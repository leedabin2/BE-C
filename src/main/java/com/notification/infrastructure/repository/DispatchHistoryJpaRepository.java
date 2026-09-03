package com.notification.infrastructure.repository;
// PRD: F2-3 → docs/prd/F2.md

import com.notification.domain.DispatchHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DispatchHistoryJpaRepository extends JpaRepository<DispatchHistory, Long> {
}
