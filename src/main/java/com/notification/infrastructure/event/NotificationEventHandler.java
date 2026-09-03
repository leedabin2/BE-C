package com.notification.infrastructure.event;
// PRD: F4-1, O-1 → docs/prd/F4.md

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
 * 커밋 직후 발송을 트리거한다. <b>지연 단축이 유일한 목적이다.</b>
 *
 * AFTER_COMMIT: 커밋된 뒤에만 실행. 커밋 전에 죽으면 이 핸들러는 아예 안 돈다.
 * @Async: notificationExecutor로 스레드를 갈라 HTTP 응답이 발송을 기다리지 않게 한다.
 *
 * ⚠️ 유실 방지 장치가 아니다. 이 핸들러가 통째로 실패해도 행은 PENDING이라
 * 스케줄러가 1분 내 회수한다. 지우면 발송이 최대 1분 늦어질 뿐 정합성은 그대로다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventHandler {

    private final NotificationDispatchService dispatchService;

    /** 실행 스레드: notification-*. 여기서 터져도 HTTP 응답에는 영향이 없다(이미 반환됨). */
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
