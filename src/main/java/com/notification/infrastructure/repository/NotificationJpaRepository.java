package com.notification.infrastructure.repository;

import com.notification.domain.Notification;
import com.notification.domain.NotificationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.QueryHint;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * JPA 기반 알림 저장소.
 *
 * JPA 기반 알림 저장소. Spring Data JPA가 런타임에 프록시를 생성한다.
 */
public interface NotificationJpaRepository extends JpaRepository<Notification, Long> {

    /** 멱등성 키로 알림 조회. */
    Optional<Notification> findByIdempotencyKey(String idempotencyKey);

    /**
     * 수신자별 알림 목록을 최신순으로 페이징 조회한다.
     *
     * @param isRead null이면 읽음 여부 무관하게 전체 조회
     */
    @Query("SELECT n FROM Notification n WHERE n.receiverId = :receiverId " +
           "AND (:isRead IS NULL OR n.isRead = :isRead) " +
           "ORDER BY n.createdAt DESC")
    Page<Notification> findByReceiver(@Param("receiverId") Long receiverId,
                                      @Param("isRead") Boolean isRead,
                                      Pageable pageable);

    /**
     * 발송 대기 중인 알림을 비관적 락으로 조회한다.
     *
     * PESSIMISTIC_WRITE + SKIP LOCKED 동작으로, 다중 인스턴스 환경에서
     * 여러 스케줄러가 동시에 실행되더라도 같은 알림을 중복 처리하지 않는다.
     * 이미 다른 인스턴스가 잠금을 획득한 행은 건너뛴다.
     *
     * @param limit 한 번에 처리할 최대 건수
     * @param now   현재 시각 (scheduledAt, nextRetryAt 비교 기준)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT n FROM Notification n WHERE n.status IN :statuses " +
           "AND (n.scheduledAt IS NULL OR n.scheduledAt <= :now) " +
           "AND (n.nextRetryAt IS NULL OR n.nextRetryAt <= :now) " +
           "ORDER BY n.createdAt ASC LIMIT :limit")
    List<Notification> findPendingWithLock(@Param("limit") int limit,
                                           @Param("now") LocalDateTime now,
                                           @Param("statuses") List<NotificationStatus> statuses);

    /**
     * PROCESSING 상태에서 일정 시간 이상 멈춰 있는 알림을 조회한다.
     *
     * 서버 재시작이나 비정상 종료로 PROCESSING 상태에 stuck된 알림을
     * 감지해 PENDING으로 복구하는 데 사용된다.
     *
     * @param threshold 이 시각 이전에 업데이트된 PROCESSING 알림을 대상으로 한다
     */
    @Query("SELECT n FROM Notification n WHERE n.status = :status " +
           "AND n.updatedAt <= :threshold")
    List<Notification> findStuckProcessing(@Param("status") NotificationStatus status,
                                           @Param("threshold") LocalDateTime threshold);

    /**
     * CAS(Compare-And-Set) 방식으로 PROCESSING 상태 전환.
     * clearAutomatically로 1차 캐시를 비워 이후 findById가 최신 상태를 반환하도록 한다.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE notification SET status = 'PROCESSING', updated_at = NOW() " +
                   "WHERE id = :id AND status IN ('PENDING', 'RETRYING')",
           nativeQuery = true)
    int tryStartProcessing(@Param("id") Long id);
}
