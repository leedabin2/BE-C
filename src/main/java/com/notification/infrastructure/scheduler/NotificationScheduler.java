package com.notification.infrastructure.scheduler;
// PRD: F5-1, F5-2, F5-3, O-1 → docs/prd/F5.md

import com.notification.application.service.DispatchStateService;
import com.notification.application.service.DispatchStateService.ClaimedNotification;
import com.notification.application.service.NotificationDispatchService;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 알림 재처리 및 Stuck 복구 스케줄러.
 *
 * 두 스케줄러는 역할이 다르다:
 * - retryScheduler: 발송 실패(RETRYING)나 미처리(PENDING) 알림을 재발송
 * - stuckRecoveryScheduler: 서버 장애로 PROCESSING에 갇힌 알림을 PENDING으로 복구
 *
 * retryScheduler는 모든 인스턴스에서 실행된다. DB의 {@code SKIP LOCKED + claim CAS}가 작업을 분배한다.
 * stuckRecoveryScheduler만 ShedLock으로 한 인스턴스가 실행한다. 만료 lease 회수는 중복할 이유가 없기 때문이다.
 * 두 스케줄러의 주기는 서로 다르다(due 1초 / 회수 30초). 회수 뒤에도 backoff가 지나야 다시 due가 된다.
 */
@Slf4j
@Component
public class NotificationScheduler {

    private final NotificationDispatchService dispatchService;
    private final DispatchStateService dispatchStateService;
    private final ThreadPoolTaskExecutor batchExecutor;
    private final BatchExecutionSlots executionSlots;

    public NotificationScheduler(NotificationDispatchService dispatchService,
                                 DispatchStateService dispatchStateService,
                                 @Qualifier("batchExecutor") ThreadPoolTaskExecutor batchExecutor,
                                 BatchExecutionSlots executionSlots) {
        this.dispatchService = dispatchService;
        this.dispatchStateService = dispatchStateService;
        this.batchExecutor = batchExecutor;
        this.executionSlots = executionSlots;
    }

    @Value("${notification.scheduler.retry.batch-size:100}")
    private int retryBatchSize;

    /** 한 scan에서 슬롯을 다시 채울 최대 횟수. 처리량을 scan 주기에 묶지 않되 상한은 둔다. */
    @Value("${notification.scheduler.retry.max-rounds:20}")
    private int retryMaxRounds;

    /** 이 시간을 넘기면 새 라운드를 시작하지 않는다. 진행 중인 제출을 끊는 타임아웃이 아니다. */
    @Value("${notification.scheduler.retry.round-budget-ms:500}")
    private long retryRoundBudgetMs;

    /**
     * PENDING/RETRYING 알림 재처리.
     *
     * 이 메서드는 "슬롯 확보 + 조회 + 제출"만 한다. 발송은 batchExecutor가 병렬로 처리한다.
     *
     * <p>모든 인스턴스가 동시 실행한다. <b>지금 실행할 수 있는 만큼만</b> 슬롯을 잡고 그 수만큼만
     * DB에서 원자 claim한 뒤, ID/token 스냅샷만 worker에게 넘긴다. A/B가 같은 due 행을 조회해도
     * 선점 CAS가 한쪽만 통과시키므로 둘 다 외부 발송을 하지는 않는다.
     *
     * <p>선점하고도 실행하지 못한 작업은 lease만 태운다. 그래서 큐 길이가 아니라 실행 슬롯을 기준으로
     * 가져오고, 못 가져간 잔량은 메모리가 아니라 DB에 남겨 다음 scan(또는 여유 있는 다른 노드)에 맡긴다.
     *
     * <p>슬롯 회계: 확보한 슬롯은 <b>모든 경로</b>에서 정확히 한 번 반환된다 —
     * 선택된 행이 적으면 즉시, 제출이 거절되면 그 자리에서, 제출됐으면 worker의 finally에서.
     */
    @Scheduled(fixedDelayString = "${notification.scheduler.retry.fixed-delay-ms:1000}")
    public void retryScheduler() {
        long startedAtNanos = System.nanoTime();
        for (int round = 0; round < retryMaxRounds; round++) {
            // 첫 라운드는 항상 돈다. 예산은 "한 번 더 채울지"만 결정한다.
            if (round > 0 && (System.nanoTime() - startedAtNanos) / 1_000_000L >= retryRoundBudgetMs) return;

            int acquired = executionSlots.tryAcquireUpTo(retryBatchSize);
            if (acquired == 0) return;                  // 워커가 가득 찼다. 다음 scan에 다시 본다

            RoundResult round_ = dispatchRound(acquired);
            if (round_.submitted() == 0 && round_.claimed() > 0) {
                // 전부 거절됐다. 여기서 또 채우면 같은 행을 선점·반환만 반복하며 DB만 두드린다.
                log.warn("[재처리] {}건 모두 제출 거절. 이번 scan은 여기서 멈춘다.", round_.claimed());
                return;
            }
            if (round_.claimed() < acquired) return;    // due를 다 비웠다(또는 경쟁 노드가 가져갔다)
        }
    }

    /** 한 라운드의 결과. claimed는 DB에서 선점한 수, submitted는 실제로 worker에게 넘긴 수다. */
    private record RoundResult(int claimed, int submitted) {
    }

    /**
     * 확보한 슬롯만큼 DB에서 선점해 worker에게 넘기는 한 라운드.
     *
     * <p>왜 한 scan에 여러 라운드인가: 슬롯이 30이고 scan이 1초면 처리량이 30건/초로 묶인다.
     * 워커가 이미 끝나 슬롯이 비었는데도 다음 scan까지 노는 것은 낭비다. 반대로 무한 반복은
     * scheduler 스레드와 DB를 독점하므로 라운드 수와 시간 예산으로 상한을 둔다.
     *
     * @return 실제로 선점한 건수. 확보한 슬롯보다 적으면 더 가져올 due가 없다는 뜻이다
     */
    private RoundResult dispatchRound(int acquired) {
        List<ClaimedNotification> claimed;
        try {
            claimed = dispatchStateService.claimDueBatch(acquired);
        } catch (RuntimeException e) {
            executionSlots.release(acquired);        // DB 실패로 아무것도 못 가져갔다
            log.error("[재처리] due claim 실패. 다음 scan에 재시도한다.", e);
            return new RoundResult(0, 0);
        }

        // 선택된 행이 요청보다 적으면(대개 due가 그만큼 없음) 남는 슬롯은 즉시 돌려준다.
        executionSlots.release(acquired - claimed.size());
        if (claimed.isEmpty()) return new RoundResult(0, 0);

        log.debug("[재처리] due {}건 원자 claim → batchExecutor 제출 (확보 슬롯={})", claimed.size(), acquired);
        int unhandled = claimed.size();
        int submitted = 0;
        try {
            for (ClaimedNotification workItem : claimed) {
                unhandled--;
                if (submitToWorker(workItem)) {
                    submitted++;
                } else {
                    executionSlots.release(1);
                    releaseClaimQuietly(workItem);
                }
            }
        } finally {
            // 루프가 예외로 끊겨도 남은 몫의 슬롯은 반환한다(DB 행은 PROCESSING으로 남아 lease 회수 대상).
            executionSlots.release(unhandled);
        }
        return new RoundResult(claimed.size(), submitted);
    }

    /** @return 제출에 성공했으면 true. 이때 슬롯 반환 책임은 worker에게 넘어간다. */
    private boolean submitToWorker(ClaimedNotification workItem) {
        try {
            // 스케줄러 스레드가 외부 I/O를 하면 다음 scan이 멈춘다. runnable은 이미 claim된 작업만 발송한다.
            batchExecutor.execute(() -> {
                try {
                    dispatchService.dispatchClaimed(workItem);
                } catch (Exception e) {
                    log.error("[재처리] dispatch 오류. id={}", workItem.notificationId(), e);
                } finally {
                    executionSlots.release(1);
                }
            });
            return true;
        } catch (TaskRejectedException e) {
            // 큐 0에서는 슬롯이 남아 있어도 worker handoff 경합으로 거절될 수 있다. 정상 경로다.
            // CallerRuns로 대신 실행하면 scheduler 스레드가 외부 I/O에 묶이므로 쓰지 않는다.
            log.warn("[재처리] worker 제출 거절. 대기 상태로 되돌린다. id={}", workItem.notificationId());
            return false;
        }
    }

    /**
     * 한 건의 반환 실패가 뒤에 이미 선점한 작업들을 방치하게 두지 않는다.
     * 반환하지 못한 행은 PROCESSING으로 남고 lease 회수가 이어받는다.
     */
    private void releaseClaimQuietly(ClaimedNotification workItem) {
        try {
            dispatchStateService.releaseBatchClaim(workItem);
        } catch (RuntimeException e) {
            log.error("[재처리] claim 반환 실패. PROCESSING으로 두고 lease 회수에 맡긴다. id={}",
                    workItem.notificationId(), e);
        }
    }

    /**
     * Stuck PROCESSING 알림 복구.
     *
     * 정상 발송은 최대 수십 초 내 완료된다.
     * threshold-minutes(기본 10분) 이상 PROCESSING 상태이면 비정상으로 판정하고
     * RETRYING(실패 1회) 또는 FAILED(PROCESSING_STUCK)로 전이한다.
     * 복구된 알림은 backoff가 지난 뒤 retryScheduler가 다시 집는다 — 이 스케줄러는 직접 발송하지 않는다.
     *
     * <p>실행당 예산(batch 수·시간)과 종료 사유는 {@code recoverStuck()}이 관리하고 로그로 남긴다.
     * ShedLock은 동시 실행을 줄이는 용도이지 정확성의 근거가 아니다. 락이 만료돼 두 노드가 겹쳐도
     * {@code token + lease 만료} CAS가 한 행을 한 번만 반영한다. lockAtMostFor(60초)는 실행 예산보다
     * 넉넉하게 두되, 느린 DB에서 마지막 batch가 이를 넘길 수 있다는 점은 그대로 남는다.
     */
    @Scheduled(fixedDelayString = "${notification.scheduler.stuck.fixed-delay-ms:30000}")
    @SchedulerLock(name = "stuckRecoveryScheduler", lockAtMostFor = "PT60S", lockAtLeastFor = "PT1S")
    public void stuckRecoveryScheduler() {
        dispatchService.recoverStuck();
    }

    /**
     * 기한이 지난 채 대기 중인 알림 정리.
     *
     * <p>선점 조건이 만료 건을 제외하므로, 이 정리가 없으면 그 행들이 영영 PENDING으로 남아
     * backlog 지표만 부풀린다. 회수와 같은 이유로 ShedLock을 쓰고 실행당 예산을 둔다.
     * 발송은 하지 않으며 PROCESSING도 건드리지 않는다.
     */
    @Scheduled(fixedDelayString = "${notification.scheduler.expiry.fixed-delay-ms:30000}")
    @SchedulerLock(name = "expiryCleanupScheduler", lockAtMostFor = "PT60S", lockAtLeastFor = "PT1S")
    public void expiryCleanupScheduler() {
        dispatchService.expireOverdue();
    }
}
