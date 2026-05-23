package com.notification.application.port.out;

import com.notification.domain.DispatchHistory;

public interface DispatchHistoryRepositoryPort {
    void save(DispatchHistory dispatchHistory);
}
