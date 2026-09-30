package com.notification.application.service;
// PRD: F2-2, F2-3, F3-1, F5-1, F5-2, F5-3 → docs/prd/F2.md, docs/prd/F5.md

import com.notification.application.exception.ChannelFailureCode;
import com.notification.application.exception.NonRetryableChannelException;
import com.notification.application.exception.RetryableChannelException;
import com.notification.application.port.out.ChannelSenderPort;
import com.notification.application.port.out.NotificationLogRepositoryPort;
import com.notification.application.port.out.NotificationRepositoryPort;
import com.notification.application.port.out.ProviderCircuitPort;
import com.notification.domain.ProviderCallPermit;
import com.notification.domain.Notification;
import com.notification.domain.NotificationLog;
import com.notification.domain.NotificationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 발송 조율자.
 *
 * {@link #dispatch}는 <b>트랜잭션이 없다.</b> DB 상태 전이는 {@link DispatchStateService}의 짧은 트랜잭션 2개가 맡고,
 * 외부 발송은 그 사이 트랜잭션 밖에서 일어난다. 외부 응답을 기다리는 동안 붙잡는 자원은 스레드 하나뿐이며
 * DB 커넥션·행 락은 잡지 않는다. (RULES §2-1, §2-7)
 *
 * 실행 스레드: {@code notification-*}(이벤트 핸들러) 또는 {@code scheduling-*}(스케줄러).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDispatchService {

    /** 한 회수 실행이 왜 끝났는가. 경보와 튜닝의 근거이므로 "그냥 끝남"으로 뭉개지 않는다. */
    public enum StopReason {
        /** 볼 행이 없었다. 정상 idle. */
        EMPTY,
        /** 마지막 batch가 limit보다 적게 읽었다 = 만료 backlog를 이번 실행에서 다 따라잡았다. */
        EXHAUSTED,
        /** 실행당 batch 상한에 걸렸다. 잔량은 다음 트리거가 이어받는다. */
        MAX_BATCHES,
        /** 시간 예산을 넘겨 새 batch를 시작하지 않았다. 잔량은 다음 트리거가 이어받는다. */
        TIME_BUDGET,
        /** 읽기는 했는데 한 건도 회수하지 못했다. 다른 노드와의 경합이거나 정체 — 무한 반복 대신 멈춘다. */
        NO_PROGRESS,
        /** DB 오류. 이번 실행만 중단하고 다음 트리거에 재개한다. */
        DB_ERROR
    }

    /**
     * Stuck scan의 관측 결과.
     *
     * @param tokenMissing 만료됐지만 token이 없어 회수할 수 없는 이상 행 수. 회수 대상에서 제외하고 세기만 한다
     * @param stopReason   {@link StopReason} — MAX_BATCHES/TIME_BUDGET이면 아직 잔량이 남아 있다
     */
    public record StuckRecoveryResult(int selected, int recovered, int tokenMissing, int fencedOut,
                                      int batches, StopReason stopReason) {
    }

    private final NotificationRepositoryPort notificationRepositoryPort;
    private final ChannelSenderPort channelSenderPort;
    private final NotificationLogRepositoryPort notificationLogRepositoryPort;
    private final DispatchStateService dispatchStateService;
    private final RetrySchedulePolicy retrySchedulePolicy;
    private final RetryDecisionPolicy retryDecisionPolicy;
    private final ProviderCircuitPort providerCircuitPort;

    @org.springframework.beans.factory.annotation.Value("${notification.scheduler.stuck.batch-size:100}")
    private int stuckRecoveryBatchSize;

    /** 한 실행에서 시작할 수 있는 최대 batch 수. 회수가 DB를 장시간 독점하지 않게 하는 상한이다. */
    @org.springframework.beans.factory.annotation.Value("${notification.scheduler.stuck.max-batches:5}")
    private int stuckRecoveryMaxBatches;

    /** 이 시간을 넘기면 <b>새 batch를 시작하지 않는다.</b> 실행 중인 SQL을 중단시키는 타임아웃이 아니다. */
    @org.springframework.beans.factory.annotation.Value("${notification.scheduler.stuck.time-budget-ms:2000}")
    private long stuckRecoveryTimeBudgetMs;

    /**
     * 외부 호출을 시작하려면 최소한 이만큼의 lease가 남아 있어야 한다.
     * 어댑터 최악 소요 + 결과 반영 여유이며, 기동 시 {@code DispatchInvariantValidator}가 정합성을 검사한다.
     */
    @org.springframework.beans.factory.annotation.Value("${notification.dispatch.min-remaining-lease-seconds:220}")
    private int minRemainingLeaseSeconds;

    /** 차단으로 보류한 작업을 다시 집기까지의 대기. 차단 해제 전에 또 선점하지 않도록 둔다. */
    @org.springframework.beans.factory.annotation.Value("${notification.provider.circuit.hold-seconds:30}")
    private int providerHoldSeconds;

    /** 제공자 scope 식별자. 실제 업체 연결 전까지는 단일 scope를 쓴다. */
    @org.springframework.beans.factory.annotation.Value("${notification.provider.scope:EMAIL:default}")
    private String providerScope;

    /**
     * PESSIMISTIC_WRITE(SKIP LOCKED)는 트랜잭션이 열려 있어야 동작한다.
     * 트랜잭션 커밋 시 DB 락 해제 → 이후 dispatch()가 각자 새 트랜잭션으로 실행된다.
     */
    @Transactional
    public List<Long> fetchPendingIds(int limit) {
        return notificationRepositoryPort.findPendingWithLock(limit)
                .stream().map(Notification::getId)
                .toList();
    }

    /**
     * lease가 만료된 PROCESSING 알림을 재시도 실패 1회로 복구한다. <b>이 메서드에는 트랜잭션이 없다.</b>
     *
     * <pre>
     * batch 1 (짧은 TX) → batch 2 (짧은 TX) → ... → 최대 5 batch 또는 2초 예산에서 종료
     * </pre>
     *
     * <p>왜 반복하나: 노드 한 대가 죽으면 만료 행이 batch 크기보다 훨씬 많이 생긴다. 한 실행에 100건만
     * 회수하면 300건 밀린 상황이 주기 × 3만큼 늦어진다. 왜 무한 반복은 안 하나: 회수가 DB와 커넥션을
     * 오래 독점하고, 회수된 건이 다시 due 큐로 쏟아져 아직 죽어 있는 제공자를 더 세게 때린다.
     * (제공자 전역 차단기·rate limit은 P2로 미구현이므로 지금은 회수 속도 자체가 유일한 완충이다.)
     *
     * <p>회수 주기를 줄이는 것은 "만료를 얼마나 빨리 발견하는가"만 바꾼다. 발송 중인 작업을 빼앗지 않는
     * 근거는 주기가 아니라 {@code lease_until <= UTC_TIMESTAMP(6)} 조건이며, lease 자체는
     * {@code DispatchInvariantValidator}가 기동 시 지킨다.
     *
     * <p>lease 만료 여부는 claim 때 기록한 {@code lease_until}로 판정한다. 호출자가 별도
     * threshold를 다시 전달하면 조회 기준과 claim 기준이 달라질 수 있으므로 받지 않는다.
     */
    public StuckRecoveryResult recoverStuck() {
        long startedAtNanos = System.nanoTime();
        int selected = 0;
        int recovered = 0;
        int fencedOut = 0;
        int batches = 0;
        StopReason stopReason = StopReason.MAX_BATCHES;

        for (int i = 0; i < stuckRecoveryMaxBatches; i++) {
            // 첫 batch는 항상 실행한다. 예산은 "다음 batch를 시작할지"만 결정한다.
            if (i > 0 && elapsedMillis(startedAtNanos) >= stuckRecoveryTimeBudgetMs) {
                stopReason = StopReason.TIME_BUDGET;
                break;
            }

            DispatchStateService.StuckRecoveryBatch batch;
            try {
                batch = dispatchStateService.recoverStuckBatch(stuckRecoveryBatchSize);
            } catch (DataAccessException e) {
                // 삼키지 않는다. 남은 잔량은 다음 트리거가 같은 조건으로 다시 읽는다.
                log.error("[복구] batch {} DB 오류로 이번 실행 중단. 다음 트리거에 재개한다.", i + 1, e);
                stopReason = StopReason.DB_ERROR;
                break;
            }

            batches++;
            selected += batch.selected();
            recovered += batch.recovered();
            fencedOut += batch.fencedOut();

            if (batch.selected() == 0) {
                stopReason = StopReason.EMPTY;
                break;
            }
            if (batch.recovered() == 0) {
                // 같은 행을 계속 다시 읽는 낭비를 막는다. 경합이면 fencedOut이, 정체면 0이 같이 찍힌다.
                stopReason = StopReason.NO_PROGRESS;
                log.warn("[복구] 진전 없음으로 중단. selected={}, fencedOut={}", batch.selected(), batch.fencedOut());
                break;
            }
            if (batch.selected() < stuckRecoveryBatchSize) {
                stopReason = StopReason.EXHAUSTED;
                break;
            }
        }

        // 회수 대상에서 제외한 이상 행은 조용히 사라지면 안 된다. 사람이 판단할 때까지 경보로 남긴다.
        int tokenMissing = (int) notificationRepositoryPort.countExpiredProcessingWithoutToken();
        if (tokenMissing > 0) {
            log.error("[복구] lease 만료 + token 없는 이상 행 {}건. 임의 재발송하지 않으니 데이터 보정 필요.", tokenMissing);
        }
        if (selected > 0) {
            log.warn("[복구] Stuck {}건 중 {}건 회수. batches={}, fencedOut={}, 종료사유={}",
                    selected, recovered, batches, fencedOut, stopReason);
        }
        return new StuckRecoveryResult(selected, recovered, tokenMissing, fencedOut, batches, stopReason);
    }

    private Optional<Boolean> withinExpiry(DispatchStateService.ClaimedNotification workItem) {
        try {
            return notificationRepositoryPort.withinExpiry(workItem.notificationId(), workItem.processingToken());
        } catch (DataAccessException e) {
            // 확인할 수 없으면 기한 판정을 보류하고 기존 lease 검사에만 맡긴다. 호출을 막지는 않는다.
            log.error("[발송 방어] 기한 확인 실패. id={}", workItem.notificationId(), e);
            return Optional.of(Boolean.TRUE);
        }
    }

    /**
     * 대기 중 기한이 지난 알림을 정리한다. (재시도 정책 §5.5)
     *
     * <p>선점 조건이 만료 건을 제외하므로 이 정리가 없으면 그 행들이 영영 PENDING으로 남는다.
     * 회수와 같은 bounded 예산으로 돌리고, 상태는 CAS로 다시 확인한다 — 정리와 선점이 겹쳐도
     * 한쪽만 이겨야 한다. <b>PROCESSING은 건드리지 않는다.</b> 이미 외부 호출이 시작됐을 수 있고,
     * 그 결과 반영은 token/lease 경로가 담당한다.
     *
     * @return 실제로 만료 종료한 건수
     */
    public int expireOverdue() {
        int expired = 0;
        for (int batch = 0; batch < stuckRecoveryMaxBatches; batch++) {
            int inBatch = dispatchStateService.expireOverdueBatch(stuckRecoveryBatchSize);
            expired += inBatch;
            if (inBatch < stuckRecoveryBatchSize) break;   // 더 읽을 게 없다
        }
        if (expired > 0) {
            log.warn("[만료 정리] 기한이 지난 대기 알림 {}건을 종료했다.", expired);
        }
        return expired;
    }

    /**
     * 재시도 가능 실패의 결과를 정책에 물어 적용한다.
     *
     * <p>"몇 번까지, 언제까지"는 등록 당시 정책 버전이 정하고, 이 메서드는 그 결정을 기록만 한다.
     * 기한이 지났거나 다음 기회가 기한 밖이면 재시도 없이 종료한다.
     */
    private void applyRetryableFailure(Notification notification, String failureCode,
                                       Optional<java.time.Duration> retryAfter) {
        RetryDecisionPolicy.Decision decision = retryDecisionPolicy.onRetryableFailure(
                notification, notificationRepositoryPort.currentTime(), retryAfter);
        notification.applyRetryableFailure(
                decision.status(), decision.reason(), failureCode, decision.nextEligibleAt());
    }

    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    /**
     * 단일 알림 발송. <b>이 메서드에는 트랜잭션이 없다.</b>
     *
     * <pre>
     * TX-A  claim   : 선점 실패(0행)면 여기서 끝. 다른 스레드·인스턴스가 가져간 정상 스킵
     *  ---  send    : 외부 I/O. 예외를 재시도 가능/불가로 분류해 도메인 전이로 계산만 한다 (DB 접근 없음)
     * TX-B  finish  : 조건부 UPDATE로 결과 반영 + 이력
     * </pre>
     *
     * 이 지점에서 죽으면: claim 커밋 후 ~ finish 커밋 전에는 PROCESSING으로 남고, Stuck 복구(5분 주기, 10분 임계)가 회수한다.
     * finish의 DB 예외는 호출자(스케줄러·이벤트 핸들러)로 전파된다. 삼키지 않는다.
     */
    public void dispatch(Long notificationId) {
        Optional<DispatchStateService.ClaimedDispatch> claimed = dispatchStateService.claim(notificationId);
        if (claimed.isEmpty()) {
            log.debug("아직 due가 아니거나 선점/종료된 알림 스킵. id={}", notificationId);
            return;
        }
        // 즉시 경로도 같은 방어를 거친다. 반환 계약은 추정하지 않고 선점 TX가 확보한 값을 쓴다.
        Optional<ProviderCallPermit> permit = admitExternalCall(claimed.get().workItem());
        if (permit.isEmpty()) return;

        dispatchClaimed(claimed.get().snapshot(), permit.get());
    }

    /**
     * 외부 I/O 직전 방어. claim과 실제 호출 사이의 큐 대기 때문에 소유권이 바뀌거나 lease가 끝날 수 있다.
     *
     * <p>token 일치만으로는 부족하다. <b>만료된 lease의 token도 회수되기 전까지는 그대로 일치</b>하므로,
     * 그 상태로 send를 시작하면 회수된 알림을 두 노드가 같이 보낸다. 남은 시간까지 함께 본다.
     *
     * @return 발송을 시작해도 되면 true
     */
    private Optional<ProviderCallPermit> admitExternalCall(DispatchStateService.ClaimedNotification workItem) {
        try {
            // 정상 경로는 이 한 문장으로 끝난다: 검사 통과 + 시도 의도 기록.
            if (dispatchStateService.tryBeginExternalCall(workItem, minRemainingLeaseSeconds)) {
                return acquireProviderPermit(workItem);
            }
        } catch (DataAccessException e) {
            // 확인할 수 없으면 시작하지 않는다. 행은 PROCESSING으로 남아 lease 회수가 맡는다.
            log.error("[발송 방어] 소유권 확인 실패로 외부 호출을 시작하지 않는다. id={}", workItem.notificationId(), e);
            return Optional.empty();
        }

        // 여기부터는 드문 경로다. 무엇이 막았는지 따져 반환·보류·무시를 고른다.
        Optional<Long> remaining;
        try {
            remaining = notificationRepositoryPort.remainingLeaseSeconds(
                    workItem.notificationId(), workItem.processingToken());
        } catch (DataAccessException e) {
            log.error("[발송 방어] 잔여 lease 확인 실패. id={}", workItem.notificationId(), e);
            return Optional.empty();
        }

        if (remaining.isEmpty()) {
            log.debug("[발송 방어] 소유권이 이미 만료/변경됨. id={}", workItem.notificationId());
            return Optional.empty();
        }
        if (remaining.get() <= 0) {
            // 손대지 않는다. 다른 노드가 회수 중일 수 있고, 여기서 되돌리면 그 경로와 경합한다.
            log.warn("[발송 방어] lease가 이미 만료돼 호출하지 않는다. 회수 경로에 맡긴다. id={}", workItem.notificationId());
            return Optional.empty();
        }
        // 기한 판정이 예산보다 먼저다. 기한이 끝났으면 되돌려봐야 다시 보낼 수 없다.
        if (Boolean.FALSE.equals(withinExpiry(workItem).orElse(Boolean.TRUE))) {
            log.warn("[발송 방어] 대기 중 기한이 지나 호출하지 않는다. id={}", workItem.notificationId());
            dispatchStateService.expireClaimed(workItem);
            return Optional.empty();
        }
        // 아직 내 소유이고 호출 전이 확실하다 → 실패로 세지 않고 원래 대기 상태로 돌려준다.
        log.warn("[발송 방어] 잔여 lease {}초 < 필요 예산 {}초. 호출 전에 반환한다. id={}",
                remaining.get(), minRemainingLeaseSeconds, workItem.notificationId());
        dispatchStateService.releaseClaim(workItem, DispatchStateService.LEASE_BUDGET_INSUFFICIENT);
        return Optional.empty();
    }

    /**
     * 제공자 scope의 공유 허가. 차단 중이면 <b>호출하지 않고</b> 보류한다.
     *
     * <p>보류는 실패가 아니다. 호출 자체를 하지 않았으므로 실패 예산도 시도 이력도 남기지 않는다.
     * 다만 원래 예약 시각 그대로 돌려놓으면 차단이 풀릴 때까지 선점·반환만 반복하므로,
     * 차단 해제 예상 시각 뒤로 미뤄 되돌린다. (재시도 정책 §5.6)
     */
    private Optional<ProviderCallPermit> acquireProviderPermit(DispatchStateService.ClaimedNotification workItem) {
        String scope = providerScope;
        Optional<ProviderCallPermit> permit;
        try {
            permit = providerCircuitPort.tryAcquire(scope);
        } catch (DataAccessException e) {
            // 공유 상태를 못 읽으면 새 외부 호출을 시작하지 않는다. 행은 lease 회수에 맡긴다.
            log.error("[제공자 차단기] 상태 확인 실패로 호출하지 않는다. id={}", workItem.notificationId(), e);
            return Optional.empty();
        }
        if (permit.isEmpty()) {
            LocalDateTime retryAt = notificationRepositoryPort.currentTime().plusSeconds(providerHoldSeconds);
            dispatchStateService.holdForProvider(workItem, retryAt);
        }
        return permit;
    }

    /**
     * {@link DispatchStateService#claimDueBatch(int)}가 이미 PROCESSING으로 선점한 작업을 발송한다.
     * worker 스레드에서는 ID/token으로 소유권과 <b>남은 lease</b>를 다시 읽는다. 엔티티를 스레드 간에
     * 넘기지 않고, 큐에서 오래 기다린 옛 worker가 외부 I/O를 시작하지 않게 하기 위함이다.
     */
    public void dispatchClaimed(DispatchStateService.ClaimedNotification workItem) {
        Optional<ProviderCallPermit> permit = admitExternalCall(workItem);
        if (permit.isEmpty()) return;

        Optional<Notification> snapshot = notificationRepositoryPort.findById(workItem.notificationId());
        if (snapshot.isEmpty() || snapshot.get().getStatus() != NotificationStatus.PROCESSING) {
            log.debug("batch 작업의 소유권이 확인 직후 바뀌어 실행하지 않음. id={}", workItem.notificationId());
            return;
        }
        dispatchClaimed(snapshot.get(), permit.get());
    }

    /**
     * 로드한 PROCESSING 엔티티의 외부 I/O와 결과 반영을 수행한다.
     *
     * <p>permit을 인수로 받는 이유: 허가 없이 부를 수 있는 통로를 남기면 차단기를 우회하게 된다.
     * 허가는 호출 직전 admission이 발급하고, 결과는 같은 permit의 세대로 돌려준다.
     */
    private void dispatchClaimed(Notification notification, ProviderCallPermit permit) {
        Long notificationId = notification.getId();

        // 이번 시도 회차 = 현재 실패 횟수 + 1 (결과 반영 전에 캡처)
        int attemptNumber = notification.getRetryCount() + 1;
        String failureCode = null;

        try {
            channelSenderPort.send(notification);
            notification.markSent();
            providerCircuitPort.recordSuccess(permit);
            log.info("알림 발송 성공. id={}, channel={}", notificationId, notification.getChannel());

        } catch (RetryableChannelException e) {
            failureCode = e.getFailureCode().name();
            // 수신자 오류가 아닌 일시 실패는 scope 전체의 신호로 본다. 주소 오류는 아래 영구 실패로 간다.
            providerCircuitPort.recordFailure(permit, true, e.getRetryAfter());
            applyRetryableFailure(notification, failureCode, e.getRetryAfter());
            log.warn("재시도 가능 발송 실패. id={}, code={}, 결과={}, 실패횟수={}",
                    notificationId, failureCode, notification.getStatus(), notification.getRetryCount());

        } catch (NonRetryableChannelException e) {
            failureCode = e.getFailureCode().name();
            // 인증 실패는 scope 문제, 수신자·요청 오류는 그 알림만의 문제다.
            providerCircuitPort.recordFailure(permit,
                    e.getFailureCode() == ChannelFailureCode.CHANNEL_AUTH_FAILED, Optional.empty());
            notification.markFailed(failureCode);
            log.error("재시도 불가 발송 실패. id={}, code={}", notificationId, failureCode);

        } catch (Exception e) {
            // CHANNEL_UNAVAILABLE로 뭉개지 않는다. 그러면 코드 버그(NPE 등)가 외부 장애로 위장돼
            // 영원히 안 고쳐진다. 보수적으로 재시도하되 코드는 분리한다. 원문은 로그에만 (RULES §5-4)
            failureCode = ChannelFailureCode.CHANNEL_UNKNOWN.name();
            // 우리 버그일 수도 업체 문제일 수도 있다. 보수적으로 scope 신호에 넣되 코드는 분리해 둔다.
            providerCircuitPort.recordFailure(permit, true, Optional.empty());
            applyRetryableFailure(notification, failureCode, Optional.empty());
            log.error("분류되지 않은 발송 오류. id={}, code={}", notificationId, failureCode, e);
        }

        dispatchStateService.finish(notification, attemptNumber, failureCode);
    }

}
