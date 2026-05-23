package com.notification.infrastructure.scheduler;

import com.notification.application.port.out.NotificationRepositoryPort;
import com.notification.application.service.NotificationDispatchService;
import com.notification.domain.Notification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 알림 재처리 및 Stuck 복구 스케줄러.
 *
 * 두 스케줄러는 역할이 다르다:
 * - retryScheduler: 발송 실패(RETRYING)나 미처리(PENDING) 알림을 재발송
 * - stuckRecoveryScheduler: 서버 장애로 PROCESSING에 갇힌 알림을 PENDING으로 복구
 *
 * ShedLock이 다중 인스턴스 환경에서 한 인스턴스만 실행하도록 보장한다 (1차 방어).
 * findPendingWithLock의 SKIP LOCKED가 스레드 간 중복 행 처리를 방지한다 (2차 방어).
 * dispatch() 내부의 CAS가 이벤트 핸들러와의 최종 경합을 방지한다 (3차 방어).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationScheduler {

    private final NotificationRepositoryPort notificationRepositoryPort;
    private final NotificationDispatchService dispatchService;

    @Value("${notification.scheduler.retry.batch-size:100}")
    private int retryBatchSize;

    @Value("${notification.scheduler.stuck.threshold-minutes:10}")
    private int stuckThresholdMinutes;

    /**
     * PENDING/RETRYING 알림 재처리.
     *
     * fixedDelay: 이전 실행 완료 후 N ms 뒤에 실행 (cron과 달리 실행 시간이 주기에 포함되지 않음).
     * lockAtMostFor: 서버가 죽어도 이 시간 후엔 락이 만료되어 다른 인스턴스가 실행 가능.
     * lockAtLeastFor: 너무 빨리 끝나도 이 시간은 락을 유지해 즉시 재실행을 방지.
     */
    @Scheduled(fixedDelayString = "${notification.scheduler.retry.fixed-delay-ms:60000}")
    @SchedulerLock(name = "retryScheduler", lockAtMostFor = "55s", lockAtLeastFor = "10s")
    public void retryScheduler() {
        List<Notification> pending = notificationRepositoryPort.findPendingWithLock(retryBatchSize);
        if (pending.isEmpty()) return;

        log.info("[재처리] 대상 {}건 조회", pending.size());
        for (Notification n : pending) {
            try {
                dispatchService.dispatch(n.getId());
            } catch (Exception e) {
                // 개별 알림 오류가 전체 배치를 중단하지 않도록 catch
                log.error("[재처리] dispatch 오류. id={}", n.getId(), e);
            }
        }
    }

    /**
     * Stuck PROCESSING 알림 복구.
     *
     * 정상 발송은 최대 수십 초 내 완료된다.
     * threshold-minutes(기본 10분) 이상 PROCESSING 상태이면 비정상으로 판정하고 PENDING으로 되돌린다.
     * 복구된 알림은 retryScheduler 다음 사이클에서 재처리된다.
     */
    @Scheduled(fixedDelayString = "${notification.scheduler.stuck.fixed-delay-ms:300000}")
    @SchedulerLock(name = "stuckRecoveryScheduler", lockAtMostFor = "4m55s", lockAtLeastFor = "30s")
    public void stuckRecoveryScheduler() {
        dispatchService.recoverStuck(stuckThresholdMinutes);
    }
}
