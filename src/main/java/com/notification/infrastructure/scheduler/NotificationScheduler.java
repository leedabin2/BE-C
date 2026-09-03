package com.notification.infrastructure.scheduler;
// PRD: F5-1, F5-2, F5-3, O-1 → docs/prd/F5.md

import com.notification.application.service.NotificationDispatchService;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * 알림 재처리 및 Stuck 복구 스케줄러.
 *
 * 두 스케줄러는 역할이 다르다:
 * - retryScheduler: 발송 실패(RETRYING)나 미처리(PENDING) 알림을 재발송
 * - stuckRecoveryScheduler: 서버 장애로 PROCESSING에 갇힌 알림을 PENDING으로 복구
 *
 * ShedLock이 다중 인스턴스 환경에서 한 인스턴스만 실행하도록 보장한다 (1차 방어).
 * findPendingWithLock의 SKIP LOCKED가 스레드 간 중복 행 처리를 방지한다 (2차 방어).
 * dispatch() 내부의 조건부 UPDATE가 이벤트 핸들러와의 최종 경합을 방지한다 (3차 방어).
 * recoverStuck의 조건부 UPDATE가 stuck 복구 시 이미 완료된 행을 안전 스킵한다 (4차 방어).
 */
@Slf4j
@Component
public class NotificationScheduler {

    private final NotificationDispatchService dispatchService;
    private final Executor batchExecutor;

    public NotificationScheduler(NotificationDispatchService dispatchService,
                                 @Qualifier("batchExecutor") Executor batchExecutor) {
        this.dispatchService = dispatchService;
        this.batchExecutor = batchExecutor;
    }

    @Value("${notification.scheduler.retry.batch-size:100}")
    private int retryBatchSize;

    @Value("${notification.scheduler.stuck.threshold-minutes:10}")
    private int stuckThresholdMinutes;

    /**
     * PENDING/RETRYING 알림 재처리.
     *
     * 이 메서드는 "조회 + 제출"만 한다. 발송은 batchExecutor가 병렬로 처리한다.
     *
     * fixedDelay: 이전 실행 완료 후 N ms 뒤에 실행.
     * lockAtMostFor: 타임아웃이 아니라 락 만료다. 넘겨도 작업은 안 멈추고 락만 풀린다.
     * lockAtLeastFor: 너무 빨리 끝나도 이 시간은 락을 유지해 즉시 재실행을 방지.
     */
    @Scheduled(fixedDelayString = "${notification.scheduler.retry.fixed-delay-ms:60000}")
    @SchedulerLock(name = "retryScheduler", lockAtMostFor = "55s", lockAtLeastFor = "10s")
    public void retryScheduler() {
        // fetchPendingIds 트랜잭션 커밋 시 SKIP LOCKED 해제 → 이후 dispatch는 각자 새 트랜잭션
        List<Long> ids = dispatchService.fetchPendingIds(retryBatchSize);
        if (ids.isEmpty()) return;

        log.info("[재처리] 대상 {}건 조회 → batchExecutor 제출", ids.size());
        for (Long id : ids) {
            // 스케줄러 스레드에서 직접 발송하지 않는다. 제출만 하고 즉시 반환한다.
            // 직접 하면 100건 × 외부 I/O 만큼 이 스레드가 묶여 lockAtMostFor(55s)를 넘고,
            // 락이 만료되면 다른 인스턴스가 같은 배치를 다시 돌기 시작한다. (DECISIONS D-001)
            batchExecutor.execute(() -> {
                try {
                    dispatchService.dispatch(id);
                } catch (Exception e) {
                    log.error("[재처리] dispatch 오류. id={}", id, e);
                }
            });
        }
    }

    /**
     * Stuck PROCESSING 알림 복구.
     *
     * 정상 발송은 최대 수십 초 내 완료된다.
     * threshold-minutes(기본 10분) 이상 PROCESSING 상태이면 비정상으로 판정하고 PENDING으로 되돌린다.
     * 복구된 알림은 retryScheduler 다음 사이클에서 재처리된다.
     */
    @Scheduled(fixedDelayString = "${notification.scheduler.stuck.fixed-delay-ms:300000}")
    @SchedulerLock(name = "stuckRecoveryScheduler", lockAtMostFor = "PT4M55S", lockAtLeastFor = "PT30S")
    public void stuckRecoveryScheduler() {
        dispatchService.recoverStuck(stuckThresholdMinutes);
    }
}
