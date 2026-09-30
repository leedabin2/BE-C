package com.notification.application.port.out;
// PRD: F2-3 → docs/prd/F2.md

import com.notification.domain.DispatchHistory;

public interface DispatchHistoryRepositoryPort {
    void save(DispatchHistory dispatchHistory);
}
