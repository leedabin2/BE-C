package com.notification.infrastructure.repository;

import com.notification.domain.Notification;
import com.notification.application.port.out.NotificationRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class NotificationRepositoryImpl implements NotificationRepositoryPort {

    private final NotificationJpaRepository jpaRepository;

    @Override
    public Notification save(Notification notification) {
        return jpaRepository.save(notification);
    }

    @Override
    public Optional<Notification> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public boolean existsByIdempotencyKey(String idempotencyKey) {
        return jpaRepository.existsByIdempotencyKey(idempotencyKey);
    }

    @Override
    public Page<Notification> findByReceiver(Long receiverId, Boolean isRead, Pageable pageable) {
        return jpaRepository.findByReceiver(receiverId, isRead, pageable);
    }

    @Override
    public List<Notification> findPendingWithLock(int limit) {
        return jpaRepository.findPendingWithLock(limit, LocalDateTime.now());
    }

    @Override
    public List<Notification> findStuckProcessing(int minutes) {
        return jpaRepository.findStuckProcessing(LocalDateTime.now().minusMinutes(minutes));
    }

    @Override
    public boolean tryStartProcessing(Long id) {
        return jpaRepository.tryStartProcessing(id) > 0;
    }
}
