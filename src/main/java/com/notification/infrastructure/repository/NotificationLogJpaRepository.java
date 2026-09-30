package com.notification.infrastructure.repository;
// PRD: F2-3 → docs/prd/F2.md

import com.notification.domain.NotificationLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationLogJpaRepository extends JpaRepository<NotificationLog, Long> {
}
