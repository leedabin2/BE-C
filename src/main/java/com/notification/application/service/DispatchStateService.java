package com.notification.application.service;
// PRD: F2-2, F2-3, F5-3 (선점·결과 반영 CAS) → docs/prd/F2.md, docs/prd/F3.md

import com.notification.application.port.out.DispatchHistoryRepositoryPort;
import com.notification.application.port.out.NotificationLogRepositoryPort;
import com.notification.application.port.out.NotificationRepositoryPort;
import com.notification.domain.DispatchHistory;
import com.notification.domain.Notification;
import com.notification.domain.NotificationLog;
import com.notification.domain.NotificationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 발송의 "DB 상태 전이"만 담당하는 짧은 트랜잭션 2개.
 *
 * <pre>
 * TX-A claim()  : 조건부 UPDATE로 PROCESSING 선점 → 스냅샷 조회 → 커밋   (DB 자원 즉시 반납)
 *  ---           : 외부 발송은 {@link NotificationDispatchService}가 트랜잭션 밖에서 수행
 * TX-B finish() : 조건부 UPDATE로 결과 반영 + 이력 → 커밋
 * </pre>
 *
 * 왜 별도 빈인가: {@code @Transactional}은 프록시로 동작하므로 같은 클래스 내부 호출에는 걸리지 않는다.
 * 조율자({@code dispatch()})와 트랜잭션 단위를 다른 빈으로 나눠야 경계가 실제로 생긴다. (RULES §3-3)
 *
 * 실행 스레드: {@code notification-*}(이벤트 핸들러) 또는 {@code scheduling-*}(스케줄러).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DispatchStateService {

    /** 결과 반영이 0행일 때 남기는 이력 사유. Stuck 복구가 먼저 행을 되돌린 경우다. */
    public static final String LATE_RESULT_IGNORED = "LATE_RESULT_IGNORED";

    private final NotificationRepositoryPort notificationRepositoryPort;
    private final DispatchHistoryRepositoryPort dispatchHistoryRepositoryPort;
    private final NotificationLogRepositoryPort notificationLogRepositoryPort;

    /**
     * TX-A. PENDING/RETRYING → PROCESSING 선점을 시도하고, 성공하면 발송에 필요한 스냅샷을 돌려준다.
     *
     * 반환된 엔티티는 커밋과 함께 detached 된다. 호출자는 이를 읽기 전용 스냅샷으로 다루고,
     * 상태 변경은 도메인 메서드로 계산한 뒤 {@link #finish}의 조건부 UPDATE로만 기록한다.
     *
     * @return 선점 성공 시 스냅샷. 0행(다른 스레드·인스턴스가 이미 선점)이면 empty — 에러가 아니다
     */
    @Transactional
    public Optional<Notification> claim(Long notificationId) {
        if (!notificationRepositoryPort.tryStartProcessing(notificationId)) {
            return Optional.empty();
        }
        Notification notification = notificationRepositoryPort.findById(notificationId)
                .orElseThrow(() -> new IllegalStateException("선점 직후 알림을 찾을 수 없음. id=" + notificationId));

        // CAS가 DB에서 이미 바꿨으므로 from은 알 수 없다 (PENDING 또는 RETRYING)
        notificationLogRepositoryPort.save(
                NotificationLog.of(notificationId, null, NotificationStatus.PROCESSING, "DISPATCH_START"));
        return Optional.of(notification);
    }

    /**
     * TX-B. 외부 발송 결과를 반영한다.
     *
     * <ul>
     *   <li>조건부 UPDATE {@code WHERE status='PROCESSING'}로 상태·횟수·백오프·사유를 기록</li>
     *   <li>{@code dispatch_history}는 반영 성공 여부와 무관하게 남긴다 — "시도가 있었다"는 사실이기 때문</li>
     *   <li>0행이면 Stuck 복구가 이미 PENDING으로 되돌린 것. 상태는 건드리지 않고 {@value #LATE_RESULT_IGNORED}로 기록</li>
     * </ul>
     *
     * 0행 + 발송 성공 조합은 중복 발송으로 이어질 수 있다. 이를 막는 불변식은
     * <b>"어댑터의 발송 타임아웃 &lt; Stuck 임계(10분)"</b>이다. (svc-dispatch 명세 참조)
     *
     * @param notification  도메인 전이(markSent/markRetrying/markFailed)가 끝난 detached 엔티티
     * @param attemptNumber 이번 시도 회차 (전이 전에 캡처한 값)
     * @param failureCode   실패 코드. 성공이면 null
     */
    @Transactional
    public void finish(Notification notification, int attemptNumber, String failureCode) {
        Long id = notification.getId();
        boolean applied = notificationRepositoryPort.tryFinishProcessing(notification);

        dispatchHistoryRepositoryPort.save(failureCode == null
                ? DispatchHistory.success(id, attemptNumber)
                : DispatchHistory.failure(id, attemptNumber, failureCode));

        if (applied) {
            notificationLogRepositoryPort.save(
                    NotificationLog.of(id, NotificationStatus.PROCESSING, notification.getStatus(), failureCode));
            return;
        }

        // 여기 도달했다면 Stuck 복구가 먼저 움직였다. 외부 발송이 임계(10분)보다 오래 걸렸다는 신호다.
        // "PROCESSING → (시도 결과)로 바꾸려 했으나 무시됨"을 남긴다. to_status는 NOT NULL이라 시도 결과를 넣고 reason으로 구분한다.
        notificationLogRepositoryPort.save(
                NotificationLog.of(id, NotificationStatus.PROCESSING, notification.getStatus(), LATE_RESULT_IGNORED));
        log.warn("[늦은 결과] 결과 반영 0행. 이미 PROCESSING이 아님(Stuck 복구 추정). id={}, 시도결과={}, code={}",
                id, notification.getStatus(), failureCode);
    }
}
