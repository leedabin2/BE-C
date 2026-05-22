package com.notification.application.port.out;

import com.notification.application.event.NotificationCreatedEvent;

public interface NotificationEventPublisher {
    void publish(NotificationCreatedEvent event);
}
