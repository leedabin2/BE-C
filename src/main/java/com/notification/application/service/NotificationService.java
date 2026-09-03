package com.notification.application.service;
// PRD: F1-1, F3-1, F3-2, F4-1 → docs/prd/F1.md, docs/prd/F3.md

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
 * 등록 담당. 인그레스(HTTP·메시징)가 무엇이든 여기로 수렴한다.
 *
 * 하는 일은 "멱등성 키 검사 → PENDING 저장 → 커밋"이 전부다. 외부 발송은 하지 않는다.
 * PENDING이 커밋되는 순간부터 유실이 없다 — 이후 어디서 죽어도 스케줄러가 회수한다.
 *
 * ⚠️ 이것은 Outbox 패턴이 아니다. Outbox는 "DB 저장 + 브로커 발행"의 원자성을 푸는 패턴이고,
 * 우리는 발행할 브로커가 없다. 정확히는 <b>DB를 작업 큐로 쓰는 것</b>이다. → docs/design/09-broker-migration.md §0.5
 *
 * 실행 스레드: {@code http-nio-*} (전환 후엔 브로커 리스너 스레드). 트랜잭션 열림.
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
     * 중복 방지 2단: 1차는 조회(효율), 2차는 unique 제약(정합성 보증).
     * 1차만으로는 안 된다 — read-check-write는 원자성이 없어 동시 요청이 전부 통과한다.
     *
     * 재요청에도 200 + 같은 id를 준다. 호출자에겐 "이미 접수됨"이 실패가 아니기 때문. (F1-1·F3-2)
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
            // save()가 아닌 이유: save()는 flush를 트랜잭션 끝까지 미뤄 제약 위반이 catch 밖에서 터진다
            saved = notificationRepositoryPort.saveAndFlush(notification);
        } catch (DataIntegrityViolationException e) {
            log.warn("동시 중복 등록 감지. 기존 알림 반환. idempotencyKey={}", idempotencyKey);
            // self로 부르는 이유: @Transactional은 프록시에서만 걸린다. this.메서드()는 프록시를 안 거친다
            return self.findExistingByCommand(command);
        }

        notificationLogRepositoryPort.save(
                NotificationLog.of(saved.getId(), null, NotificationStatus.PENDING, "CREATED"));

        // 지금은 큐에 보관만 된다. 커밋 후 AFTER_COMMIT에서 발화한다. 지연 단축 장치일 뿐, 지워도 정합성은 안 깨진다
        eventPublisherPort.publish(new NotificationCreatedEvent(saved.getId(), saved.getScheduledAt()));

        return RegisterNotificationResult.from(saved);
    }

    /**
     * 경합에서 진 쪽이 기존 알림을 읽는다.
     *
     * REQUIRES_NEW인 이유: 제약 위반이 난 트랜잭션은 rollback-only로 찍혀 더 쓸 수 없다.
     * 새 트랜잭션을 열어야 읽을 수 있다. 보안과는 무관하고, 순전히 트랜잭션 상태 문제다.
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
     * SHA-256(notificationType|eventId|receiverId|channel).
     *
     * eventId는 생산자가 채운다. 재전달분이 같은 eventId를 실어야 같은 키가 나온다.
     * channel이 재료에 있는 이유: 같은 결제 건에 EMAIL과 IN_APP을 둘 다 보낼 수 있어야 한다.
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
