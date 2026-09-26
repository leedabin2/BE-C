package com.notification.application.service;
// PRD: F2-2, F2-3, F5-3 (선점·결과 반영 CAS) → docs/prd/F2.md, docs/prd/F3.md

import com.notification.application.port.out.DispatchHistoryRepositoryPort;
import com.notification.application.port.out.NotificationLogRepositoryPort;
import com.notification.application.port.out.NotificationRepositoryPort;
import com.notification.domain.DispatchFailureReason;
import com.notification.domain.DispatchHistory;
import com.notification.domain.Notification;
import com.notification.domain.NotificationLog;
import com.notification.domain.NotificationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 발송의 "DB 상태 전이"만 담당하는 짧은 트랜잭션 2개.
 *
 * <pre>
 * TX-A claim()  : 조건부 UPDATE로 PROCESSING 선점 → 스냅샷 조회 → 커밋   (DB 자원 즉시 반납)
 *  ---           : 외부 발송은 {@link NotificationDispatchService}가 트랜잭션 밖에서 수행
 * TX-B finish() : 조건부 UPDATE로 결과 반영 + 이력 → 커밋
 * </pre>
 *
 * 왜 별도 빈인가: {@code @Transactional}은 프록시로 동작하므로 같은 클래스 내부 호출에는 걸리지 않는다.
 * 조율자({@code dispatch()})와 트랜잭션 단위를 다른 빈으로 나눠야 경계가 실제로 생긴다. (RULES §3-3)
 *
 * 실행 스레드: {@code notification-*}(이벤트 핸들러) 또는 {@code scheduling-*}(스케줄러).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DispatchStateService {

    /** 결과 반영이 0행일 때 남기는 이력 사유. Stuck 복구가 먼저 행을 되돌린 경우다. */
    public static final String LATE_RESULT_IGNORED = "LATE_RESULT_IGNORED";
    public static final String BATCH_SUBMISSION_REJECTED = "BATCH_SUBMISSION_REJECTED";
    /** 큐 대기가 길어 남은 lease로는 발송을 끝낼 수 없어, 외부 호출 전에 되돌린 경우. */
    public static final String LEASE_BUDGET_INSUFFICIENT = "LEASE_BUDGET_INSUFFICIENT";
    /**
     * 제공자 scope가 차단 중이라 <b>호출하지 않고</b> 보류한 경우.
     * 외부 호출이 없었으므로 실패 예산을 소모하지 않는다. (재시도 정책 §5.6)
     */
    public static final String PROVIDER_CIRCUIT_OPEN = "PROVIDER_CIRCUIT_OPEN";

    /**
     * Stuck 회수 한 batch의 결과. {@code selected}가 limit과 같으면 뒤에 더 남아 있을 수 있고,
     * {@code recovered}가 0이면 이 batch는 진전이 없었다는 뜻이다(다른 노드가 먼저 가져갔거나 정체).
     */
    public record StuckRecoveryBatch(int selected, int recovered, int fencedOut) {
    }

    /**
     * 단건 선점 결과. 발송에 쓸 스냅샷과, 호출 전 반환에 쓸 계약을 함께 돌려준다.
     * 반환 계약을 호출자가 추정하지 않도록 선점 TX가 확보한 값을 그대로 넘긴다.
     */
    public record ClaimedDispatch(Notification snapshot, ClaimedNotification workItem) {
    }

    /**
     * 배치 claim 결과. 스레드 경계를 넘길 때 JPA 엔티티를 넘기지 않고 ID/token만 전달한다.
     * previous* 값은 worker 큐 제출이 거절됐을 때 정확히 원래 대기 상태로 되돌리기 위한 값이다.
     */
    public record ClaimedNotification(Long notificationId,
                                      String processingToken,
                                      NotificationStatus previousStatus,
                                      LocalDateTime previousNextRetryAt) {
    }

    private final NotificationRepositoryPort notificationRepositoryPort;
    private final DispatchHistoryRepositoryPort dispatchHistoryRepositoryPort;
    private final NotificationLogRepositoryPort notificationLogRepositoryPort;
    private final RetryDecisionPolicy retryDecisionPolicy;

    @Value("${notification.scheduler.stuck.threshold-minutes:10}")
    private int processingLeaseMinutes;

    /**
     * TX-A. PENDING/RETRYING → PROCESSING 선점을 시도하고, 성공하면 발송에 필요한 스냅샷을 돌려준다.
     *
     * 반환된 엔티티는 커밋과 함께 detached 된다. 호출자는 이를 읽기 전용 스냅샷으로 다루고,
     * 상태 변경은 도메인 메서드로 계산한 뒤 {@link #finish}의 조건부 UPDATE로만 기록한다.
     *
     * @return 선점 성공 시 스냅샷. 0행(아직 due가 아니거나 다른 실행자가 선점/완료)이면 empty — 에러가 아니다
     */
    @Transactional
    public Optional<ClaimedDispatch> claim(Long notificationId) {
        // 선점 전에 "되돌릴 상태"를 읽어 둔다. 사후 스냅샷은 이미 PROCESSING이라 원래 값을 알 수 없고,
        // 회수된 TYPE_V1 행은 RETRYING인데 retryCount가 0이라 카운터로 추정할 수도 없다.
        Optional<Notification> before = notificationRepositoryPort.findById(notificationId);
        if (before.isEmpty()) return Optional.empty();
        NotificationStatus previousStatus = before.get().getStatus();
        LocalDateTime previousNextRetryAt = before.get().getNextRetryAt();
        if (previousStatus != NotificationStatus.PENDING && previousStatus != NotificationStatus.RETRYING) {
            return Optional.empty();
        }

        String processingToken = UUID.randomUUID().toString();
        // 읽은 상태를 조건에 넣는다. 그 사이 상태가 바뀌었으면 0행 → 틀린 가정으로 진행하지 않는다.
        if (!notificationRepositoryPort.tryStartProcessingFrom(
                notificationId, processingToken, processingLeaseMinutes, previousStatus)) {
            return Optional.empty();
        }
        Notification notification = notificationRepositoryPort.findById(notificationId)
                .orElseThrow(() -> new IllegalStateException("선점 직후 알림을 찾을 수 없음. id=" + notificationId));

        notificationLogRepositoryPort.save(
                NotificationLog.of(notificationId, previousStatus, NotificationStatus.PROCESSING, "DISPATCH_START"));
        return Optional.of(new ClaimedDispatch(notification,
                new ClaimedNotification(notificationId, processingToken, previousStatus, previousNextRetryAt)));
    }

    /**
     * TX-A(batch). due 행을 {@code SKIP LOCKED}로 짧게 잠그고, 같은 트랜잭션 안에서 PROCESSING으로
     * 바꾼 뒤 커밋한다. 따라서 N대 scheduler가 동시에 실행돼도 한 행은 한 번만 batch worker에 제출된다.
     *
     * 외부 발송과 worker 큐 제출은 이 트랜잭션 밖에서 해야 한다. 이 메서드는 DB 선점까지만 담당한다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<ClaimedNotification> claimDueBatch(int limit) {
        if (limit <= 0) return List.of();

        List<Notification> candidates = notificationRepositoryPort.findPendingWithLock(limit);
        if (candidates.isEmpty()) return List.of();

        List<ClaimedNotification> claimed = new java.util.ArrayList<>(candidates.size());
        for (Notification candidate : candidates) {
            NotificationStatus previousStatus = candidate.getStatus();
            LocalDateTime previousNextRetryAt = candidate.getNextRetryAt();
            String processingToken = UUID.randomUUID().toString();

            // findPendingWithLock의 조건을 통과했어도, 최종 권한은 due 조건까지 포함한 CAS가 결정한다.
            if (!notificationRepositoryPort.tryStartProcessing(candidate.getId(), processingToken, processingLeaseMinutes)) {
                continue;
            }
            claimed.add(new ClaimedNotification(candidate.getId(), processingToken, previousStatus, previousNextRetryAt));
        }
        // tryStartProcessing은 clearAutomatically라 반복 중 저장하면 앞선 로그가 detach될 수 있다.
        // 모든 CAS가 끝난 뒤 한 번에 기록해 TX-A 종료 때 함께 flush한다.
        claimed.forEach(workItem -> notificationLogRepositoryPort.save(
                NotificationLog.of(workItem.notificationId(), workItem.previousStatus(),
                        NotificationStatus.PROCESSING, "BATCH_DISPATCH_CLAIMED")));
        return claimed;
    }

    /**
     * worker 큐가 가득 차 제출 자체가 실패한 경우의 보상 TX.
     * runnable이 시작되기 전이므로 외부 발송은 없었고 retryCount도 증가시키지 않는다.
     */
    @Transactional
    public void releaseBatchClaim(ClaimedNotification claimedNotification) {
        releaseClaim(claimedNotification, BATCH_SUBMISSION_REJECTED);
    }

    /**
     * <b>외부 호출을 하지 않은 것이 확실할 때만</b> 선점을 되돌린다. 실패가 아니므로 retryCount와
     * 시도 이력은 건드리지 않는다 — 여기서 실패로 세면 아무 호출도 없이 재시도 예산이 닳는다.
     *
     * <p>token 조건 UPDATE이므로 그 사이 lease가 회수돼 새 소유자가 생겼다면 0행으로 조용히 스킵된다.
     * 0행은 오류가 아니라 "이미 다른 경로가 처리 중"이라는 뜻이다.
     *
     * @param reason 이력에 남길 반환 사유. 큐 포화인지 lease 예산 부족인지 구분해야 튜닝이 가능하다
     */
    @Transactional
    public void releaseClaim(ClaimedNotification claimedNotification, String reason) {
        boolean released = notificationRepositoryPort.tryReleaseProcessing(
                claimedNotification.notificationId(), claimedNotification.processingToken(),
                claimedNotification.previousStatus(), claimedNotification.previousNextRetryAt());
        if (released) {
            notificationLogRepositoryPort.save(NotificationLog.of(claimedNotification.notificationId(), NotificationStatus.PROCESSING,
                    claimedNotification.previousStatus(), reason));
            log.warn("[claim 반환] id={}, 복원상태={}, 사유={}", claimedNotification.notificationId(),
                    claimedNotification.previousStatus(), reason);
        }
    }

    /**
     * 만료 lease 회수 <b>한 batch</b>. 짧은 트랜잭션 하나로 끝낸다.
     *
     * <p>호출자({@code NotificationDispatchService.recoverStuck})는 이 메서드를 여러 번 부르되,
     * <b>전체 반복을 하나의 트랜잭션으로 감싸지 않는다.</b> 500건 회수가 한 TX면 그동안 커넥션과
     * 행 락을 잡고 있어 접수·발송 경로가 같이 느려진다. batch마다 커밋해 잠금 구간을 짧게 끊는다.
     *
     * <p>조회는 token이 있는 행만 본다. 상태 변경은 {@code token + lease 만료} CAS로만 하므로,
     * ShedLock이 겹쳐 두 노드가 동시에 돌아도 한 행은 한 번만 반영된다(진 쪽은 fencedOut).
     *
     * @param limit 이 batch에서 볼 최대 행 수
     */
    @Transactional
    public StuckRecoveryBatch recoverStuckBatch(int limit) {
        List<Notification> stuckList = notificationRepositoryPort.findExpiredProcessingLease(limit);
        if (stuckList.isEmpty()) return new StuckRecoveryBatch(0, 0, 0);

        LocalDateTime now = notificationRepositoryPort.currentTime();
        int recovered = 0;
        int fencedOut = 0;
        for (Notification n : stuckList) {
            // 회수는 발송 실패를 관측한 것이 아니다. TYPE_V1은 실패 예산 대신 회수 횟수를 쓰고,
            // LEGACY_V0는 기존 의미(실패 1회)를 그대로 유지한다.
            RetryDecisionPolicy.Decision decision = retryDecisionPolicy.onLeaseRecovery(n, now);
            Notification.StuckRecovery recovery = Notification.StuckRecovery.from(n, decision);
            if (notificationRepositoryPort.tryRecoverStuck(n.getId(), n.getProcessingToken(), recovery)) {
                notificationLogRepositoryPort.save(NotificationLog.of(
                        n.getId(), NotificationStatus.PROCESSING, recovery.status(), recovery.failureReason()));
                recovered++;
            } else {
                // 다른 노드가 먼저 회수했거나 원래 소유자가 제때 finish했다. 덮어쓰지 않는다.
                log.debug("[복구] token/lease 경합으로 스킵. id={}", n.getId());
                fencedOut++;
            }
        }
        return new StuckRecoveryBatch(stuckList.size(), recovered, fencedOut);
    }

    /**
     * 외부 호출을 시작해도 되는지 확인하면서 <b>시작 의도를 같은 문장으로 남긴다.</b>
     *
     * <p>조건부 UPDATE 하나로 "아직 내 것이고, 끝낼 시간이 있고, 기한도 남았다"를 확인한다.
     * 검사와 기록이 나뉘면 그 사이에 소유권이 바뀔 수 있고 왕복도 두 번이 된다.
     *
     * @return 호출해도 되면 true. false면 호출자가 사유를 따져 반환/보류를 고른다
     */
    @Transactional
    public boolean tryBeginExternalCall(ClaimedNotification claimedNotification, int minRemainingSeconds) {
        return notificationRepositoryPort.tryRecordAttemptIntent(
                claimedNotification.notificationId(), claimedNotification.processingToken(), minRemainingSeconds);
    }

    /**
     * 제공자 차단으로 호출하지 못한 작업을 <b>지정한 시각 뒤로 미뤄</b> 되돌린다.
     *
     * <p>원래 예약 시각 그대로 돌려놓으면 즉시 다시 due가 되어, 차단이 풀릴 때까지 선점·반환만
     * 반복한다. 차단 해제 예상 시각 이후로 미뤄 그 낭비를 막는다. 실패가 아니므로 예산은 그대로다.
     */
    @Transactional
    public void holdForProvider(ClaimedNotification claimedNotification, LocalDateTime retryAt) {
        boolean released = notificationRepositoryPort.tryReleaseProcessing(
                claimedNotification.notificationId(), claimedNotification.processingToken(),
                claimedNotification.previousStatus(), retryAt);
        if (released) {
            notificationLogRepositoryPort.save(NotificationLog.of(claimedNotification.notificationId(),
                    NotificationStatus.PROCESSING, claimedNotification.previousStatus(), PROVIDER_CIRCUIT_OPEN));
        }
    }

    /**
     * 선점한 뒤 호출 전에 기한이 지난 것을 발견했을 때의 종료 TX.
     * 외부 호출이 없었으므로 시도 이력도 실패 예산도 남기지 않는다.
     */
    @Transactional
    public void expireClaimed(ClaimedNotification claimedNotification) {
        boolean applied = notificationRepositoryPort.tryExpireProcessing(
                claimedNotification.notificationId(), claimedNotification.processingToken());
        if (applied) {
            notificationLogRepositoryPort.save(NotificationLog.of(claimedNotification.notificationId(),
                    NotificationStatus.PROCESSING, NotificationStatus.FAILED,
                    DispatchFailureReason.EXPIRED.name()));
        }
    }

    /**
     * 대기 중 기한이 지난 알림을 한 batch 정리한다. 짧은 TX 하나로 끝낸다.
     *
     * <p>관측용 조회에 조용히 UPDATE를 섞지 않도록 정리 전용 경로로 분리했다.
     *
     * @return 이 batch에서 실제로 종료한 건수
     */
    @Transactional
    public int expireOverdueBatch(int limit) {
        List<Notification> overdue = notificationRepositoryPort.findOverdue(limit);
        int expired = 0;
        for (Notification n : overdue) {
            // 조회와 UPDATE 사이에 다른 노드가 선점했을 수 있다. 상태·기한을 CAS로 다시 본다.
            if (notificationRepositoryPort.tryExpire(n.getId())) {
                notificationLogRepositoryPort.save(NotificationLog.of(n.getId(), n.getStatus(),
                        NotificationStatus.FAILED, DispatchFailureReason.EXPIRED.name()));
                expired++;
            }
        }
        return expired;
    }

    /**
     * TX-B. 외부 발송 결과를 반영한다.
     *
     * <ul>
     *   <li>조건부 UPDATE {@code WHERE status='PROCESSING'}로 상태·횟수·백오프·사유를 기록</li>
     *   <li>{@code dispatch_history}는 반영 성공 여부와 무관하게 남긴다 — "시도가 있었다"는 사실이기 때문</li>
     *   <li>0행이면 Stuck 복구 또는 새 소유자가 먼저 움직인 것. 상태는 건드리지 않고 {@value #LATE_RESULT_IGNORED}로 기록</li>
     * </ul>
     *
     * 0행 + 발송 성공 조합은 중복 발송으로 이어질 수 있다. 이를 막는 불변식은
     * <b>"어댑터의 발송 타임아웃 &lt; Stuck 임계(10분)"</b>이다. (svc-dispatch 명세 참조)
     *
     * @param notification  도메인 전이(markSent/markRetrying/markFailed)가 끝난 detached 엔티티
     * @param attemptNumber 이번 시도 회차 (전이 전에 캡처한 값)
     * @param failureCode   실패 코드. 성공이면 null
     */
    @Transactional
    public void finish(Notification notification, int attemptNumber, String failureCode) {
        Long id = notification.getId();
        boolean applied = notificationRepositoryPort.tryFinishProcessing(notification);

        dispatchHistoryRepositoryPort.save(failureCode == null
                ? DispatchHistory.success(id, attemptNumber)
                : DispatchHistory.failure(id, attemptNumber, failureCode));

        if (applied) {
            notificationLogRepositoryPort.save(
                    NotificationLog.of(id, NotificationStatus.PROCESSING, notification.getStatus(), failureCode));
            return;
        }

        // 여기 도달했다면 Stuck 복구가 먼저 움직였다. 외부 발송이 임계(10분)보다 오래 걸렸다는 신호다.
        // "PROCESSING → (시도 결과)로 바꾸려 했으나 무시됨"을 남긴다. to_status는 NOT NULL이라 시도 결과를 넣고 reason으로 구분한다.
        notificationLogRepositoryPort.save(
                NotificationLog.of(id, NotificationStatus.PROCESSING, notification.getStatus(), LATE_RESULT_IGNORED));
        log.warn("[늦은 결과] 결과 반영 0행. 이미 PROCESSING이 아님(Stuck 복구 추정). id={}, 시도결과={}, code={}",
                id, notification.getStatus(), failureCode);
    }
}
