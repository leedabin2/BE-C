package com.notification.resilience;

import com.notification.application.port.out.NotificationRepositoryPort;
import com.notification.application.service.NotificationDispatchService;
import com.notification.domain.NotificationStatus;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-b. Stuck 회수의 <b>실행당 예산</b>을 검증한다.
 *
 * <p>회수 주기(30초)는 "만료를 얼마나 빨리 발견하는가"만 바꾼다. 발송 중인 작업을 빼앗지 않는 근거는
 * 주기가 아니라 {@code lease_until <= UTC_TIMESTAMP(6)} 조건이고, lease 자체는
 * {@code DispatchInvariantValidator}(최악 소요 215초 × 2 ≤ 임계 600초)가 지킨다.
 * 여기서 확인하는 것은 <b>한 번의 실행이 얼마나 따라잡고, 어디서 멈추는가</b>다.
 *
 * <pre>
 * 100건 × 최대 5 batch  → 한 실행에서 최대 500건. 그 이상은 다음 트리거로 넘긴다
 * batch마다 별도 TX     → 250건을 한 트랜잭션으로 묶어 DB를 오래 점유하지 않는다
 * token 없는 이상 행    → 회수 대상 조회를 가로막지 않고 별도 경보로 분리한다
 * </pre>
 */
@TestPropertySource(properties = {
        "notification.scheduler.retry.fixed-delay-ms=3600000",
        "notification.scheduler.stuck.fixed-delay-ms=3600000",
        "notification.scheduler.metrics.fixed-delay-ms=3600000",
        "notification.scheduler.stuck.batch-size=100",
        "notification.scheduler.stuck.max-batches=5",
        "notification.scheduler.stuck.time-budget-ms=60000"
})
class StuckRecoveryBoundedIntegrationTest extends AbstractIntegrationTest {

    @Autowired NotificationDispatchService dispatch;
    @Autowired NotificationJpaRepository notifications;
    @Autowired NotificationLogJpaRepository logs;
    @Autowired DispatchHistoryJpaRepository histories;
    @Autowired NotificationRepositoryPort repository;
    @Autowired TestChannelSenderAdapter sender;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        histories.deleteAll();
        logs.deleteAll();
        notifications.deleteAll();
        sender.reset();
    }

    /**
     * 만료된 PROCESSING 행을 직접 만든다. lease 만료 시각을 조금씩 다르게 줘서 "오래된 순" 회수 순서를 만든다.
     *
     * @param withToken false면 소유자 token이 없는 이상 데이터. 회수 CAS가 성립할 수 없는 행이다.
     */
    private void insertExpiredProcessing(String prefix, int count, boolean withToken, int leaseAgeBaseSeconds) {
        List<Object[]> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rows.add(new Object[]{prefix + "-" + i, prefix + "-" + i,
                    withToken ? "token-" + prefix + "-" + i : null,
                    -(leaseAgeBaseSeconds + i)});
        }
        jdbc.batchUpdate("""
                INSERT INTO notification
                  (idempotency_key, receiver_id, notification_type, channel, event_id,
                   status, retry_count, is_read, created_at, updated_at,
                   processing_token, claimed_at, lease_until)
                VALUES (?, 1, 'PAYMENT_CONFIRMED', 'EMAIL', ?,
                   'PROCESSING', 0, false, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6),
                   ?, TIMESTAMPADD(MINUTE, -11, UTC_TIMESTAMP(6)), TIMESTAMPADD(SECOND, ?, UTC_TIMESTAMP(6)))
                """, rows);
    }

    private long countByStatus(NotificationStatus status) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE status = ?", Long.class, status.name());
    }

    @Test
    @DisplayName("만료 250건은 한 번의 실행에서 100+100+50으로 모두 회수된다 (같은 행의 횟수 중복 증가 없음)")
    void 만료_250건을_한_실행에서_소진한다() {
        insertExpiredProcessing("bulk", 250, true, 60);

        NotificationDispatchService.StuckRecoveryResult result = dispatch.recoverStuck();

        assertThat(result.selected()).isEqualTo(250);
        assertThat(result.recovered()).isEqualTo(250);
        assertThat(countByStatus(NotificationStatus.RETRYING)).isEqualTo(250);
        assertThat(countByStatus(NotificationStatus.PROCESSING)).isZero();
        assertThat(repository.getOperationalSnapshot().expiredLeaseCount()).isZero();
        // 한 행이 두 batch에 걸쳐 두 번 회수되면 실패 예산이 두 배로 타버린다
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE retry_count <> 1", Long.class)).isZero();
        assertThat(logs.count()).as("회수 1건당 상태 이력 1건").isEqualTo(250);
        assertThat(sender.getSendCallCount()).as("회수는 발송하지 않는다").isZero();
    }

    @Test
    @DisplayName("실행당 상한(100×5=500)을 넘는 잔량은 다음 트리거가 이어받는다")
    void 실행당_상한을_넘으면_다음_트리거로_넘긴다() {
        insertExpiredProcessing("flood", 600, true, 60);

        NotificationDispatchService.StuckRecoveryResult first = dispatch.recoverStuck();

        assertThat(first.recovered()).as("무한 반복 없이 실행당 상한에서 멈춘다").isEqualTo(500);
        assertThat(countByStatus(NotificationStatus.PROCESSING)).isEqualTo(100);

        NotificationDispatchService.StuckRecoveryResult second = dispatch.recoverStuck();

        assertThat(second.recovered()).isEqualTo(100);
        assertThat(countByStatus(NotificationStatus.PROCESSING)).isZero();
        assertThat(countByStatus(NotificationStatus.RETRYING)).isEqualTo(600);
    }

    @Test
    @DisplayName("token 없는 이상 행이 선두에 쌓여도 뒤의 정상 만료 행이 굶지 않는다")
    void token_없는_행이_정상_회수를_막지_않는다() {
        // 이상 행이 더 오래 전에 만료돼 "오래된 순" 정렬의 앞자리를 전부 차지한 상황
        insertExpiredProcessing("orphan", 100, false, 3600);
        insertExpiredProcessing("healthy", 3, true, 60);

        NotificationDispatchService.StuckRecoveryResult result = dispatch.recoverStuck();

        assertThat(result.recovered()).as("뒤에 있던 정상 만료 행 3건은 이번 실행에서 회수돼야 한다").isEqualTo(3);
        assertThat(countByStatus(NotificationStatus.RETRYING)).isEqualTo(3);
        assertThat(result.tokenMissing()).as("이상 데이터는 삼키지 않고 경보용으로 센다").isEqualTo(100);
        // 임의 token을 발급해 다시 발송하지 않는다. 사람이 판단할 때까지 그대로 둔다
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE status = 'PROCESSING' AND processing_token IS NULL",
                Long.class)).isEqualTo(100);
    }

    @Test
    @DisplayName("ShedLock이 겹쳐 두 노드가 동시에 회수해도 행마다 정확히 한 번만 반영된다")
    void 동시_회수에도_행마다_한_번만_반영된다() throws Exception {
        insertExpiredProcessing("race", 40, true, 60);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        List<java.util.concurrent.Future<NotificationDispatchService.StuckRecoveryResult>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return dispatch.recoverStuck();
            }));
        }
        start.countDown();
        int totalRecovered = 0;
        for (var f : futures) totalRecovered += f.get(60, TimeUnit.SECONDS).recovered();
        pool.shutdownNow();

        assertThat(totalRecovered).as("두 실행의 회수 합은 행 수를 넘을 수 없다").isEqualTo(40);
        assertThat(countByStatus(NotificationStatus.RETRYING)).isEqualTo(40);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE retry_count <> 1", Long.class)).isZero();
        assertThat(logs.count()).isEqualTo(40);
    }
}
