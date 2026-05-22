package com.notification.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 알림 도메인 엔티티.
 *
 * <p>알림의 전체 생명주기를 상태 머신으로 관리한다.</p>
 * <pre>
 * PENDING → PROCESSING → SENT
 *                      ↘ RETRYING → (재시도) → SENT
 *                                 ↘ FAILED (MAX_RETRY_COUNT 초과)
 * </pre>
 *
 * <p>인덱스 전략:</p>
 * <ul>
 *   <li>idx_retry_fetch: 스케줄러가 재처리 대상을 조회할 때 사용 (status, next_retry_at, scheduled_at)</li>
 *   <li>idx_user_notification: 사용자별 알림 목록 페이징 조회에 사용 (receiver_id, is_read, created_at)</li>
 * </ul>
 */
@Entity
@Table(name = "notification",
        indexes = {
                @Index(name = "idx_retry_fetch", columnList = "status, next_retry_at, scheduled_at"),
                @Index(name = "idx_user_notification", columnList = "receiver_id, is_read, created_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    private static final int MAX_RETRY_COUNT = 3;

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

    /** 현재까지 재시도한 횟수. MAX_RETRY_COUNT(3) 도달 시 FAILED로 전이. */
    private int retryCount;

    /** 다음 재시도 예정 시각. 지수 백오프(1분 → 5분 → 30분) 적용. */
    private LocalDateTime nextRetryAt;

    /** 예약 발송 시각. null이면 즉시 처리 대상. */
    private LocalDateTime scheduledAt;

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
                         LocalDateTime scheduledAt) {
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
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** 스케줄러가 발송을 시작할 때 호출. PENDING/RETRYING → PROCESSING. */
    public void startProcessing() {
        this.status = NotificationStatus.PROCESSING;
    }

    /** 발송 성공 시 호출. PROCESSING → SENT. */
    public void markSent() {
        this.status = NotificationStatus.SENT;
    }

    /**
     * 발송 실패 시 호출. 재시도 횟수를 증가시키고 상태를 전이한다.
     * MAX_RETRY_COUNT 미만이면 RETRYING + 지수 백오프 시각 계산,
     * 초과하면 FAILED로 최종 처리.
     */
    public void markRetrying() {
        this.retryCount++;
        this.status = retryCount >= MAX_RETRY_COUNT
                ? NotificationStatus.FAILED
                : NotificationStatus.RETRYING;
        this.nextRetryAt = retryCount < MAX_RETRY_COUNT
                ? calculateNextRetryAt()
                : null;
    }

    /** 수신자가 알림을 읽었을 때 호출. */
    public void markRead() {
        this.isRead = true;
        this.readAt = LocalDateTime.now();
    }

    /**
     * 운영자 수동 재시도 시 호출. retryCount를 초기화하고 PENDING으로 되돌린다.
     * FAILED 상태에서만 의미 있는 호출이다.
     */
    public void resetForManualRetry() {
        this.retryCount = 0;
        this.status = NotificationStatus.PENDING;
        this.nextRetryAt = LocalDateTime.now();
    }

    /** 최종 실패 여부 확인. */
    public boolean isFailed() {
        return this.status == NotificationStatus.FAILED;
    }

    /** 인앱 채널 여부 확인. 인앱은 channelTarget이 null이고 외부 발송이 없다. */
    public boolean isInApp() {
        return this.channel == NotificationChannel.IN_APP;
    }

    /** 재시도 가능 여부. MAX_RETRY_COUNT 미만인 경우만 true. */
    public boolean canRetry() {
        return this.retryCount < MAX_RETRY_COUNT;
    }

    /**
     * 지수 백오프 방식으로 다음 재시도 시각을 계산한다.
     * 1회: 1분 후, 2회: 5분 후, 3회 이상: 30분 후.
     */
    private LocalDateTime calculateNextRetryAt() {
        return switch (this.retryCount) {
            case 1 -> LocalDateTime.now().plusMinutes(1);
            case 2 -> LocalDateTime.now().plusMinutes(5);
            default -> LocalDateTime.now().plusMinutes(30);
        };
    }
}
