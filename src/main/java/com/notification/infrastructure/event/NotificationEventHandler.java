package com.notification.infrastructure.event;

import com.notification.application.event.NotificationCreatedEvent;
import com.notification.application.service.NotificationDispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;

/**
 * 알림 생성 이벤트를 수신해 발송을 트리거하는 핸들러.
 *
 * Transactional Outbox Pattern의 발송 트리거 역할을 한다.
 *
 * {@code @TransactionalEventListener(AFTER_COMMIT)}: DB 커밋이 완료된 후에만 실행된다.
 * 커밋 전에 서버가 재시작되면 이 핸들러는 실행되지 않으며,
 * PENDING 상태로 남은 알림은 스케줄러가 재처리한다.
 *
 * {@code @Async}: 별도의 스레드풀(notificationExecutor)에서 실행되어
 * 발송 지연이 HTTP 응답 시간에 영향을 주지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventHandler {

    private final NotificationDispatchService dispatchService;

    /**
     * 알림 생성 이벤트를 수신한다.
     *
     * 트랜잭션 커밋 이후 비동기로 실행되므로 이 메서드의 예외가
     * 발송 요청 API의 응답에 영향을 주지 않는다.
     * 발송 실패 시 스케줄러가 RETRYING 상태를 감지해 재처리한다.
     *
     * @param event 알림 생성 이벤트 (notificationId 포함)
     */
    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(NotificationCreatedEvent event) {
        if (event.scheduledAt() != null && event.scheduledAt().isAfter(LocalDateTime.now())) {
            log.debug("예약 발송 알림 - 즉시 발송 생략, 스케줄러에 위임. id={}, scheduledAt={}",
                    event.notificationId(), event.scheduledAt());
            return;
        }
        log.debug("알림 발송 이벤트 수신. id={}", event.notificationId());
        dispatchService.dispatch(event.notificationId());
    }
}
