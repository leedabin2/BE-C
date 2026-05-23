package com.notification.application.service;

import com.notification.application.exception.ChannelFailureCode;
import com.notification.application.exception.NonRetryableChannelException;
import com.notification.application.exception.RetryableChannelException;
import com.notification.application.port.out.ChannelSenderPort;
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

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDispatchService {

    private final NotificationRepositoryPort notificationRepositoryPort;
    private final ChannelSenderPort channelSenderPort;
    private final DispatchHistoryRepositoryPort dispatchHistoryRepositoryPort;
    private final NotificationLogRepositoryPort notificationLogRepositoryPort;

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
        List<Notification> stuckList = notificationRepositoryPort.findStuckProcessing(thresholdMinutes);
        if (stuckList.isEmpty()) return;

        log.warn("[복구] Stuck PROCESSING 알림 {}건 감지", stuckList.size());
        stuckList.forEach(n -> {
            NotificationStatus prev = n.getStatus();
            n.resetForManualRetry();
            notificationRepositoryPort.save(n);
            notificationLogRepositoryPort.save(
                    NotificationLog.of(n.getId(), prev, NotificationStatus.PENDING, "STUCK_RECOVERY"));
            log.warn("[복구] PENDING 복구 완료. id={}", n.getId());
        });
    }

    /**
     * 단일 알림 발송을 처리한다.
     *
     * CAS로 PROCESSING 전환에 성공한 스레드만 실제 발송을 수행한다.
     * 발송 결과마다 DispatchHistory와 NotificationLog를 기록한다.
     */
    @Transactional
    public void dispatch(Long notificationId) {
        if (!notificationRepositoryPort.tryStartProcessing(notificationId)) {
            log.debug("이미 처리 중인 알림 스킵. id={}", notificationId);
            return;
        }

        Notification notification = notificationRepositoryPort.findById(notificationId)
                .orElseThrow(() -> new IllegalStateException("알림을 찾을 수 없음. id=" + notificationId));

        // PROCESSING 전이 로그 (CAS가 DB에서 이미 변경했으므로 이전 상태 추정)
        notificationLogRepositoryPort.save(
                NotificationLog.of(notificationId, null, NotificationStatus.PROCESSING, "DISPATCH_START"));

        // 이번 시도 회차 = 현재 retryCount + 1 (markRetrying 전에 캡처)
        int attemptNumber = notification.getRetryCount() + 1;

        try {
            channelSenderPort.send(notification);
            notification.markSent();
            dispatchHistoryRepositoryPort.save(DispatchHistory.success(notificationId, attemptNumber));
            notificationLogRepositoryPort.save(
                    NotificationLog.of(notificationId, NotificationStatus.PROCESSING, NotificationStatus.SENT, null));
            log.info("알림 발송 성공. id={}, channel={}", notificationId, notification.getChannel());

        } catch (RetryableChannelException e) {
            notification.markRetrying(e.getFailureCode().name());
            dispatchHistoryRepositoryPort.save(
                    DispatchHistory.failure(notificationId, attemptNumber, e.getFailureCode().name()));
            notificationLogRepositoryPort.save(
                    NotificationLog.of(notificationId, NotificationStatus.PROCESSING,
                            notification.getStatus(), e.getFailureCode().name()));
            log.warn("재시도 가능 발송 실패. id={}, code={}, retryCount={}",
                    notificationId, e.getFailureCode(), notification.getRetryCount());

        } catch (NonRetryableChannelException e) {
            notification.markFailed(e.getFailureCode().name());
            dispatchHistoryRepositoryPort.save(
                    DispatchHistory.failure(notificationId, attemptNumber, e.getFailureCode().name()));
            notificationLogRepositoryPort.save(
                    NotificationLog.of(notificationId, NotificationStatus.PROCESSING,
                            NotificationStatus.FAILED, e.getFailureCode().name()));
            log.error("재시도 불가 발송 실패. id={}, code={}", notificationId, e.getFailureCode());

        } catch (Exception e) {
            notification.markRetrying(ChannelFailureCode.CHANNEL_UNAVAILABLE.name());
            dispatchHistoryRepositoryPort.save(
                    DispatchHistory.failure(notificationId, attemptNumber, ChannelFailureCode.CHANNEL_UNAVAILABLE.name()));
            notificationLogRepositoryPort.save(
                    NotificationLog.of(notificationId, NotificationStatus.PROCESSING,
                            notification.getStatus(), ChannelFailureCode.CHANNEL_UNAVAILABLE.name()));
            log.error("예상치 못한 발송 오류. id={}", notificationId, e);

        } finally {
            notificationRepositoryPort.save(notification);
        }
    }
}
