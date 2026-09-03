package com.notification.adapter.in.messaging;
// PRD: F4-2 → docs/prd/F4.md

import com.notification.application.port.in.command.RegisterNotificationCommand;
import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationType;

import java.time.LocalDateTime;

/**
 * 브로커에서 꺼낸 메시지. Kafka·SQS 무엇이 오든 이 타입으로 수렴한다.
 * 그래서 여기엔 브로커 라이브러리를 import 하지 않는다.
 *
 * eventId는 생산자가 채운다. 재전달된 메시지가 같은 eventId를 실어야 멱등성 키가 같아진다.
 */
public record NotificationMessage(
        Long receiverId,
        NotificationType notificationType,
        NotificationChannel channel,
        String channelTarget,
        String eventId,
        Long referenceId,
        String referenceType,
        String contentData,
        LocalDateTime scheduledAt
) {

    public RegisterNotificationCommand toCommand() {
        return new RegisterNotificationCommand(
                receiverId, notificationType, channel, channelTarget,
                eventId, referenceId, referenceType, contentData, scheduledAt);
    }
}
