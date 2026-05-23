package com.notification.application.service;

import com.notification.application.exception.ChannelFailureCode;
import com.notification.application.exception.NonRetryableChannelException;
import com.notification.application.exception.RetryableChannelException;
import com.notification.application.port.out.ChannelSenderPort;
import com.notification.application.port.out.NotificationRepositoryPort;
import com.notification.domain.Notification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 알림 발송 처리 서비스.
 *
 * CAS(tryStartProcessing)로 이벤트 핸들러와 스케줄러 간 경합을 방지하고
 * 발송 결과에 따라 상태를 전이한다.
 * 스케줄러와 이벤트 핸들러 양쪽에서 재사용된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDispatchService {

    private final NotificationRepositoryPort notificationRepositoryPort;
    private final ChannelSenderPort channelSenderPort;

    /**
     * 단일 알림 발송을 처리한다.
     *
     * CAS로 PROCESSING 전환에 성공한 스레드만 실제 발송을 수행한다.
     * 트랜잭션이 외부 발송 호출을 감싸므로, 실제 운영에서는 트랜잭션 분리를 권장한다.
     *
     * @param notificationId 발송할 알림 ID
     */
    /**
     * 트랜잭션 안에서 PENDING/RETRYING 알림을 조회하고 ID만 반환한다.
     *
     * PESSIMISTIC_WRITE(SKIP LOCKED)는 트랜잭션이 열려 있어야 동작한다.
     * 이 메서드가 반환되면 트랜잭션이 커밋되어 DB 락이 해제되고,
     * 이후 dispatch()가 각자 새 트랜잭션으로 실행된다.
     */
    @Transactional
    public List<Long> fetchPendingIds(int limit) {
        return notificationRepositoryPort.findPendingWithLock(limit)
                .stream().map(Notification::getId)
                .toList();
    }

    /**
     * Stuck PROCESSING 알림을 PENDING으로 복구한다.
     * 서버 재시작 등으로 PROCESSING 상태에서 멈춘 알림을 감지해 재처리 가능 상태로 되돌린다.
     * 복구 후 재처리 스케줄러가 다음 사이클에 dispatch()를 호출한다.
     */
    @Transactional
    public void recoverStuck(int thresholdMinutes) {
        List<Notification> stuckList = notificationRepositoryPort.findStuckProcessing(thresholdMinutes);
        if (stuckList.isEmpty()) return;

        log.warn("[복구] Stuck PROCESSING 알림 {}건 감지", stuckList.size());
        stuckList.forEach(n -> {
            n.resetForManualRetry();
            notificationRepositoryPort.save(n);
            log.warn("[복구] PENDING 복구 완료. id={}", n.getId());
        });
    }

    @Transactional
    public void dispatch(Long notificationId) {
        if (!notificationRepositoryPort.tryStartProcessing(notificationId)) {
            log.debug("이미 처리 중인 알림 스킵. id={}", notificationId);
            return;
        }

        Notification notification = notificationRepositoryPort.findById(notificationId)
                .orElseThrow(() -> new IllegalStateException("알림을 찾을 수 없음. id=" + notificationId));

        try {
            channelSenderPort.send(notification);
            notification.markSent();
            log.info("알림 발송 성공. id={}, channel={}", notificationId, notification.getChannel());
        } catch (RetryableChannelException e) {
            notification.markRetrying(e.getFailureCode().name());
            log.warn("재시도 가능 발송 실패. id={}, code={}, retryCount={}",
                    notificationId, e.getFailureCode(), notification.getRetryCount());
        } catch (NonRetryableChannelException e) {
            notification.markFailed(e.getFailureCode().name());
            log.error("재시도 불가 발송 실패. id={}, code={}", notificationId, e.getFailureCode());
        } catch (Exception e) {
            // 예상치 못한 예외는 Retryable로 처리
            notification.markRetrying(ChannelFailureCode.CHANNEL_UNAVAILABLE.name());
            log.error("예상치 못한 발송 오류. id={}", notificationId, e);
        } finally {
            notificationRepositoryPort.save(notification);
        }
    }
}
