package com.notification.application.port.out;
// PRD: F2, F3, F5 → docs/prd/F3.md, docs/prd/F5.md

import com.notification.domain.Notification;
import com.notification.domain.NotificationOperationalSnapshot;
import com.notification.domain.NotificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 알림 저장소 출력 포트.
 *
 * application 레이어가 영속성 기술(JPA 등)에 직접 의존하지 않도록
 * 필요한 저장소 연산을 인터페이스로 정의한다.
 * 구현체는 infrastructure/repository에 위치한다 (의존성 역전 원칙).
 */
public interface NotificationRepositoryPort {

    /** 선점·lease와 동일한 DB 시계의 현재 시각(UTC). 재시도 예약 계산의 기준이다. */
    LocalDateTime currentTime();

    /** 알림을 저장하고 저장된 엔티티를 반환한다. */
    Notification save(Notification notification);

    /**
     * 알림을 저장하고 즉시 flush한다.
     * Hibernate는 save() 이후 실제 INSERT를 커밋 직전까지 지연할 수 있어,
     * DataIntegrityViolationException이 catch 범위 바깥(커밋 시점)에서 발생한다.
     * 중복 키 예외를 save() 직후에 잡아야 하는 경우 이 메서드를 사용한다.
     */
    Notification saveAndFlush(Notification notification);

    /** ID로 알림을 조회한다. */
    Optional<Notification> findById(Long id);

    /**
     * 멱등성 키로 알림을 조회한다.
     * 중복 등록 요청 시 기존 결과를 반환하는 데 사용된다.
     */
    Optional<Notification> findByIdempotencyKey(String idempotencyKey);

    /**
     * 수신자별 알림 목록을 페이징 조회한다.
     *
     * @param receiverId 수신자 ID
     * @param isRead     읽음 여부 필터. null이면 전체 조회
     * @param pageable   페이지 정보
     */
    Page<Notification> findByReceiver(Long receiverId, Boolean isRead, Pageable pageable);

    /**
     * 처리 대기 중인 알림을 비관적 락(SKIP LOCKED)으로 조회한다.
     * DB 시각으로 due 후보를 조회한다. 조회 TX 종료 후 소유권은 없으며 실행 시 claim이 필요하다.
     *
     * @param limit 최대 조회 건수
     */
    List<Notification> findPendingWithLock(int limit);

    /**
     * PROCESSING 상태로 stuck된 알림을 조회한다.
     * 서버 재시작 등으로 PROCESSING 상태에서 멈춘 알림을 복구하는 데 사용된다.
     *
     * @return 현재 시각 기준 leaseUntil이 만료되고 소유자 token이 남아 있는 PROCESSING 알림
     */
    List<Notification> findExpiredProcessingLease(int limit);

    /** 만료됐지만 token이 없어 회수할 수 없는 이상 행 수. 경보용이며 상태를 바꾸지 않는다. */
    long countExpiredProcessingWithoutToken();

    /**
     * 외부 호출 직전 소유권 재확인. 주어진 token이 아직 이 알림의 PROCESSING 소유자면 남은 lease 초를 돌려준다.
     *
     * @return 소유자가 아니면 empty. 이미 만료됐으면 0 이하의 값
     */
    Optional<Long> remainingLeaseSeconds(Long notificationId, String processingToken);

    /**
     * 외부 호출 직전 검사와 시작 의도 기록을 한 번에 한다.
     * @return 소유권·잔여 lease·기한이 모두 유효해 호출해도 되면 true
     */
    boolean tryRecordAttemptIntent(Long notificationId, String processingToken, int minRemainingSeconds);

    /** 소유권이 유효하고 기한도 남아 있는가. 소유자가 아니면 empty. */
    Optional<Boolean> withinExpiry(Long notificationId, String processingToken);

    /** 기한이 지난 채 대기 중인 알림. 정리 작업 전용 조회다. */
    List<Notification> findOverdue(int limit);

    /** 대기 상태와 기한을 CAS로 재확인하고 만료 종료한다. @return 1행 갱신했으면 true */
    boolean tryExpire(Long notificationId);

    /** 선점 상태에서 기한 만료로 종료한다. token 조건이라 새 소유자가 생겼으면 false. */
    boolean tryExpireProcessing(Long notificationId, String processingToken);

    /** 운영 관측용 전역 DB 집계. 개별 상태 전이를 판단하는 용도가 아니다. */
    NotificationOperationalSnapshot getOperationalSnapshot();

    /**
     * CAS(Compare-And-Set) 방식으로 PENDING/RETRYING → PROCESSING 상태 전환.
     * 이벤트 핸들러와 스케줄러가 동시에 같은 알림을 처리하려 할 때
     * 먼저 성공한 쪽만 처리하도록 보장한다.
     *
     * 예약·재시도 시각이 DB 현재 시각 이하일 때만 성공한다.
     * @return 성공 시 true, 아직 due가 아니거나 이미 선점/종료된 경우 false
     */
    boolean tryStartProcessing(Long id, String processingToken, int leaseMinutes);

    /**
     * 읽어 둔 대기 상태를 조건에 포함해 선점한다. 그 사이 상태가 바뀌었으면 false.
     * 호출자가 "되돌릴 상태"를 추정하지 않고 확보할 수 있게 한다.
     */
    boolean tryStartProcessingFrom(Long id, String processingToken, int leaseMinutes,
                                   NotificationStatus expectedStatus);

    /**
     * 배치 worker 제출이 거절된 경우, 아직 외부 발송을 시작하지 않은 claim을 원래 대기 상태로 되돌린다.
     *
     * token을 조건에 넣으므로, lease 복구나 다른 소유자가 이미 상태를 바꿨다면 0행으로 안전하게 끝난다.
     */
    boolean tryReleaseProcessing(Long id, String processingToken, NotificationStatus previousStatus,
                                 LocalDateTime previousNextRetryAt);

    /**
     * CAS 방식으로 PROCESSING → SENT / RETRYING / FAILED 결과 반영.
     *
     * 외부 발송은 트랜잭션 밖에서 끝났고, 이 호출은 별도 트랜잭션(TX-B)에서 실행된다.
     * 그 사이 Stuck 복구가 이 행을 PENDING으로 되돌렸을 수 있으므로
     * {@code WHERE status = 'PROCESSING' AND processing_token = :token}으로
     * "내가 선점한 상태 그대로인가"를 확인하고 쓴다.
     * 전이 규칙(횟수·백오프)은 도메인 메서드가 이미 계산했고, 여기서는 그 결과 필드
     * (status, retryCount, nextRetryAt, failureReason)만 그대로 기록한다.
     *
     * @param notification 도메인 전이가 끝난 detached 엔티티
     * @return 1행 반영 시 true. 0행이면 이미 PROCESSING이 아니라는 뜻 → 호출자가 "늦은 결과"로 기록한다
     */
    boolean tryFinishProcessing(Notification notification);

    /**
     * CAS 방식으로 lease가 만료된 PROCESSING을 RETRYING 또는 FAILED로 복구한다.
     * token과 lease가 달라진 경우 0행 업데이트로 안전하게 스킵한다.
     *
     * @param id              알림 ID
     * @param processingToken claim 때 발급한 소유권 token
     * @param recovery        도메인이 계산한 다음 상태·횟수·백오프·실패 사유
     * @return 복구 성공 시 true
     */
    boolean tryRecoverStuck(Long id, String processingToken, Notification.StuckRecovery recovery);
}
