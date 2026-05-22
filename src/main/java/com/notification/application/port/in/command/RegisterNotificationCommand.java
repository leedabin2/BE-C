package com.notification.application.port.in.command;

import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationType;

import java.time.LocalDateTime;

public record RegisterNotificationCommand(
        Long receiverId,
        NotificationType notificationType,
        NotificationChannel channel,
        String channelTarget,
        String eventId,
        Long referenceId,
        String referenceType,
        String contentData,
        LocalDateTime scheduledAt
) {}
