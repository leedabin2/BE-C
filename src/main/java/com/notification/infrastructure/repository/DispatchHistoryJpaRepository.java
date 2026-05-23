package com.notification.infrastructure.repository;

import com.notification.domain.DispatchHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DispatchHistoryJpaRepository extends JpaRepository<DispatchHistory, Long> {
}
