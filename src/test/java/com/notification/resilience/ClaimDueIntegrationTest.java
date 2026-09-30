package com.notification.resilience;

import com.notification.application.service.NotificationDispatchService;
import com.notification.application.service.DispatchStateService;
import com.notification.application.port.out.NotificationRepositoryPort;
import com.notification.domain.Notification;
import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationStatus;
import com.notification.domain.NotificationType;
import com.notification.infrastructure.repository.DispatchHistoryJpaRepository;
import com.notification.infrastructure.repository.NotificationJpaRepository;
import com.notification.infrastructure.repository.NotificationLogJpaRepository;
import com.notification.support.AbstractIntegrationTest;
import com.notification.support.TestChannelSenderAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 MySQL의 조건부 UPDATE와 커밋을 검증한다. HTTP 이벤트 없이 큐의 실행 순서를 통제한다. */
@TestPropertySource(properties = {
        "notification.scheduler.retry.fixed-delay-ms=3600000",
        "notification.scheduler.stuck.fixed-delay-ms=3600000",
        "notification.scheduler.stuck.batch-size=2",
        // 이 테스트의 주제는 "batch 상한이 회수를 끊고 다음 트리거가 이어받는다"이므로 실행당 1 batch로 고정한다.
        // 실행당 여러 batch를 도는 P0-b 동작은 StuckRecoveryBoundedIntegrationTest가 검증한다.
        "notification.scheduler.stuck.max-batches=1",
        "notification.scheduler.metrics.fixed-delay-ms=3600000"
})
class ClaimDueIntegrationTest extends AbstractIntegrationTest {
    @Autowired NotificationDispatchService dispatch;
    @Autowired NotificationJpaRepository notifications;
    @Autowired DispatchHistoryJpaRepository histories;
    @Autowired NotificationLogJpaRepository logs;
    @Autowired TestChannelSenderAdapter sender;
    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationRepositoryPort repository;
    @Autowired DispatchStateService state;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void reset() {
        histories.deleteAll();
        logs.deleteAll();
        notifications.deleteAll();
        sender.reset();
    }

    private Long create(String key) {
        return notifications.saveAndFlush(Notification.builder()
                .receiverId(1L).notificationType(NotificationType.PAYMENT_CONFIRMED)
                .channel(NotificationChannel.EMAIL).eventId(key).idempotencyKey(key).build()).getId();
    }

    @Test
    @DisplayName("DB UTC 기준으로 실패 후 1~2분 예약한다 (JVM 기본 시간대와 무관)")
    void retrySchedule_usesDatabaseUtcClock() {
        Long id = create("db-clock");
        var before = repository.currentTime();
        sender.setFailCount(1);
        dispatch.dispatch(id);
        var after = repository.currentTime();
        var retryAt = notifications.findById(id).orElseThrow().getNextRetryAt();
        assertThat(retryAt).isBetween(before.plusMinutes(1), after.plusMinutes(2));
        var rawRetryAt = jdbc.queryForObject("SELECT next_retry_at FROM notification WHERE id = ?",
                (rs, rowNum) -> rs.getObject(1, java.time.LocalDateTime.class), id);
        assertThat(rawRetryAt).as("JPA 왕복뿐 아니라 실제 DB에 저장된 UTC 시각도 일치해야 함").isEqualTo(retryAt);
        assertThat(jdbc.queryForObject("SELECT @@session.time_zone", String.class)).isEqualTo("+00:00");
        assertThat(after).isCloseTo(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC),
                org.assertj.core.api.Assertions.within(5, java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("고정 DB 시각 경계: 1마이크로초 미래는 제외, 정확히 같은 시각부터 선점")
    void exactDatabaseBoundary_isInclusive() {
        Long id = create("exact-boundary");
        // 같은 TX/connection의 DB 시계만 고정한다. 앱 시계나 실제 대기에 기대지 않는다.
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            jdbc.execute("SET timestamp = 1800000000.123456");
            try {
                jdbc.update("UPDATE notification SET scheduled_at = TIMESTAMPADD(MICROSECOND, 1, NOW(6)) WHERE id = ?", id);
                assertThat(dispatch.fetchPendingIds(10)).doesNotContain(id);
                assertThat(state.claim(id)).isEmpty();
                jdbc.update("UPDATE notification SET scheduled_at = NOW(6), next_retry_at = NOW(6) WHERE id = ?", id);
                assertThat(dispatch.fetchPendingIds(10)).contains(id);
                assertThat(state.claim(id)).isPresent();
            } finally {
                jdbc.execute("SET timestamp = 0"); // 풀에 돌려주기 전에 반드시 원복
            }
        });
        assertThat(sender.getSendCallCount()).isZero(); // 선점만 검증, TX 안에서 외부 발송하지 않음
    }

    @Test
    @DisplayName("오래 대기한 배치 ID는 다른 워커가 만든 미래 재시도 예약을 우회할 수 없다")
    void staleQueuedId_doesNotBypassNewRetrySchedule() {
        Long id = create("stale-queue");
        List<Long> queuedIds = dispatch.fetchPendingIds(10); // 이 ID를 로컬 큐에 보관한 상황
        assertThat(queuedIds).containsExactly(id);
        sender.setFailCount(1);
        dispatch.dispatch(id); // 즉시 워커가 먼저 실패하고 미래 재시도를 예약
        var retryAt = notifications.findById(id).orElseThrow().getNextRetryAt();
        assertThat(notifications.findById(id).orElseThrow().getStatus()).isEqualTo(NotificationStatus.RETRYING);

        queuedIds.forEach(dispatch::dispatch); // 뒤늦게 배치 워커가 이전 ID 실행

        assertThat(sender.getSendCallCount()).as("아직 예약 전이므로 첫 실패 이외의 외부 호출은 없어야 함").isEqualTo(1);
        var pending = notifications.findById(id).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(pending.getRetryCount()).isEqualTo(1);
        assertThat(pending.getNextRetryAt()).isEqualTo(retryAt);
        assertThat(histories.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("미래 예약은 직접 dispatch해도 호출 0회, 예약 시각 도달 뒤에는 발송")
    void futureSchedule_blocksDirectDispatch_untilDue() {
        Long id = create("future-schedule");
        jdbc.update("UPDATE notification SET scheduled_at = DATE_ADD(NOW(6), INTERVAL 1 HOUR) WHERE id = ?", id);
        assertThat(dispatch.fetchPendingIds(10)).doesNotContain(id);
        dispatch.dispatch(id);
        assertThat(sender.getSendCallCount()).isZero();
        assertThat(histories.count()).isZero();
        assertThat(notifications.findById(id).orElseThrow().getProcessingToken()).isNull();

        jdbc.update("UPDATE notification SET scheduled_at = NOW(6) WHERE id = ?", id);
        assertThat(dispatch.fetchPendingIds(10)).contains(id);
        dispatch.dispatch(id);
        assertThat(sender.getSendCallCount()).isEqualTo(1);
        assertThat(notifications.findById(id).orElseThrow().getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    @DisplayName("예약 시각이 지났어도 미래 재시도 시각 전에는 호출하지 않는다")
    void bothScheduleConditions_mustBeDue() {
        Long id = create("both-times");
        jdbc.update("UPDATE notification SET status = 'RETRYING', retry_count = 1, " +
                "scheduled_at = DATE_SUB(NOW(6), INTERVAL 1 HOUR), " +
                "next_retry_at = DATE_ADD(NOW(6), INTERVAL 1 HOUR) WHERE id = ?", id);
        dispatch.dispatch(id);
        assertThat(sender.getSendCallCount()).isZero();
        assertThat(histories.count()).isZero();
        assertThat(dispatch.fetchPendingIds(10)).doesNotContain(id);
    }

    @Test
    @DisplayName("재시도 due 도달 뒤 12개 실행자가 경합해도 실제 호출은 1회다")
    void dueRetry_concurrentDispatch_sendsOnce() throws Exception {
        Long id = create("due-race");
        jdbc.update("UPDATE notification SET status = 'RETRYING', retry_count = 1, " +
                "scheduled_at = NOW(6), next_retry_at = NOW(6) WHERE id = ?", id);
        sender.setSendDelayMs(100);
        var pool = Executors.newFixedThreadPool(12);
        var start = new CountDownLatch(1);
        try {
            var futures = java.util.stream.IntStream.range(0, 12).mapToObj(i -> pool.submit(() -> {
                start.await();
                dispatch.dispatch(id);
                return null;
            })).toList();
            start.countDown();
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(sender.getSendCallCount()).isEqualTo(1);
        assertThat(histories.count()).isEqualTo(1);
        assertThat(notifications.findById(id).orElseThrow().getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    @DisplayName("서버 A/B 동시 batch claim은 겹치지 않고, SKIP LOCKED가 건너뛴 행은 다음 scan이 회수한다")
    void twoSchedulers_claimDistinctDueBatches() throws Exception {
        for (int i = 0; i < 20; i++) create("batch-claim-" + i);

        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var futures = java.util.stream.IntStream.range(0, 2).mapToObj(i -> pool.submit(() -> {
                start.await();
                return state.claimDueBatch(10);
            })).toList();
            start.countDown();

            List<DispatchStateService.ClaimedNotification> all = new java.util.ArrayList<>();
            for (var future : futures) all.addAll(future.get(20, TimeUnit.SECONDS));

            Set<Long> claimedIds = all.stream().map(DispatchStateService.ClaimedNotification::notificationId)
                    .collect(java.util.stream.Collectors.toSet());
            Set<String> tokens = all.stream().map(DispatchStateService.ClaimedNotification::processingToken)
                    .collect(java.util.stream.Collectors.toSet());
            // InnoDB의 SKIP LOCKED는 앞쪽 next-key lock을 건너뛰며 limit보다 적게 반환할 수 있다.
            // 핵심은 같은 행을 두 scheduler가 claim하지 않는 것이며, 남은 due 행은 즉시 다음 scan에서 가져간다.
            assertThat(all).isNotEmpty();
            assertThat(claimedIds).hasSize(all.size());
            assertThat(tokens).hasSize(all.size());
            assertThat(sender.getSendCallCount()).as("claim은 DB 소유권만 얻고 외부 I/O를 하지 않는다").isZero();
            List<DispatchStateService.ClaimedNotification> nextScan = state.claimDueBatch(20);
            Set<Long> allClaimedIds = new java.util.HashSet<>(claimedIds);
            allClaimedIds.addAll(nextScan.stream().map(DispatchStateService.ClaimedNotification::notificationId).toList());
            assertThat(allClaimedIds).hasSize(20);
            assertThat(notifications.countByStatusIn(List.of(NotificationStatus.PROCESSING))).isEqualTo(20);
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("batch claim된 작업은 재claim 없이 발송하고, 큐 거절 보상은 원래 RETRYING으로 되돌린다")
    void batchClaim_dispatchAndRejectedSubmissionRecovery() {
        Long ready = create("batch-dispatch");
        Long retrying = create("batch-release");
        jdbc.update("UPDATE notification SET status = 'RETRYING', retry_count = 1, next_retry_at = NOW(6) WHERE id = ?", retrying);

        List<DispatchStateService.ClaimedNotification> claimed = state.claimDueBatch(10);
        var readyWork = claimed.stream().filter(work -> work.notificationId().equals(ready)).findFirst().orElseThrow();
        var retryWork = claimed.stream().filter(work -> work.notificationId().equals(retrying)).findFirst().orElseThrow();

        dispatch.dispatchClaimed(readyWork);
        state.releaseBatchClaim(retryWork);

        assertThat(sender.getSendCallCount()).isEqualTo(1);
        assertThat(histories.count()).isEqualTo(1);
        assertThat(notifications.findById(ready).orElseThrow().getStatus()).isEqualTo(NotificationStatus.SENT);
        var restored = notifications.findById(retrying).orElseThrow();
        assertThat(restored.getStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(restored.getRetryCount()).isEqualTo(1);
        assertThat(restored.getProcessingToken()).isNull();
        assertThat(restored.getLeaseUntil()).isNull();
    }

    @Test
    @DisplayName("batch worker 시작 전 token 소유권이 바뀌면 옛 WorkItem은 외부 발송을 하지 않는다")
    void batchWorker_skipsWhenOwnershipChangedBeforeItStarts() {
        Long id = create("batch-owner-changed");
        DispatchStateService.ClaimedNotification workItem = state.claimDueBatch(1).get(0);

        // A의 runnable이 큐에서 기다리는 동안 lease 회수·B 재선점이 끝난 상황을 직접 만든다.
        jdbc.update("UPDATE notification SET processing_token = ? WHERE id = ?", java.util.UUID.randomUUID().toString(), id);
        dispatch.dispatchClaimed(workItem);

        assertThat(sender.getSendCallCount()).isZero();
        assertThat(histories.count()).isZero();
        assertThat(notifications.findById(id).orElseThrow().getStatus()).isEqualTo(NotificationStatus.PROCESSING);
    }

    @Test
    @DisplayName("Stuck 회수는 가장 오래 만료된 2건만 처리하고, 운영 snapshot은 남은 lease backlog를 보인다")
    void stuckRecovery_isBoundedAndOperationalSnapshotShowsRemainingBacklog() {
        Long oldest = create("stuck-batch-oldest");
        Long middle = create("stuck-batch-middle");
        Long newest = create("stuck-batch-newest");
        markExpiredProcessing(oldest, 30);
        markExpiredProcessing(middle, 20);
        markExpiredProcessing(newest, 10);

        NotificationDispatchService.StuckRecoveryResult first = dispatch.recoverStuck();

        assertThat(first).isEqualTo(new NotificationDispatchService.StuckRecoveryResult(
                2, 2, 0, 0, 1, NotificationDispatchService.StopReason.MAX_BATCHES));
        assertThat(notifications.findById(oldest).orElseThrow().getStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(notifications.findById(middle).orElseThrow().getStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(notifications.findById(newest).orElseThrow().getStatus()).isEqualTo(NotificationStatus.PROCESSING);
        var afterFirst = repository.getOperationalSnapshot();
        assertThat(afterFirst.processingCount()).isEqualTo(1);
        assertThat(afterFirst.expiredLeaseCount()).isEqualTo(1);
        assertThat(afterFirst.retryingCount()).isEqualTo(2);

        NotificationDispatchService.StuckRecoveryResult second = dispatch.recoverStuck();

        assertThat(second).isEqualTo(new NotificationDispatchService.StuckRecoveryResult(
                1, 1, 0, 0, 1, NotificationDispatchService.StopReason.EXHAUSTED));
        var afterSecond = repository.getOperationalSnapshot();
        assertThat(afterSecond.processingCount()).isZero();
        assertThat(afterSecond.expiredLeaseCount()).isZero();
        assertThat(afterSecond.retryingCount()).isEqualTo(3);
    }

    private void markExpiredProcessing(Long id, int minutesAgo) {
        jdbc.update("UPDATE notification SET status = 'PROCESSING', processing_token = ?, " +
                        "claimed_at = DATE_SUB(NOW(6), INTERVAL ? MINUTE), " +
                        "lease_until = DATE_SUB(NOW(6), INTERVAL ? MINUTE) WHERE id = ?",
                java.util.UUID.randomUUID().toString(), minutesAgo + 1, minutesAgo, id);
    }
}
