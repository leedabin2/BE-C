package com.notification.infrastructure.repository;
// PRD: F3, F5 → docs/prd/F3.md, docs/prd/F5.md

import com.notification.domain.Notification;
import com.notification.domain.NotificationOperationalSnapshot;
import com.notification.domain.NotificationStatus;
import com.notification.application.port.out.NotificationRepositoryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class NotificationRepositoryImpl implements NotificationRepositoryPort {

    private static final DateTimeFormatter DB_DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

    /**
     * Hibernate 6.6 native UPDATE의 추론된 LocalDateTime 파라미터는 direct JDBC 설정과 달리
     * Timestamp로 바인딩될 수 있다. DATETIME(6) 문자열 파라미터로 UTC 벽시각을 보존한다.
     * SQL 문자열 연결이 아닌 prepared parameter이며 null도 그대로 유지한다.
     */
    private static String databaseDateTime(LocalDateTime value) {
        return value == null ? null : DB_DATETIME.format(value);
    }

    private final NotificationJpaRepository jpaRepository;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public LocalDateTime currentTime() {
        // JPA native scalar의 Timestamp -> LocalDateTime 변환은 JVM 시간대를 끼워 넣을 수 있다.
        // UTC 벽시각을 JDBC 4.2 타입으로 직접 읽어 9시간 이동을 방지한다.
        // CURRENT_TIMESTAMP가 아닌 UTC_TIMESTAMP: 세션 시간대 설정이 빠진 연결에서도 due/lease 판정과
        // 같은 기준 시각을 쓴다. ShedLock의 usingDbTime()도 MySQL에서 UTC_TIMESTAMP를 쓴다. (P0-a)
        return jdbcTemplate.queryForObject("SELECT UTC_TIMESTAMP(6)",
                (rs, rowNum) -> rs.getObject(1, LocalDateTime.class));
    }

    @Override
    public Notification save(Notification notification) {
        return jpaRepository.save(notification);
    }

    @Override
    public Notification saveAndFlush(Notification notification) {
        return jpaRepository.saveAndFlush(notification);
    }

    @Override
    public Optional<Notification> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public Optional<Notification> findByIdempotencyKey(String idempotencyKey) {
        return jpaRepository.findByIdempotencyKey(idempotencyKey);
    }

    @Override
    public Page<Notification> findByReceiver(Long receiverId, Boolean isRead, Pageable pageable) {
        return jpaRepository.findByReceiver(receiverId, isRead, pageable);
    }

    @Override
    public List<Notification> findPendingWithLock(int limit) {
        // native query에는 enum이 아니라 저장 문자열을 넘긴다. @Enumerated(STRING) 매핑은 JPQL 경로에만 적용된다.
        return jpaRepository.findPendingWithLock(
                limit,
                List.of(NotificationStatus.PENDING.name(), NotificationStatus.RETRYING.name()));
    }

    @Override
    public List<Notification> findExpiredProcessingLease(int limit) {
        return jpaRepository.findExpiredProcessingLease(NotificationStatus.PROCESSING.name(), limit);
    }

    @Override
    public Optional<Long> remainingLeaseSeconds(Long notificationId, String processingToken) {
        return Optional.ofNullable(jpaRepository.findRemainingLeaseSeconds(notificationId, processingToken));
    }

    @Override
    public boolean tryRecordAttemptIntent(Long notificationId, String processingToken, int minRemainingSeconds) {
        return jpaRepository.tryRecordAttemptIntent(notificationId, processingToken, minRemainingSeconds) > 0;
    }

    @Override
    public Optional<Boolean> withinExpiry(Long notificationId, String processingToken) {
        Integer within = jpaRepository.findWithinExpiry(notificationId, processingToken);
        return Optional.ofNullable(within).map(v -> v == 1);
    }

    @Override
    public List<Notification> findOverdue(int limit) {
        return jpaRepository.findOverdue(limit);
    }

    @Override
    public boolean tryExpire(Long notificationId) {
        return jpaRepository.tryExpire(notificationId) > 0;
    }

    @Override
    public boolean tryExpireProcessing(Long notificationId, String processingToken) {
        return jpaRepository.tryExpireProcessing(notificationId, processingToken) > 0;
    }

    @Override
    public long countExpiredProcessingWithoutToken() {
        return jpaRepository.countExpiredProcessingWithoutToken(NotificationStatus.PROCESSING.name());
    }

    @Override
    public NotificationOperationalSnapshot getOperationalSnapshot() {
        // 하나의 aggregate SELECT 안에서 DB UTC_TIMESTAMP(6)를 사용한다. scheduler마다 다른 JVM 시계나
        // 여러 count 쿼리 사이의 상태 변화를 관측값에 섞지 않는다.
        return jdbcTemplate.queryForObject("""
                SELECT
                  COALESCE(SUM(CASE WHEN status IN ('PENDING', 'RETRYING')
                    AND (scheduled_at IS NULL OR scheduled_at <= UTC_TIMESTAMP(6))
                    AND (next_retry_at IS NULL OR next_retry_at <= UTC_TIMESTAMP(6)) THEN 1 ELSE 0 END), 0),
                  COALESCE(MAX(CASE WHEN status IN ('PENDING', 'RETRYING')
                    AND (scheduled_at IS NULL OR scheduled_at <= UTC_TIMESTAMP(6))
                    AND (next_retry_at IS NULL OR next_retry_at <= UTC_TIMESTAMP(6))
                    THEN TIMESTAMPDIFF(SECOND,
                      GREATEST(COALESCE(scheduled_at, created_at), COALESCE(next_retry_at, created_at)), UTC_TIMESTAMP(6))
                    ELSE 0 END), 0),
                  COALESCE(SUM(status = 'PROCESSING'), 0),
                  COALESCE(SUM(status = 'PROCESSING' AND lease_until <= UTC_TIMESTAMP(6)), 0),
                  COALESCE(SUM(status = 'RETRYING'), 0),
                  COALESCE(SUM(status = 'FAILED'), 0)
                FROM notification
                """, (rs, rowNum) -> new NotificationOperationalSnapshot(
                rs.getLong(1), rs.getLong(2), rs.getLong(3),
                rs.getLong(4), rs.getLong(5), rs.getLong(6)));
    }

    @Override
    public boolean tryStartProcessing(Long id, String processingToken, int leaseMinutes) {
        return jpaRepository.tryStartProcessing(id, processingToken, leaseMinutes) > 0;
    }

    @Override
    public boolean tryStartProcessingFrom(Long id, String processingToken, int leaseMinutes,
                                          NotificationStatus expectedStatus) {
        return jpaRepository.tryStartProcessingFrom(id, processingToken, leaseMinutes, expectedStatus.name()) > 0;
    }

    @Override
    public boolean tryReleaseProcessing(Long id, String processingToken, NotificationStatus previousStatus,
                                        LocalDateTime previousNextRetryAt) {
        return jpaRepository.tryReleaseProcessing(
                id, processingToken, previousStatus.name(), databaseDateTime(previousNextRetryAt)) > 0;
    }

    @Override
    public boolean tryFinishProcessing(Notification n) {
        return jpaRepository.tryFinishProcessing(
                n.getId(),
                n.getStatus().name(),
                n.getRetryCount(),
                databaseDateTime(n.getNextRetryAt()),
                n.getFailureReason(),
                n.getProcessingToken()) > 0;
    }

    @Override
    public boolean tryRecoverStuck(Long id, String processingToken, Notification.StuckRecovery recovery) {
        return jpaRepository.tryRecoverStuck(
                id,
                recovery.status().name(),
                recovery.retryCount(),
                recovery.cycleRecoveryCount(),
                databaseDateTime(recovery.nextRetryAt()),
                recovery.failureReason(),
                processingToken) > 0;
    }
}
