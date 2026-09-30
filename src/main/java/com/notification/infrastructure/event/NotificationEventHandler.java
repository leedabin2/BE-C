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


/**
 * 커밋 직후 발송을 트리거한다. <b>지연 단축이 유일한 목적이다.</b>
 *
 * AFTER_COMMIT: 커밋된 뒤에만 실행. 커밋 전에 죽으면 이 핸들러는 아예 안 돈다.
 * @Async: realtimeExecutor로 스레드를 갈라 HTTP 응답이 발송을 기다리지 않게 한다.
 *
 * ⚠️ 유실 방지 장치가 아니다. 이 핸들러가 통째로 실패해도 행은 PENDING이라
 * 스케줄러가 이후 due 후보로 조회한다. backlog/장애에 따라 1분보다 늦어질 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventHandler {

    private final NotificationDispatchService dispatchService;

    /** 실행 스레드: notification-rt-*. 여기서 터져도 HTTP 응답에는 영향이 없다(이미 반환됨). */
    @Async("realtimeExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(NotificationCreatedEvent event) {
        // 예약 판단은 claim의 DB 시각으로 통일한다. 미래 예약이면 0행으로 끝나고 외부 호출하지 않는다.
        log.debug("알림 발송 이벤트 수신. id={}", event.notificationId());
        dispatchService.dispatch(event.notificationId());
    }
}
