package com.notification.application.port.in.result;

import com.notification.domain.Notification;
import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationStatus;
import com.notification.domain.NotificationType;

import java.time.LocalDateTime;

public record NotificationDetailResult(
        Long id,
        Long receiverId,
        NotificationType notificationType,
        NotificationChannel channel,
        NotificationStatus status,
        String idempotencyKey,
        String failureReason,
        int retryCount,
        boolean isRead,
        LocalDateTime scheduledAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static NotificationDetailResult from(Notification n) {
        return new NotificationDetailResult(
                n.getId(),
                n.getReceiverId(),
                n.getNotificationType(),
                n.getChannel(),
                n.getStatus(),
                n.getIdempotencyKey(),
                n.getFailureReason(),
                n.getRetryCount(),
                n.isRead(),
                n.getScheduledAt(),
                n.getCreatedAt(),
                n.getUpdatedAt()
        );
    }
}
