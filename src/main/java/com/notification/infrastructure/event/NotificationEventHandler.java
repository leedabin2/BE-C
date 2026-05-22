package com.notification.infrastructure.event;

import com.notification.application.event.NotificationCreatedEvent;
import com.notification.domain.Notification;
import com.notification.application.port.out.NotificationRepositoryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 알림 생성 이벤트를 수신해 실제 발송을 트리거하는 핸들러.
 *
 * <p>Transactional Outbox Pattern의 발송 트리거 역할을 한다.</p>
 * <ul>
 *   <li>{@code @TransactionalEventListener(AFTER_COMMIT)}: DB 커밋이 완료된 후에만 실행된다.
 *       커밋 전에 서버가 재시작되면 이 핸들러는 실행되지 않으며,
 *       PENDING 상태로 남은 알림은 스케줄러가 재처리한다.</li>
 *   <li>{@code @Async}: 별도의 스레드풀(notificationExecutor)에서 실행되어
 *       발송 지연이 HTTP 응답 시간에 영향을 주지 않는다.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventHandler {

    private final NotificationRepositoryPort notificationRepository;

    /**
     * 알림 생성 이벤트를 수신한다.
     *
     * <p>트랜잭션 커밋 이후 비동기로 실행되므로, 이 메서드의 예외가
     * 발송 요청 API의 트랜잭션에 영향을 주지 않는다.
     * 발송 실패 시 스케줄러가 PENDING/RETRYING 상태를 감지해 재처리한다.</p>
     *
     * @param event 알림 생성 이벤트 (notificationId 포함)
     */
    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(NotificationCreatedEvent event) {
        notificationRepository.findById(event.notificationId())
                .ifPresentOrElse(
                        this::dispatch,
                        () -> log.warn("알림을 찾을 수 없습니다. id={}", event.notificationId())
                );
    }

    /**
     * 채널에 따라 실제 발송을 수행한다.
     *
     * @param notification 발송 대상 알림 엔티티
     */
    private void dispatch(Notification notification) {
        log.info("알림 발송 처리 시작. id={}, channel={}", notification.getId(), notification.getChannel());
        // TODO: 채널별 실제 발송 어댑터 연결 (email sender, in-app push 등)
    }
}
