package com.notification.application.port.out;
// PRD: F4-1 → docs/prd/F4.md

import com.notification.application.event.NotificationCreatedEvent;

public interface NotificationEventPublisherPort {
    void publish(NotificationCreatedEvent event);
}
