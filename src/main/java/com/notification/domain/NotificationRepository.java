package com.notification.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface NotificationRepository {
    Notification save(Notification notification);
    Optional<Notification> findById(Long id);
    boolean existsByIdempotencyKey(String idempotencyKey);
    Page<Notification> findByReceiver(Long receiverId, Boolean isRead, Pageable pageable);
    List<Notification> findPendingWithLock(int limit);
    List<Notification> findStuckProcessing(int minutes);
}
