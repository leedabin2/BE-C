package com.notification.application.port.out;

import com.notification.domain.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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

    /** 알림을 저장하고 저장된 엔티티를 반환한다. */
    Notification save(Notification notification);

    /** ID로 알림을 조회한다. */
    Optional<Notification> findById(Long id);

    /**
     * 멱등성 키 중복 여부를 확인한다.
     * 동일한 비즈니스 이벤트에 대한 중복 발송 요청을 차단하는 데 사용된다.
     */
    boolean existsByIdempotencyKey(String idempotencyKey);

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
     * 다중 인스턴스 환경에서 동일 알림을 여러 스케줄러가 중복 처리하지 않도록 보장한다.
     *
     * @param limit 최대 조회 건수
     */
    List<Notification> findPendingWithLock(int limit);

    /**
     * PROCESSING 상태로 stuck된 알림을 조회한다.
     * 서버 재시작 등으로 PROCESSING 상태에서 멈춘 알림을 복구하는 데 사용된다.
     *
     * @param minutes 이 시간(분) 이상 PROCESSING 상태인 알림을 대상으로 한다
     */
    List<Notification> findStuckProcessing(int minutes);

    /**
     * CAS(Compare-And-Set) 방식으로 PENDING/RETRYING → PROCESSING 상태 전환.
     * 이벤트 핸들러와 스케줄러가 동시에 같은 알림을 처리하려 할 때
     * 먼저 성공한 쪽만 처리하도록 보장한다.
     *
     * @return 상태 전환 성공 시 true, 이미 다른 스레드가 선점한 경우 false
     */
    boolean tryStartProcessing(Long id);
}
