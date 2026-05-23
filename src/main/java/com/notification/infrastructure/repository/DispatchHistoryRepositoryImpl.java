package com.notification.infrastructure.repository;

import com.notification.application.port.out.DispatchHistoryRepositoryPort;
import com.notification.domain.DispatchHistory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DispatchHistoryRepositoryImpl implements DispatchHistoryRepositoryPort {

    private final DispatchHistoryJpaRepository jpaRepository;

    @Override
    public void save(DispatchHistory dispatchHistory) {
        jpaRepository.save(dispatchHistory);
    }
}
