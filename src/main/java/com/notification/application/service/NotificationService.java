package com.notification.application.service;

import com.notification.application.event.NotificationCreatedEvent;
import com.notification.application.port.in.RegisterNotificationUseCase;
import com.notification.application.port.in.command.RegisterNotificationCommand;
import com.notification.application.port.in.result.RegisterNotificationResult;
import com.notification.application.port.out.NotificationEventPublisherPort;
import com.notification.application.port.out.NotificationRepositoryPort;
import com.notification.common.exception.ErrorCode;
import com.notification.common.exception.NotificationException;
import com.notification.domain.Notification;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 알림 발송 요청을 처리하는 애플리케이션 서비스.
 *
 * Transactional Outbox Pattern을 적용한다.
 * 알림 레코드를 DB에 저장(PENDING)하고, 트랜잭션 커밋 후 발송 이벤트를 발행한다.
 * 커밋 전에 서버가 재시작되면 알림 레코드도 함께 롤백되므로 데이터 유실이 없다.
 */
@Service
@RequiredArgsConstructor
public class NotificationService implements RegisterNotificationUseCase {

    private final NotificationRepositoryPort notificationRepositoryPort;
    private final NotificationEventPublisherPort eventPublisherPort;

    /**
     * 알림 발송을 요청한다.
     *
     * 멱등성 키를 생성해 중복 요청 여부를 확인한다.
     * PENDING 상태로 알림을 저장하고(트랜잭션 안),
     * 커밋 후 {@link NotificationCreatedEvent}를 발행해 비동기 발송을 트리거한다.
     *
     * @param command 발송 요청 커맨드
     * @return 저장된 알림의 결과 정보
     * @throws NotificationException 동일 멱등성 키 요청이 이미 존재하는 경우 (DUPLICATE_NOTIFICATION)
     */
    @Override
    @Transactional
    public RegisterNotificationResult register(RegisterNotificationCommand command) {
        String idempotencyKey = buildIdempotencyKey(command);

        if (notificationRepositoryPort.existsByIdempotencyKey(idempotencyKey)) {
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

        Notification saved = notificationRepositoryPort.save(notification);

        eventPublisherPort.publish(new NotificationCreatedEvent(saved.getId()));

        return RegisterNotificationResult.from(saved);
    }

    /**
     * SHA-256 기반 멱등성 키를 생성한다.
     *
     * 키 재료: {@code notificationType|eventId|receiverId|channel}
     * 동일한 비즈니스 이벤트에 대해 항상 같은 키가 생성되므로,
     * 네트워크 재시도나 중복 호출로 인한 중복 발송을 방지한다.
     */
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
