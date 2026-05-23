package com.notification.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 알림 상태 전이 이력.
 *
 * 알림이 어떤 경로를 거쳐 현재 상태에 도달했는지 추적한다.
 * 최초 생성(PENDING) 시 fromStatus = NULL.
 */
@Entity
@Table(name = "notification_log",
        indexes = @Index(name = "idx_log_notification", columnList = "notification_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long notificationId;

    /** 최초 생성 시 NULL. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private NotificationStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationStatus toStatus;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private NotificationLog(Long notificationId, NotificationStatus fromStatus,
                            NotificationStatus toStatus, String reason) {
        this.notificationId = notificationId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.reason = reason;
        this.createdAt = LocalDateTime.now();
    }

    public static NotificationLog of(Long notificationId,
                                     NotificationStatus from,
                                     NotificationStatus to,
                                     String reason) {
        return NotificationLog.builder()
                .notificationId(notificationId)
                .fromStatus(from)
                .toStatus(to)
                .reason(reason)
                .build();
    }
}
