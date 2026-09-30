package com.notification.domain;
// PRD: F2-1, F2-2, O-1, O-3, O-4 → docs/prd/F2.md

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 알림 도메인 엔티티.
 *
 * 알림의 전체 생명주기를 상태 머신으로 관리한다.
 *
 * 상태 전이: PENDING → PROCESSING → SENT
 *                               → RETRYING → SENT (재시도 성공)
 *                                          → FAILED (예산 소진·기한 만료·영구 오류)
 *
 * <p>"몇 번까지, 언제까지"는 이 엔티티가 정하지 않는다. {@code RetryDecisionPolicy}가 등록 당시
 * 정책 버전으로 판정하고, 여기서는 그 결정을 기록만 한다. 타입마다 예산과 기한이 다르기 때문이다.
 *
 * 인덱스 전략:
 * idx_retry_fetch - 스케줄러 재처리 대상 조회 (status, next_retry_at, scheduled_at)
 * idx_user_notification - 사용자별 알림 목록 페이징 조회 (receiver_id, is_read, created_at)
 */
@Entity
@Table(name = "notification",
        indexes = {
                @Index(name = "idx_retry_fetch", columnList = "status, next_retry_at, scheduled_at"),
                @Index(name = "idx_processing_lease", columnList = "status, lease_until"),
                // 만료 정리는 대기 상태 + 기한만 본다. 없으면 30초마다 전체 스캔이 된다.
                @Index(name = "idx_expiry_cleanup", columnList = "status, expires_at"),
                @Index(name = "idx_user_notification", columnList = "receiver_id, is_read, created_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    /**
     * Stuck 복구가 DB에 적용할 다음 상태. 계산은 순수하게, 저장은 CAS 성공 뒤에만 한다.
     *
     * @param retryCount 실패 예산 값. TYPE_V1에서는 회수로 증가하지 않으므로 현재 값 그대로다
     */
    public record StuckRecovery(NotificationStatus status, int retryCount, int cycleRecoveryCount,
                                LocalDateTime nextRetryAt, String failureReason) {

        /**
         * 정책 결정을 그대로 옮긴다. LEGACY_V0는 회수를 실패 1회로 세고(예전 의미),
         * TYPE_V1은 실패 예산을 그대로 둔 채 회수 횟수만 올린다. (재시도 정책 §5.6)
         */
        public static StuckRecovery from(Notification n, com.notification.application.service.RetryDecisionPolicy.Decision d) {
            boolean legacy = n.policyVersion() == PolicyVersion.LEGACY_V0;
            return new StuckRecovery(
                    d.status(),
                    legacy ? n.getRetryCount() + 1 : n.getRetryCount(),
                    n.getCycleRecoveryCount() + 1,
                    d.nextEligibleAt(),
                    d.reason() != null ? d.reason() : "PROCESSING_STUCK");
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 중복 발송 방지를 위한 멱등성 키.
     * SHA-256(notificationType|eventId|receiverId|channel) 으로 생성된다.
     */
    @Column(nullable = false, unique = true)
    private String idempotencyKey;

    /** 알림 수신자 ID (users 테이블 논리 참조). */
    @Column(nullable = false)
    private Long receiverId;

    /**
     * 채널별 발송 대상 주소.
     * EMAIL이면 이메일 주소, IN_APP이면 null.
     * 추후 SMS 채널 추가 시 전화번호를 담는 범용 컬럼.
     */
    private String channelTarget;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private NotificationType notificationType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private NotificationChannel channel;

    /** 외부 비즈니스 이벤트 식별자. 멱등성 키 생성 재료로 사용된다. */
    @Column(nullable = false)
    private String eventId;

    /** 알림과 연관된 도메인 객체 ID (예: 수강신청 ID, 결제 ID). */
    private Long referenceId;

    /** 알림과 연관된 도메인 타입 (예: ENROLLMENT, PAYMENT). */
    private String referenceType;

    /** 알림 본문에 채울 동적 데이터 (JSON 문자열). */
    @Column(columnDefinition = "TEXT")
    private String contentData;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationStatus status;

    /** 이번 cycle에서 관측한 재시도 가능 실패 횟수. 종료 시점은 정책이 타입별로 정한다. */
    private int retryCount;

    /** UTC 재시도 예정 시각. 운영 경로는 DB 시각 + 단계형 백오프/지터 정책으로 계산한다. */
    private LocalDateTime nextRetryAt;

    /**
     * 최종 실패 사유. ChannelFailureCode 이름을 저장한다.
     * 외부 서비스 오류 메시지 직접 저장 금지 (보안).
     */
    @Column(length = 100)
    private String failureReason;

    /** UTC 예약 발송 시각. null이면 즉시 처리 대상. 업무가 지정한 원래 값이며 재시도로 덮어쓰지 않는다. */
    private LocalDateTime scheduledAt;

    // ── P1: 정책 버전과 시간창 ──────────────────────────────────────
    // 전부 nullable로 추가한다. 기존 행은 NULL인 채로 예전 의미를 그대로 유지하고,
    // 신규 행만 TYPE_V1로 채운다. 전 행 NOT NULL 제약을 성급히 걸지 않는다. (재시도 정책 §5.7)

    /** 이 알림에 적용할 정책 버전. NULL(기존 행)은 {@link PolicyVersion#LEGACY_V0}로 읽는다. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private PolicyVersion policyVersion;

    /**
     * 지금 발송 후보가 될 수 있는 가장 이른 시각 = {@code max(예약 조건, 재시도 조건)}.
     * scheduledAt(업무 예약)과 nextRetryAt(재시도 예약)을 하나로 합친 조회 기준이다.
     */
    private LocalDateTime eligibleAt;

    /**
     * 발송을 <b>시작</b>해도 되는 기한. 이미 시작한 호출을 취소하지는 못한다.
     * NULL이면 기한 없음(LEGACY_V0). 재시도나 재등록으로 연장하지 않는다.
     */
    private LocalDateTime expiresAt;

    // 아래 세 카운터는 primitive가 아니라 Integer다. primitive면 Hibernate가 NOT NULL·DEFAULT 없는
    // 컬럼을 만들어, 이 컬럼을 모르는 기존/외부 INSERT가 strict 모드에서 전부 실패한다.
    // "nullable로 추가하고 읽을 때 기본값을 준다"가 신구 writer 공존의 조건이다. (재시도 정책 §5.7)

    /**
     * 만료 lease 회수 횟수. <b>발송 실패와 별개로</b> 센다 — 회수는 "실패를 관측한 것"이 아니라
     * "소유자가 사라진 것"이라, 실패 예산에 섞으면 고장 난 worker가 남의 예산을 태운다.
     */
    @Getter(AccessLevel.NONE)
    private Integer cycleRecoveryCount;

    /** 수동 재시도로 예산을 초기화한 횟수. 최초 1이며 누적 이력은 보존한다. */
    @Getter(AccessLevel.NONE)
    private Integer attemptCycle;

    /** 외부 호출을 <b>시작하려 한</b> 누적 횟수. 기록과 실제 호출은 원자적이지 않아 정확한 호출 수가 아니다. */
    @Getter(AccessLevel.NONE)
    private Integer attemptIntentCount;

    /**
     * 이번 PROCESSING 소유자를 식별하는 lease token.
     * Stuck 복구 뒤 이전 워커가 늦게 finish해 새 소유자의 결과를 덮는 ABA를 막는다.
     */
    @Column(length = 36)
    private String processingToken;

    private LocalDateTime claimedAt;
    private LocalDateTime leaseUntil;

    private boolean isRead;
    private LocalDateTime readAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private Notification(Long receiverId, String channelTarget, NotificationType notificationType,
                         NotificationChannel channel, String eventId, Long referenceId,
                         String referenceType, String contentData, String idempotencyKey,
                         LocalDateTime scheduledAt, PolicyVersion policyVersion,
                         LocalDateTime eligibleAt, LocalDateTime expiresAt) {
        this.policyVersion = policyVersion;
        this.eligibleAt = eligibleAt;
        this.expiresAt = expiresAt;
        this.attemptCycle = 1;
        this.receiverId = receiverId;
        this.channelTarget = channelTarget;
        this.notificationType = notificationType;
        this.channel = channel;
        this.eventId = eventId;
        this.referenceId = referenceId;
        this.referenceType = referenceType;
        this.contentData = contentData;
        this.idempotencyKey = idempotencyKey;
        this.scheduledAt = scheduledAt;
        this.status = NotificationStatus.PENDING;
        this.retryCount = 0;
        this.isRead = false;
    }

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
        this.updatedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    /**
     * 발송 성공 시 호출. PROCESSING → SENT.
     * 재시도 예약 시각과 마지막 실패 사유를 지운다. SENT에 실패 사유가 남아 있으면 상태가 거짓말을 한다.
     * 회차별 실패 사유는 dispatch_history에 append-only로 보존된다.
     */
    public void markSent() {
        this.status = NotificationStatus.SENT;
        this.nextRetryAt = null;
        this.failureReason = null;
    }

    /**
     * NonRetryable 발송 실패 시 호출. 재시도 없이 즉시 FAILED 처리한다.
     *
     * @param failureCode ChannelFailureCode 이름 (외부 오류 메시지 금지)
     */
    public void markFailed(String failureCode) {
        this.status = NotificationStatus.FAILED;
        this.failureReason = failureCode;
        this.nextRetryAt = null;
    }

    /** 수신자가 알림을 읽었을 때 호출. */
    public void markRead() {
        this.isRead = true;
        this.readAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    /**
     * 운영자 수동 재시도 시 호출. retryCount를 초기화하고 PENDING으로 되돌린다.
     * FAILED 상태에서만 의미 있는 호출이다.
     */
    public void resetForManualRetry(LocalDateTime dueAt) {
        if (dueAt == null) {
            throw new IllegalArgumentException("수동 재시도에는 DB 기준 재시도 시각이 필요합니다.");
        }
        this.retryCount = 0;
        this.status = NotificationStatus.PENDING;
        this.nextRetryAt = dueAt;
    }

    /** 기존 행(NULL)은 LEGACY_V0로 읽는다. 구버전 데이터를 읽지 못해 예외를 내지 않기 위한 호환 규칙이다. */
    public PolicyVersion policyVersion() {
        return policyVersion == null ? PolicyVersion.LEGACY_V0 : policyVersion;
    }

    /** 컬럼 추가 전 행은 NULL이므로 최초 cycle(1)로 읽는다. */
    public int attemptCycle() {
        return attemptCycle == null ? 1 : attemptCycle;
    }

    /** 컬럼 추가 전 행은 NULL이므로 0회로 읽는다. */
    public int getCycleRecoveryCount() {
        return cycleRecoveryCount == null ? 0 : cycleRecoveryCount;
    }

    /** 컬럼 추가 전 행은 NULL이므로 0회로 읽는다. */
    public int getAttemptIntentCount() {
        return attemptIntentCount == null ? 0 : attemptIntentCount;
    }

    /** 이번 cycle에서 관측한 재시도 가능 실패 횟수. {@code retryCount}가 원래부터 이 의미였다. */
    public int cycleRetryFailureCount() {
        return retryCount;
    }

    /**
     * 정책이 내린 결정을 그대로 적용한다. <b>무엇을 할지는 정책이 정하고, 이 메서드는 기록만 한다.</b>
     * 상태 머신이 타입별 지연 목록이나 기한을 알 필요가 없도록 분리했다.
     *
     * @param channelFailureCode 제공자 오류 코드. 정책 종료 사유가 있으면 그쪽이 우선 기록된다
     */
    public void applyRetryableFailure(NotificationStatus status, String policyReason,
                                      String channelFailureCode, LocalDateTime nextEligibleAt) {
        if (status == NotificationStatus.RETRYING && nextEligibleAt == null) {
            throw new IllegalArgumentException("재시도할 작업에는 다음 예약 시각이 필요합니다.");
        }
        this.retryCount++;
        this.status = status;
        this.failureReason = policyReason != null ? policyReason : channelFailureCode;
        this.nextRetryAt = status == NotificationStatus.RETRYING ? nextEligibleAt : null;
        this.eligibleAt = status == NotificationStatus.RETRYING ? nextEligibleAt : this.eligibleAt;
    }

    /**
     * lease 회수 결정을 적용한다. <b>실패 예산({@code retryCount})은 건드리지 않고</b> 회수 횟수만 센다.
     * 회수는 "발송이 실패했다"가 아니라 "소유자가 사라졌다"이므로, 섞으면 고장 난 worker가 남의 예산을 태운다.
     */
    public void applyLeaseRecovery(NotificationStatus status, String policyReason, LocalDateTime nextEligibleAt) {
        if (status == NotificationStatus.RETRYING && nextEligibleAt == null) {
            throw new IllegalArgumentException("재시도할 복구 작업에는 다음 예약 시각이 필요합니다.");
        }
        this.cycleRecoveryCount = getCycleRecoveryCount() + 1;
        this.status = status;
        this.failureReason = policyReason != null ? policyReason : "PROCESSING_STUCK";
        this.nextRetryAt = status == NotificationStatus.RETRYING ? nextEligibleAt : null;
        this.eligibleAt = status == NotificationStatus.RETRYING ? nextEligibleAt : this.eligibleAt;
    }

    /**
     * 외부 호출을 시작하려 한 사실을 남긴다. 실제 호출 성공 여부와 무관하다 —
     * 기록 직후 프로세스가 죽으면 호출됐는지 알 수 없고, 그 불확실성 자체가 이 값의 의미다.
     */
    public void recordAttemptIntent() {
        this.attemptIntentCount = getAttemptIntentCount() + 1;
    }

    /** 최종 실패 여부 확인. */
    public boolean isFailed() {
        return this.status == NotificationStatus.FAILED;
    }

    /** 인앱 채널 여부 확인. 인앱은 channelTarget이 null이고 외부 발송이 없다. */
    public boolean isInApp() {
        return this.channel == NotificationChannel.IN_APP;
    }

}
