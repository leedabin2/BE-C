package com.notification.application.port.out;

import com.notification.domain.NotificationLog;

public interface NotificationLogRepositoryPort {
    void save(NotificationLog notificationLog);
}
