package com.notification.application.service;

import com.notification.application.event.NotificationCreatedEvent;
import com.notification.application.port.in.RegisterNotificationUseCase;
import com.notification.application.port.in.command.RegisterNotificationCommand;
import com.notification.application.port.in.result.RegisterNotificationResult;
import com.notification.application.port.out.NotificationEventPublisherPort;
import com.notification.application.port.out.NotificationRepositoryPort;
import com.notification.application.port.out.NotificationLogRepositoryPort;
import com.notification.domain.Notification;
import com.notification.domain.NotificationLog;
import com.notification.domain.NotificationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

/**
 * 알림 발송 요청을 처리하는 애플리케이션 서비스.
 *
 * Transactional Outbox Pattern을 적용한다.
 * 알림 레코드를 DB에 저장(PENDING)하고, 트랜잭션 커밋 후 발송 이벤트를 발행한다.
 * 커밋 전에 서버가 재시작되면 알림 레코드도 함께 롤백되므로 데이터 유실이 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService implements RegisterNotificationUseCase {

    private final NotificationRepositoryPort notificationRepositoryPort;
    private final NotificationEventPublisherPort eventPublisherPort;
    private final NotificationLogRepositoryPort notificationLogRepositoryPort;

    // self-injection: DataIntegrityViolationException catch 후 새 트랜잭션으로 조회하기 위해 프록시 경유
    // @Lazy로 순환 참조 해결 (자기 자신을 주입할 때 발생하는 BeanCurrentlyInCreationException 방지)
    @Lazy
    @Autowired
    private NotificationService self;

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

        Optional<Notification> existing = notificationRepositoryPort.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            log.warn("중복 요청. 기존 결과 반환. idempotencyKey={}", idempotencyKey);
            return RegisterNotificationResult.from(existing.get());
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

        Notification saved;
        try {
            saved = notificationRepositoryPort.save(notification);
        } catch (DataIntegrityViolationException e) {
            log.warn("동시 중복 등록 감지. 기존 알림 반환. idempotencyKey={}", idempotencyKey);
            return self.findExistingByCommand(command);
        }

        notificationLogRepositoryPort.save(
                NotificationLog.of(saved.getId(), null, NotificationStatus.PENDING, "CREATED"));

        eventPublisherPort.publish(new NotificationCreatedEvent(saved.getId(), saved.getScheduledAt()));

        return RegisterNotificationResult.from(saved);
    }

    /**
     * 동시 중복 등록 경합 발생 후 기존 알림을 조회한다.
     *
     * DataIntegrityViolationException 이후 별도 트랜잭션에서 호출돼
     * 롤백된 트랜잭션의 영향 없이 기존 레코드를 읽는다.
     */
    @Override
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public RegisterNotificationResult findExistingByCommand(RegisterNotificationCommand command) {
        String idempotencyKey = buildIdempotencyKey(command);
        return notificationRepositoryPort.findByIdempotencyKey(idempotencyKey)
                .map(RegisterNotificationResult::from)
                .orElseThrow(() -> new IllegalStateException(
                        "경합 후 기존 알림 조회 실패. idempotencyKey=" + idempotencyKey));
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
