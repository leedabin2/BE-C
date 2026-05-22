package com.notification.application.service;

import com.notification.application.event.NotificationCreatedEvent;
import com.notification.application.port.in.RegisterNotificationUseCase;
import com.notification.application.port.in.command.RegisterNotificationCommand;
import com.notification.application.port.in.result.RegisterNotificationResult;
import com.notification.application.port.out.NotificationEventPublisher;
import com.notification.common.exception.ErrorCode;
import com.notification.common.exception.NotificationException;
import com.notification.domain.Notification;
import com.notification.domain.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
@RequiredArgsConstructor
public class NotificationService implements RegisterNotificationUseCase {

    private final NotificationRepository notificationRepository;
    private final NotificationEventPublisher eventPublisher;

    @Override
    @Transactional
    public RegisterNotificationResult register(RegisterNotificationCommand command) {
        String idempotencyKey = buildIdempotencyKey(command);

        if (notificationRepository.existsByIdempotencyKey(idempotencyKey)) {
            throw new NotificationException(ErrorCode.DUPLICATE_NOTIFICATION);
        }

        Notification notification = Notification.builder()
                .receiverId(command.receiverId())
                .channelTarget(command.channelTarget())
                .notificationType(command.notificationType())
                .channel(command.channel())
                .eventId(command.eventId())
                .referenceId(command.referenceId())
                .referenceType(command.referenceType())
                .contentData(command.contentData())
                .idempotencyKey(idempotencyKey)
                .scheduledAt(command.scheduledAt())
                .build();

        Notification saved = notificationRepository.save(notification);

        eventPublisher.publish(new NotificationCreatedEvent(saved.getId()));

        return RegisterNotificationResult.from(saved);
    }

    private String buildIdempotencyKey(RegisterNotificationCommand command) {
        String raw = command.notificationType().name()
                + "|" + command.eventId()
                + "|" + command.receiverId()
                + "|" + command.channel().name();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }
}
