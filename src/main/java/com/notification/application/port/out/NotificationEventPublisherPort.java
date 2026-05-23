package com.notification.application.port.out;

import com.notification.application.event.NotificationCreatedEvent;

public interface NotificationEventPublisherPort {
    void publish(NotificationCreatedEvent event);
}
