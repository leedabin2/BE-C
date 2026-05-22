package com.notification.infrastructure.event;

import com.notification.application.event.NotificationCreatedEvent;
import com.notification.application.port.out.NotificationEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SpringEventNotificationPublisher implements NotificationEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publish(NotificationCreatedEvent event) {
        applicationEventPublisher.publishEvent(event);
    }
}
