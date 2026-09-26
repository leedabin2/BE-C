package com.notification.infrastructure.scheduler;

import com.notification.application.port.out.NotificationRepositoryPort;
import com.notification.domain.NotificationOperationalSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 클러스터 전체 DB backlog를 한 번만 읽어 구조화 로그로 남긴다.
 *
 * 실행 스레드: scheduling-*. 정확한 감사 원장은 notification_log/dispatch_history이고,
 * 이 값은 S4/S5에서 backlog·지연·lease 이상을 비교하기 위한 운영 관측값이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationOperationsLogger {

    private final NotificationRepositoryPort notificationRepositoryPort;

    @Scheduled(fixedDelayString = "${notification.scheduler.metrics.fixed-delay-ms:60000}")
    @SchedulerLock(name = "notificationOperationsMetrics", lockAtMostFor = "55s", lockAtLeastFor = "10s")
    public void logSnapshot() {
        NotificationOperationalSnapshot snapshot = notificationRepositoryPort.getOperationalSnapshot();
        log.info("[운영지표] dueBacklog={}, oldestDueAgeSeconds={}, processing={}, expiredLease={}, retrying={}, failed={}",
                snapshot.dueBacklog(), snapshot.oldestDueAgeSeconds(), snapshot.processingCount(),
                snapshot.expiredLeaseCount(), snapshot.retryingCount(), snapshot.failedCount());
    }
}
