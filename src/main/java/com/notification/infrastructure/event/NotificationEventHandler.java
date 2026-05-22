package com.notification.infrastructure.event;

import com.notification.application.event.NotificationCreatedEvent;
import com.notification.domain.Notification;
import com.notification.domain.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventHandler {

    private final NotificationRepository notificationRepository;

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(NotificationCreatedEvent event) {
        notificationRepository.findById(event.notificationId())
                .ifPresentOrElse(
                        this::dispatch,
                        () -> log.warn("알림을 찾을 수 없습니다. id={}", event.notificationId())
                );
    }

    private void dispatch(Notification notification) {
        log.info("알림 발송 처리 시작. id={}, channel={}", notification.getId(), notification.getChannel());
        // TODO: 채널별 실제 발송 어댑터 연결 (email sender, in-app push 등)
    }
}
