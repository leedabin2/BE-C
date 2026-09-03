package com.notification.application.port.out;
// PRD: F2-3 → docs/prd/F2.md

import com.notification.domain.NotificationLog;

public interface NotificationLogRepositoryPort {
    void save(NotificationLog notificationLog);
}
