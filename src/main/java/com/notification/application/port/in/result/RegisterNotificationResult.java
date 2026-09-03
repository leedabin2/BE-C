package com.notification.application.port.in.result;
// PRD: F1-1 → docs/prd/F1.md

import com.notification.domain.Notification;
import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationStatus;
import com.notification.domain.NotificationType;

import java.time.LocalDateTime;

public record RegisterNotificationResult(
        Long id,
        Long receiverId,
        NotificationType notificationType,
        NotificationChannel channel,
        NotificationStatus status,
        String idempotencyKey,
        LocalDateTime createdAt
) {
    public static RegisterNotificationResult from(Notification notification) {
        return new RegisterNotificationResult(
                notification.getId(),
                notification.getReceiverId(),
                notification.getNotificationType(),
                notification.getChannel(),
                notification.getStatus(),
                notification.getIdempotencyKey(),
                notification.getCreatedAt()
        );
    }
}
