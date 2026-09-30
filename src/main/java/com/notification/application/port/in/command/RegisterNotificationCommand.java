package com.notification.application.port.in.command;
// PRD: F1-1, F4-2 (진입점 수렴 지점) → docs/prd/F4.md

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
        LocalDateTime scheduledAt,
        /** 생산자가 지정한 절대 발송 기한. 강의 시작 안내처럼 업무가 기한을 아는 경우에만 의미가 있다. */
        LocalDateTime expiresAt
) {}
