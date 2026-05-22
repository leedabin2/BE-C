package com.notification.infrastructure.repository;

import com.notification.domain.Notification;
import com.notification.domain.NotificationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface NotificationJpaRepository extends JpaRepository<Notification, Long> {

    boolean existsByIdempotencyKey(String idempotencyKey);

    @Query("SELECT n FROM Notification n WHERE n.receiverId = :receiverId " +
           "AND (:isRead IS NULL OR n.isRead = :isRead) " +
           "ORDER BY n.createdAt DESC")
    Page<Notification> findByReceiver(@Param("receiverId") Long receiverId,
                                      @Param("isRead") Boolean isRead,
                                      Pageable pageable);

    // SKIP LOCKED: 다중 인스턴스 환경에서 중복 처리 방지
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(value = "SELECT n FROM Notification n WHERE n.status IN ('PENDING', 'RETRYING') " +
                   "AND (n.scheduledAt IS NULL OR n.scheduledAt <= :now) " +
                   "AND (n.nextRetryAt IS NULL OR n.nextRetryAt <= :now) " +
                   "ORDER BY n.createdAt ASC LIMIT :limit",
           nativeQuery = false)
    List<Notification> findPendingWithLock(@Param("limit") int limit, @Param("now") LocalDateTime now);

    @Query("SELECT n FROM Notification n WHERE n.status = 'PROCESSING' " +
           "AND n.updatedAt <= :threshold")
    List<Notification> findStuckProcessing(@Param("threshold") LocalDateTime threshold);
}
