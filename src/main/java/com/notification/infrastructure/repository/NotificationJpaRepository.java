package com.notification.infrastructure.repository;
// PRD: F3-1 (unique), F5-3 (SKIP LOCKED·CAS), O-1 → docs/prd/F3.md, docs/prd/F5.md

import com.notification.domain.Notification;
import com.notification.domain.NotificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * JPA 기반 알림 저장소.
 *
 * JPA 기반 알림 저장소. Spring Data JPA가 런타임에 프록시를 생성한다.
 */
public interface NotificationJpaRepository extends JpaRepository<Notification, Long> {

    /** 상태별 건수. 만 건 규모에서 findAll()로 세면 메모리를 통째로 쓴다. */
    long countByStatusIn(List<NotificationStatus> statuses);

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
     * SKIP LOCKED로, 다중 인스턴스 환경에서 조회 중 다른 스케줄러의 행 락을 건너뛴다.
     * 커밋 후 소유권은 claim CAS가 보장한다.
     *
     * <p>JPQL이 아닌 native인 이유: JPQL {@code CURRENT_TIMESTAMP}는 <b>세션 시간대</b>를 따른다.
     * 저장된 DATETIME은 UTC 벽시각이므로 세션이 +09:00인 연결에서는 9시간 뒤 예약까지 due로 보인다.
     * {@code UTC_TIMESTAMP(6)}는 세션 설정과 무관하게 UTC를 돌려주므로 Hikari 설정이 빠져도
     * 조기 발송이 생기지 않는다. 락도 JPA 힌트 대신 SQL에 그대로 적는다. (P0-a)
     *
     * @param limit 한 번에 처리할 최대 건수
     */
    @Query(value = "SELECT * FROM notification WHERE status IN (:statuses) " +
                   "AND (scheduled_at IS NULL OR scheduled_at <= UTC_TIMESTAMP(6)) " +
                   "AND (next_retry_at IS NULL OR next_retry_at <= UTC_TIMESTAMP(6)) " +
                   "AND (expires_at IS NULL OR expires_at > UTC_TIMESTAMP(6)) " +
                   "ORDER BY created_at ASC LIMIT :limit " +
                   "FOR UPDATE SKIP LOCKED",
           nativeQuery = true)
    List<Notification> findPendingWithLock(@Param("limit") int limit,
                                           @Param("statuses") List<String> statuses);

    /**
     * lease가 만료된 PROCESSING 알림을 조회한다.
     *
     * 서버 재시작이나 비정상 종료로 PROCESSING 상태에 stuck된 알림을
     * 감지해 재시도 실패 1회 또는 최종 실패로 복구하는 데 사용된다.
     *
     * 만료 판정은 애플리케이션 시계도, 세션 시간대에 흔들리는 {@code CURRENT_TIMESTAMP}도 아닌
     * {@code UTC_TIMESTAMP(6)}를 쓴다. 세션이 +09:00이면 아직 9시간 남은 lease까지 만료로 보여
     * 발송 중인 소유자의 작업을 회수한다 — 중복 발송 경로다. (P0-a)
     */
    @Query(value = "SELECT * FROM notification WHERE status = :status " +
                   "AND lease_until <= UTC_TIMESTAMP(6) " +
                   "AND processing_token IS NOT NULL " +
                   "ORDER BY lease_until ASC LIMIT :limit",
           nativeQuery = true)
    List<Notification> findExpiredProcessingLease(@Param("status") String status,
                                                   @Param("limit") int limit);

    /**
     * 만료됐지만 소유자 token이 없는 이상 행 수. 회수 CAS가 성립할 수 없는 데이터다.
     *
     * <p>이런 행을 회수 조회에 섞으면 "오래된 순" 선두를 계속 차지해 뒤의 정상 만료 행이 굶는다.
     * 그래서 조회에서는 빼고 여기서 세어 경보만 올린다. 임의 token을 발급해 다시 발송하지 않는다. (P0-b)
     */
    @Query(value = "SELECT COUNT(*) FROM notification WHERE status = :status " +
                   "AND lease_until <= UTC_TIMESTAMP(6) " +
                   "AND processing_token IS NULL",
           nativeQuery = true)
    long countExpiredProcessingWithoutToken(@Param("status") String status);

    /**
     * 아직 내가 소유한 PROCESSING인지와, 남은 lease 초를 <b>한 번의 조회</b>로 가져온다.
     *
     * <p>외부 호출 직전 방어용이다. token 일치만 보면 부족하다 — 만료된 lease의 token도 회수 전까지는
     * 그대로 일치하기 때문이다. 소유자가 아니면 행이 없어 null이 오고, 만료됐으면 음수가 온다.
     * lease_until이 비어 있는 이상 데이터는 만료(-1)로 본다. (P0-c)
     */
    @Query(value = "SELECT COALESCE(TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(6), lease_until), -1) " +
                   "FROM notification " +
                   "WHERE id = :id AND status = 'PROCESSING' AND processing_token = :processingToken",
           nativeQuery = true)
    Long findRemainingLeaseSeconds(@Param("id") Long id,
                                   @Param("processingToken") String processingToken);

    /**
     * 외부 호출 직전의 <b>검사와 시작 의도 기록을 한 문장으로</b> 처리한다.
     *
     * <p>왜 하나로 묶나: 검사와 기록이 따로면 그 사이에 소유권이 바뀔 수 있고, 왕복도 두 번이 된다.
     * 조건부 UPDATE 한 번이면 "아직 내 것이고, 끝낼 시간이 있고, 기한도 남았다"를 확인하는 동시에
     * 의도를 남긴다. 0행이면 셋 중 무엇이 틀렸는지는 별도 조회로 따진다(드문 경로).
     *
     * <p>이 값은 <b>정확한 외부 호출 수가 아니다.</b> 기록 직후 죽으면 호출 여부를 알 수 없고,
     * 그 불확실성 자체가 이 카운터의 의미다. (재시도 정책 §5.6)
     *
     * @param minRemainingSeconds 발송을 끝내는 데 필요한 최소 잔여 lease
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE notification " +
                   "SET attempt_intent_count = COALESCE(attempt_intent_count, 0) + 1, " +
                   "updated_at = UTC_TIMESTAMP(6) " +
                   "WHERE id = :id AND status = 'PROCESSING' AND processing_token = :processingToken " +
                   "AND lease_until >= TIMESTAMPADD(SECOND, :minRemainingSeconds, UTC_TIMESTAMP(6)) " +
                   "AND (expires_at IS NULL OR expires_at > UTC_TIMESTAMP(6))",
           nativeQuery = true)
    int tryRecordAttemptIntent(@Param("id") Long id,
                               @Param("processingToken") String processingToken,
                               @Param("minRemainingSeconds") int minRemainingSeconds);

    /**
     * 내가 소유한 PROCESSING 행의 기한이 아직 남아 있는가. 기한이 없으면(LEGACY_V0) 항상 1이다.
     * 큐에서 기다리는 동안 기한이 지날 수 있으므로 외부 호출 직전에 다시 본다. (재시도 정책 §5.5)
     */
    @Query(value = "SELECT CASE WHEN expires_at IS NULL OR expires_at > UTC_TIMESTAMP(6) THEN 1 ELSE 0 END " +
                   "FROM notification " +
                   "WHERE id = :id AND status = 'PROCESSING' AND processing_token = :processingToken",
           nativeQuery = true)
    Integer findWithinExpiry(@Param("id") Long id,
                             @Param("processingToken") String processingToken);

    /**
     * 대기 중 기한이 지난 알림을 종료한다. <b>PROCESSING은 건드리지 않는다</b> —
     * 이미 외부 호출이 시작됐을 수 있고, 그 결과 반영은 token/lease 경로가 담당한다.
     * 상태를 CAS로 다시 확인해 정리와 선점이 겹쳐도 한쪽만 이긴다.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE notification SET status = 'FAILED', failure_reason = 'EXPIRED', " +
                   "next_retry_at = NULL, updated_at = UTC_TIMESTAMP(6) " +
                   "WHERE id = :id AND status IN ('PENDING', 'RETRYING') " +
                   "AND expires_at IS NOT NULL AND expires_at <= UTC_TIMESTAMP(6)",
           nativeQuery = true)
    int tryExpire(@Param("id") Long id);

    /**
     * 선점한 뒤 호출 전에 기한이 지난 것을 발견했을 때의 종료. token 조건이므로
     * 그 사이 lease가 회수돼 새 소유자가 생겼다면 0행으로 조용히 스킵된다.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE notification SET status = 'FAILED', failure_reason = 'EXPIRED', " +
                   "next_retry_at = NULL, processing_token = NULL, claimed_at = NULL, lease_until = NULL, " +
                   "updated_at = UTC_TIMESTAMP(6) " +
                   "WHERE id = :id AND status = 'PROCESSING' AND processing_token = :processingToken",
           nativeQuery = true)
    int tryExpireProcessing(@Param("id") Long id, @Param("processingToken") String processingToken);

    /** 기한이 지난 채 대기 중인 알림. 오래된 기한부터 정리한다. */
    @Query(value = "SELECT * FROM notification WHERE status IN ('PENDING', 'RETRYING') " +
                   "AND expires_at IS NOT NULL AND expires_at <= UTC_TIMESTAMP(6) " +
                   "ORDER BY expires_at ASC LIMIT :limit",
           nativeQuery = true)
    List<Notification> findOverdue(@Param("limit") int limit);

    /**
     * CAS(Compare-And-Set) 방식으로 PROCESSING 상태 전환.
     * clearAutomatically로 1차 캐시를 비워 이후 findById가 최신 상태를 반환하도록 한다.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE notification SET status = 'PROCESSING', processing_token = :processingToken, " +
                   "claimed_at = UTC_TIMESTAMP(6), lease_until = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL :leaseMinutes MINUTE), updated_at = UTC_TIMESTAMP(6) " +
                   "WHERE id = :id AND status IN ('PENDING', 'RETRYING') " +
                   "AND (scheduled_at IS NULL OR scheduled_at <= UTC_TIMESTAMP(6)) " +
                   "AND (next_retry_at IS NULL OR next_retry_at <= UTC_TIMESTAMP(6)) " +
                   "AND (expires_at IS NULL OR expires_at > UTC_TIMESTAMP(6))",
           nativeQuery = true)
    int tryStartProcessing(@Param("id") Long id,
                           @Param("processingToken") String processingToken,
                           @Param("leaseMinutes") int leaseMinutes);

    /**
     * 읽어 둔 대기 상태를 <b>조건에 포함해</b> 선점한다.
     *
     * <p>단건 선점은 "되돌릴 때 어느 상태로 돌아가야 하는가"를 알아야 한다. 선점 CAS는 PENDING과
     * RETRYING을 한 번에 PROCESSING으로 바꾸므로 사후 스냅샷만으로는 구분할 수 없고, 회수된 TYPE_V1
     * 행처럼 {@code RETRYING + retryCount=0}인 경우엔 카운터로 추정할 수도 없다.
     *
     * <p>그래서 먼저 읽고, 읽은 상태를 조건에 넣어 선점한다. 그 사이 다른 실행자가 상태를 바꿨다면
     * 0행이 되어 선점이 실패한다 — 틀린 가정으로 진행하는 대신 다음 기회로 넘긴다. (P1 후속)
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE notification SET status = 'PROCESSING', processing_token = :processingToken, " +
                   "claimed_at = UTC_TIMESTAMP(6), lease_until = DATE_ADD(UTC_TIMESTAMP(6), INTERVAL :leaseMinutes MINUTE), " +
                   "updated_at = UTC_TIMESTAMP(6) " +
                   "WHERE id = :id AND status = :expectedStatus " +
                   "AND (scheduled_at IS NULL OR scheduled_at <= UTC_TIMESTAMP(6)) " +
                   "AND (next_retry_at IS NULL OR next_retry_at <= UTC_TIMESTAMP(6)) " +
                   "AND (expires_at IS NULL OR expires_at > UTC_TIMESTAMP(6))",
           nativeQuery = true)
    int tryStartProcessingFrom(@Param("id") Long id,
                               @Param("processingToken") String processingToken,
                               @Param("leaseMinutes") int leaseMinutes,
                               @Param("expectedStatus") String expectedStatus);

    /** 외부 I/O를 시작하기 전 worker 큐가 포화되면, claim만 취소해 다음 scheduler가 즉시 다시 집을 수 있게 한다. */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE notification SET status = :status, next_retry_at = :nextRetryAt, " +
                   "processing_token = NULL, claimed_at = NULL, lease_until = NULL, updated_at = UTC_TIMESTAMP(6) " +
                   "WHERE id = :id AND status = 'PROCESSING' AND processing_token = :processingToken",
           nativeQuery = true)
    int tryReleaseProcessing(@Param("id") Long id,
                             @Param("processingToken") String processingToken,
                             @Param("status") String status,
                             @Param("nextRetryAt") String nextRetryAt);

    /**
     * CAS 방식으로 PROCESSING → 결과 상태(SENT/RETRYING/FAILED) 반영.
     * 기대 상태와 processing token을 WHERE에 넣어, Stuck 복구 뒤 새 소유자가 생긴 행은 0행으로 스킵된다.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE notification SET status = :status, retry_count = :retryCount, " +
                   "next_retry_at = :nextRetryAt, eligible_at = COALESCE(:nextRetryAt, eligible_at), " +
                   "failure_reason = :failureReason, " +
                   "processing_token = NULL, claimed_at = NULL, lease_until = NULL, updated_at = UTC_TIMESTAMP(6) " +
                   "WHERE id = :id AND status = 'PROCESSING' AND processing_token = :processingToken",
           nativeQuery = true)
    int tryFinishProcessing(@Param("id") Long id,
                            @Param("status") String status,
                            @Param("retryCount") int retryCount,
                            @Param("nextRetryAt") String nextRetryAt,
                            @Param("failureReason") String failureReason,
                            @Param("processingToken") String processingToken);

    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE notification SET status = :status, retry_count = :retryCount, " +
                   "cycle_recovery_count = :cycleRecoveryCount, " +
                   "next_retry_at = :nextRetryAt, eligible_at = COALESCE(:nextRetryAt, eligible_at), " +
                   "failure_reason = :failureReason, processing_token = NULL, " +
                   "claimed_at = NULL, lease_until = NULL, updated_at = UTC_TIMESTAMP(6) " +
                   "WHERE id = :id AND status = 'PROCESSING' AND processing_token = :processingToken " +
                   "AND lease_until <= UTC_TIMESTAMP(6)",
           nativeQuery = true)
    int tryRecoverStuck(@Param("id") Long id,
                        @Param("status") String status,
                        @Param("retryCount") int retryCount,
                        @Param("cycleRecoveryCount") int cycleRecoveryCount,
                        @Param("nextRetryAt") String nextRetryAt,
                        @Param("failureReason") String failureReason,
                        @Param("processingToken") String processingToken);
}
