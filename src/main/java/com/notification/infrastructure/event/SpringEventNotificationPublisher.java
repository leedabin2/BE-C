package com.notification.infrastructure.event;

import com.notification.application.event.NotificationCreatedEvent;
import com.notification.application.port.out.NotificationEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Spring ApplicationEventPublisher를 사용하는 이벤트 발행 구현체.
 *
 * <p>application 레이어의 {@link com.notification.application.port.out.NotificationEventPublisher} 포트를 구현한다.
 * Spring 내부 이벤트 버스를 사용하므로 JVM 내부 통신이며, 외부 메시지 브로커가 아니다.
 * 추후 Kafka 등으로 교체 시 이 클래스만 바꾸면 된다.</p>
 */
@Component
@RequiredArgsConstructor
public class SpringEventNotificationPublisher implements NotificationEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publish(NotificationCreatedEvent event) {
        applicationEventPublisher.publishEvent(event);
    }
}
