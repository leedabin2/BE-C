package com.notification.infrastructure.repository;
// PRD: F2-3 → docs/prd/F2.md

import com.notification.application.port.out.NotificationLogRepositoryPort;
import com.notification.domain.NotificationLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class NotificationLogRepositoryImpl implements NotificationLogRepositoryPort {

    private final NotificationLogJpaRepository jpaRepository;

    @Override
    public void save(NotificationLog notificationLog) {
        jpaRepository.save(notificationLog);
    }
}
