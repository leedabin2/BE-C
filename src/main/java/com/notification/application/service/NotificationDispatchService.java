package com.notification.application.service;
// PRD: F2-2, F2-3, F3-1, F5-1, F5-2, F5-3 → docs/prd/F2.md, docs/prd/F5.md

import com.notification.application.exception.ChannelFailureCode;
import com.notification.application.exception.NonRetryableChannelException;
import com.notification.application.exception.RetryableChannelException;
import com.notification.application.port.out.ChannelSenderPort;
import com.notification.application.port.out.NotificationLogRepositoryPort;
import com.notification.application.port.out.NotificationRepositoryPort;
import com.notification.domain.Notification;
import com.notification.domain.NotificationLog;
import com.notification.domain.NotificationStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 발송 조율자.
 *
 * {@link #dispatch}는 <b>트랜잭션이 없다.</b> DB 상태 전이는 {@link DispatchStateService}의 짧은 트랜잭션 2개가 맡고,
 * 외부 발송은 그 사이 트랜잭션 밖에서 일어난다. 외부 응답을 기다리는 동안 붙잡는 자원은 스레드 하나뿐이며
 * DB 커넥션·행 락은 잡지 않는다. (RULES §2-1, §2-7)
 *
 * 실행 스레드: {@code notification-*}(이벤트 핸들러) 또는 {@code scheduling-*}(스케줄러).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDispatchService {

    private final NotificationRepositoryPort notificationRepositoryPort;
    private final ChannelSenderPort channelSenderPort;
    private final NotificationLogRepositoryPort notificationLogRepositoryPort;
    private final DispatchStateService dispatchStateService;

    /**
     * PESSIMISTIC_WRITE(SKIP LOCKED)는 트랜잭션이 열려 있어야 동작한다.
     * 트랜잭션 커밋 시 DB 락 해제 → 이후 dispatch()가 각자 새 트랜잭션으로 실행된다.
     */
    @Transactional
    public List<Long> fetchPendingIds(int limit) {
        return notificationRepositoryPort.findPendingWithLock(limit)
                .stream().map(Notification::getId)
                .toList();
    }

    /**
     * Stuck PROCESSING 알림을 PENDING으로 복구한다.
     */
    @Transactional
    public void recoverStuck(int thresholdMinutes) {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(thresholdMinutes);
        List<Notification> stuckList = notificationRepositoryPort.findStuckProcessing(thresholdMinutes);
        if (stuckList.isEmpty()) return;

        log.warn("[복구] Stuck PROCESSING 알림 {}건 감지", stuckList.size());
        stuckList.forEach(n -> {
            // CAS UPDATE: status가 이미 SENT/FAILED로 바뀐 경우 0행 업데이트로 안전 스킵
            if (notificationRepositoryPort.tryRecoverStuck(n.getId(), threshold)) {
                notificationLogRepositoryPort.save(
                        NotificationLog.of(n.getId(), NotificationStatus.PROCESSING, NotificationStatus.PENDING, "STUCK_RECOVERY"));
                log.warn("[복구] PENDING 복구 완료. id={}", n.getId());
            } else {
                log.debug("[복구] 이미 처리 완료 상태. 스킵. id={}", n.getId());
            }
        });
    }

    /**
     * 단일 알림 발송. <b>이 메서드에는 트랜잭션이 없다.</b>
     *
     * <pre>
     * TX-A  claim   : 선점 실패(0행)면 여기서 끝. 다른 스레드·인스턴스가 가져간 정상 스킵
     *  ---  send    : 외부 I/O. 예외를 재시도 가능/불가로 분류해 도메인 전이로 계산만 한다 (DB 접근 없음)
     * TX-B  finish  : 조건부 UPDATE로 결과 반영 + 이력
     * </pre>
     *
     * 이 지점에서 죽으면: claim 커밋 후 ~ finish 커밋 전에는 PROCESSING으로 남고, Stuck 복구(5분 주기, 10분 임계)가 회수한다.
     * finish의 DB 예외는 호출자(스케줄러·이벤트 핸들러)로 전파된다. 삼키지 않는다.
     */
    public void dispatch(Long notificationId) {
        Optional<Notification> claimed = dispatchStateService.claim(notificationId);
        if (claimed.isEmpty()) {
            log.debug("이미 처리 중인 알림 스킵. id={}", notificationId);
            return;
        }
        Notification notification = claimed.get();

        // 이번 시도 회차 = 현재 retryCount + 1 (markRetrying 전에 캡처)
        int attemptNumber = notification.getRetryCount() + 1;
        String failureCode = null;

        try {
            channelSenderPort.send(notification);
            notification.markSent();
            log.info("알림 발송 성공. id={}, channel={}", notificationId, notification.getChannel());

        } catch (RetryableChannelException e) {
            failureCode = e.getFailureCode().name();
            notification.markRetrying(failureCode);
            log.warn("재시도 가능 발송 실패. id={}, code={}, retryCount={}",
                    notificationId, failureCode, notification.getRetryCount());

        } catch (NonRetryableChannelException e) {
            failureCode = e.getFailureCode().name();
            notification.markFailed(failureCode);
            log.error("재시도 불가 발송 실패. id={}, code={}", notificationId, failureCode);

        } catch (Exception e) {
            // 원인 불명은 보수적으로 재시도. 예외 원문은 DB에 넣지 않고 로그에만 (RULES §5-4, §5-5)
            failureCode = ChannelFailureCode.CHANNEL_UNAVAILABLE.name();
            notification.markRetrying(failureCode);
            log.error("예상치 못한 발송 오류. id={}", notificationId, e);
        }

        dispatchStateService.finish(notification, attemptNumber, failureCode);
    }
}
