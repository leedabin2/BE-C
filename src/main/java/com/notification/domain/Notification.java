package com.notification.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

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

    @Column(nullable = false, unique = true)
    private String idempotencyKey;

    @Column(nullable = false)
    private Long receiverId;

    private String channelTarget;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private NotificationType notificationType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private NotificationChannel channel;

    @Column(nullable = false)
    private String eventId;

    private Long referenceId;
    private String referenceType;

    @Column(columnDefinition = "TEXT")
    private String contentData;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationStatus status;

    private int retryCount;
    private LocalDateTime nextRetryAt;
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

    public void startProcessing() {
        this.status = NotificationStatus.PROCESSING;
    }

    public void markSent() {
        this.status = NotificationStatus.SENT;
    }

    public void markRetrying() {
        this.retryCount++;
        this.status = retryCount >= MAX_RETRY_COUNT
                ? NotificationStatus.FAILED
                : NotificationStatus.RETRYING;
        this.nextRetryAt = retryCount < MAX_RETRY_COUNT
                ? calculateNextRetryAt()
                : null;
    }

    public void markRead() {
        this.isRead = true;
        this.readAt = LocalDateTime.now();
    }

    public void resetForManualRetry() {
        this.retryCount = 0;
        this.status = NotificationStatus.PENDING;
        this.nextRetryAt = LocalDateTime.now();
    }

    public boolean isFailed() {
        return this.status == NotificationStatus.FAILED;
    }

    public boolean isInApp() {
        return this.channel == NotificationChannel.IN_APP;
    }

    public boolean canRetry() {
        return this.retryCount < MAX_RETRY_COUNT;
    }

    private LocalDateTime calculateNextRetryAt() {
        return switch (this.retryCount) {
            case 1 -> LocalDateTime.now().plusMinutes(1);
            case 2 -> LocalDateTime.now().plusMinutes(5);
            default -> LocalDateTime.now().plusMinutes(30);
        };
    }
}
