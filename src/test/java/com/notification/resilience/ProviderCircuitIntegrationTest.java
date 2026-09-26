package com.notification.resilience;

import com.notification.application.port.out.ProviderCircuitPort;
import com.notification.domain.ProviderCallPermit;
import com.notification.domain.ProviderCircuitState;
import com.notification.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P2-a. 제공자 scope 공유 차단기. (가이드 §15.4)
 *
 * <p>여기서 지키려는 것은 하나다 — <b>회복 중인 업체를 N대가 동시에 두드리지 않는다.</b>
 * 노드마다 메모리 차단기를 붙이면 각자 탐침을 보내 막 살아난 업체를 다시 쓰러뜨린다.
 * 그래서 상태를 DB 한 행에 두고 모든 전이를 세대(generation) 조건 CAS로 한다.
 */
@TestPropertySource(properties = {
        "notification.scheduler.retry.fixed-delay-ms=3600000",
        "notification.scheduler.stuck.fixed-delay-ms=3600000",
        "notification.scheduler.metrics.fixed-delay-ms=3600000",
        "notification.scheduler.expiry.fixed-delay-ms=3600000",
        "notification.provider.circuit.failure-threshold=3",
        "notification.provider.circuit.open-seconds=30",
        "notification.provider.circuit.probe-lease-seconds=60"
})
class ProviderCircuitIntegrationTest extends AbstractIntegrationTest {

    private static final String SCOPE = "EMAIL:test-a";
    private static final String OTHER_SCOPE = "EMAIL:test-b";

    @Autowired ProviderCircuitPort circuit;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.notification.application.service.NotificationDispatchService dispatch;
    @Autowired com.notification.application.service.DispatchStateService state;
    @Autowired com.notification.infrastructure.repository.NotificationJpaRepository notifications;
    @Autowired com.notification.infrastructure.repository.NotificationLogJpaRepository logs;
    @Autowired com.notification.infrastructure.repository.DispatchHistoryJpaRepository histories;
    @Autowired com.notification.support.TestChannelSenderAdapter sender;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM provider_circuit");
        histories.deleteAll();
        logs.deleteAll();
        notifications.deleteAll();
        sender.reset();
    }

    /** 발송 경로가 쓰는 기본 scope. 테스트 전용 scope와 구분한다. */
    private static final String DISPATCH_SCOPE = "EMAIL:default";

    private Long insertDue(String key) {
        return notifications.saveAndFlush(com.notification.domain.Notification.builder()
                .receiverId(1L)
                .notificationType(com.notification.domain.NotificationType.PAYMENT_CONFIRMED)
                .channel(com.notification.domain.NotificationChannel.EMAIL)
                .channelTarget("user@test.com").eventId(key).idempotencyKey(key)
                .build()).getId();
    }

    /** 실제 시간을 기다리지 않고 차단 만료를 만든다. */
    private void expireOpenWindow(String scope) {
        jdbc.update("UPDATE provider_circuit SET open_until = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6)) WHERE scope = ?", scope);
    }

    private void expireProbeLease(String scope) {
        jdbc.update("UPDATE provider_circuit SET probe_until = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6)) WHERE scope = ?", scope);
    }

    private long generationOf(String scope) {
        return jdbc.queryForObject("SELECT generation FROM provider_circuit WHERE scope = ?", Long.class, scope);
    }

    /** 임계까지 scope 실패를 넣어 차단시킨다. */
    private void driveToOpen(String scope) {
        for (int i = 0; i < 3; i++) {
            ProviderCallPermit permit = circuit.tryAcquire(scope).orElseThrow();
            circuit.recordFailure(permit, true, Optional.empty());
        }
        assertThat(circuit.currentState(scope)).isEqualTo(ProviderCircuitState.OPEN);
    }

    private <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = IntStream.range(0, threads)
                .mapToObj(i -> pool.submit(() -> {
                    start.await();
                    return task.call();
                }))
                .toList();
        start.countDown();
        List<T> results = new java.util.ArrayList<>();
        for (Future<T> f : futures) results.add(f.get(30, TimeUnit.SECONDS));
        pool.shutdownNow();
        return results;
    }

    @Test
    @DisplayName("연속 실패가 임계에 닿으면 차단하고, 그 뒤 호출 허가가 나오지 않는다")
    void 임계_연속_실패는_차단한다() {
        assertThat(circuit.currentState(SCOPE)).isEqualTo(ProviderCircuitState.CLOSED);

        driveToOpen(SCOPE);

        assertThat(circuit.tryAcquire(SCOPE)).as("차단 중에는 아무도 호출하지 못한다").isEmpty();
    }

    @Test
    @DisplayName("수신자 오류는 업체를 막지 않는다 — 주소 하나 때문에 전체를 세우면 안 된다")
    void 수신자_오류는_차단하지_않는다() {
        for (int i = 0; i < 10; i++) {
            ProviderCallPermit permit = circuit.tryAcquire(SCOPE).orElseThrow();
            circuit.recordFailure(permit, false, Optional.empty());
        }

        assertThat(circuit.currentState(SCOPE)).isEqualTo(ProviderCircuitState.CLOSED);
        assertThat(circuit.tryAcquire(SCOPE)).isPresent();
    }

    @Test
    @DisplayName("차단이 풀린 순간 세 노드가 동시에 시도해도 탐침은 클러스터 전체에서 1건뿐이다")
    void 동시_탐침은_한_건뿐이다() throws Exception {
        driveToOpen(SCOPE);
        expireOpenWindow(SCOPE);

        List<Optional<ProviderCallPermit>> results = runConcurrently(3, () -> circuit.tryAcquire(SCOPE));

        List<ProviderCallPermit> granted = results.stream().flatMap(Optional::stream).toList();
        assertThat(granted).as("여럿이 동시에 두드리면 회복 중인 업체가 다시 쓰러진다").hasSize(1);
        assertThat(granted.get(0).isProbe()).isTrue();
        assertThat(circuit.currentState(SCOPE)).isEqualTo(ProviderCircuitState.HALF_OPEN);
    }

    @Test
    @DisplayName("탐침이 성공하면 회로를 닫고 나머지 노드도 다시 호출할 수 있다")
    void 탐침_성공은_회로를_닫는다() {
        driveToOpen(SCOPE);
        expireOpenWindow(SCOPE);
        ProviderCallPermit probe = circuit.tryAcquire(SCOPE).orElseThrow();

        circuit.recordSuccess(probe);

        assertThat(circuit.currentState(SCOPE)).isEqualTo(ProviderCircuitState.CLOSED);
        assertThat(circuit.tryAcquire(SCOPE)).isPresent();
    }

    @Test
    @DisplayName("탐침이 실패하면 한 번으로 다시 차단하고 대기를 늘린다")
    void 탐침_실패는_즉시_재차단하고_대기를_늘린다() {
        driveToOpen(SCOPE);
        int firstOpenSeconds = openWindowSeconds();
        expireOpenWindow(SCOPE);
        ProviderCallPermit probe = circuit.tryAcquire(SCOPE).orElseThrow();

        circuit.recordFailure(probe, true, Optional.empty());

        assertThat(circuit.currentState(SCOPE)).isEqualTo(ProviderCircuitState.OPEN);
        assertThat(openWindowSeconds()).as("회복되지 않은 업체를 같은 간격으로 계속 두드리지 않는다")
                .isGreaterThan(firstOpenSeconds);
        assertThat(circuit.tryAcquire(SCOPE)).isEmpty();
    }

    private int openWindowSeconds() {
        return jdbc.queryForObject(
                "SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(6), open_until) FROM provider_circuit WHERE scope = ?",
                Integer.class, SCOPE);
    }

    @Test
    @DisplayName("옛 세대의 늦은 성공은 새로 열린 차단을 풀지 못한다")
    void 늦은_성공은_새_차단을_풀지_못한다() {
        ProviderCallPermit stale = circuit.tryAcquire(SCOPE).orElseThrow();
        driveToOpen(SCOPE);   // 그 사이 다른 호출들이 실패해 회로가 열렸다

        circuit.recordSuccess(stale);

        assertThat(circuit.currentState(SCOPE))
                .as("이미 확정된 차단을 뒤늦은 성공이 취소하면 장애 중인 업체로 다시 쏟아진다")
                .isEqualTo(ProviderCircuitState.OPEN);
    }

    @Test
    @DisplayName("옛 세대의 늦은 실패는 대기 시간을 다시 늘리지 않는다")
    void 늦은_실패는_중복_반영되지_않는다() {
        ProviderCallPermit stale = circuit.tryAcquire(SCOPE).orElseThrow();
        driveToOpen(SCOPE);
        long generationAfterOpen = generationOf(SCOPE);
        int openSecondsAfterOpen = openWindowSeconds();

        circuit.recordFailure(stale, true, Optional.empty());

        assertThat(generationOf(SCOPE)).isEqualTo(generationAfterOpen);
        assertThat(openWindowSeconds()).isCloseTo(openSecondsAfterOpen, org.assertj.core.data.Offset.offset(2));
    }

    @Test
    @DisplayName("탐침 소유자가 죽으면 lease 만료 뒤 다른 노드가 인수한다 — 영구 HALF_OPEN 금지")
    void 죽은_탐침_소유자는_인수된다() {
        driveToOpen(SCOPE);
        expireOpenWindow(SCOPE);
        ProviderCallPermit dead = circuit.tryAcquire(SCOPE).orElseThrow();
        assertThat(circuit.tryAcquire(SCOPE)).as("소유자가 살아 있는 동안에는 인수하지 않는다").isEmpty();

        expireProbeLease(SCOPE);
        ProviderCallPermit taken = circuit.tryAcquire(SCOPE).orElseThrow();

        assertThat(taken.isProbe()).isTrue();
        assertThat(taken.generation()).as("세대를 올려 사라진 소유자의 늦은 결과를 막는다")
                .isGreaterThan(dead.generation());
        circuit.recordSuccess(dead);
        assertThat(circuit.currentState(SCOPE))
                .as("인수된 뒤 옛 소유자의 결과는 반영되지 않는다").isEqualTo(ProviderCircuitState.HALF_OPEN);
    }

    @Test
    @DisplayName("한 업체의 장애가 다른 scope를 막지 않는다")
    void scope는_서로_독립이다() {
        driveToOpen(SCOPE);

        assertThat(circuit.tryAcquire(SCOPE)).isEmpty();
        assertThat(circuit.tryAcquire(OTHER_SCOPE)).isPresent();
        assertThat(circuit.currentState(OTHER_SCOPE)).isEqualTo(ProviderCircuitState.CLOSED);
    }

    @Test
    @DisplayName("Retry-After가 기본 대기보다 길면 그만큼 차단을 늘린다")
    void 제공자_지시가_길면_그만큼_막는다() {
        ProviderCallPermit permit = circuit.tryAcquire(SCOPE).orElseThrow();
        for (int i = 0; i < 2; i++) {
            circuit.recordFailure(circuit.tryAcquire(SCOPE).orElseThrow(), true, Optional.empty());
        }

        circuit.recordFailure(circuit.tryAcquire(SCOPE).orElseThrow(), true, Optional.of(Duration.ofMinutes(10)));

        assertThat(circuit.currentState(SCOPE)).isEqualTo(ProviderCircuitState.OPEN);
        assertThat(openWindowSeconds()).isGreaterThan(500);
        assertThat(permit.scope()).isEqualTo(SCOPE);
    }

    // ── 발송 경로 연결 ────────────────────────────────────────────────

    @Test
    @DisplayName("연속 실패가 임계를 넘으면 그 뒤 알림은 호출되지 않고 보류된다")
    void 차단_중에는_발송하지_않고_보류한다() {
        sender.setFailCount(100);
        for (int i = 0; i < 3; i++) {
            Long id = insertDue("circuit-fail-" + i);
            dispatch.dispatch(id);
        }
        assertThat(circuit.currentState(DISPATCH_SCOPE)).isEqualTo(ProviderCircuitState.OPEN);
        int callsBeforeHold = sender.getSendCallCount();

        Long held = insertDue("circuit-held");
        dispatch.dispatch(held);

        assertThat(sender.getSendCallCount()).as("차단 중에는 업체를 두드리지 않는다").isEqualTo(callsBeforeHold);
        var after = notifications.findById(held).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(com.notification.domain.NotificationStatus.PENDING);
        assertThat(after.getRetryCount()).as("호출하지 않았으므로 실패 예산을 쓰지 않는다").isZero();
        assertThat(after.getNextRetryAt()).as("차단이 풀릴 때까지 선점·반환을 반복하지 않도록 미뤄 둔다").isNotNull();
        assertThat(logs.findAll()).anyMatch(l -> "PROVIDER_CIRCUIT_OPEN".equals(l.getReason()));
        assertThat(histories.count()).as("시도가 없었으므로 시도 이력도 없다")
                .isEqualTo(callsBeforeHold);
    }

    @Test
    @DisplayName("탐침 1건이 성공하면 회로가 닫히고 나머지 알림이 다시 나간다")
    void 탐침_성공_뒤_발송이_재개된다() {
        sender.setFailCount(100);
        for (int i = 0; i < 3; i++) {
            dispatch.dispatch(insertDue("circuit-recover-fail-" + i));
        }
        assertThat(circuit.currentState(DISPATCH_SCOPE)).isEqualTo(ProviderCircuitState.OPEN);
        sender.setFailCount(0);
        expireOpenWindow(DISPATCH_SCOPE);

        Long probeTarget = insertDue("circuit-probe");
        dispatch.dispatch(probeTarget);

        assertThat(notifications.findById(probeTarget).orElseThrow().getStatus())
                .isEqualTo(com.notification.domain.NotificationStatus.SENT);
        assertThat(circuit.currentState(DISPATCH_SCOPE)).isEqualTo(ProviderCircuitState.CLOSED);

        Long next = insertDue("circuit-after-recover");
        dispatch.dispatch(next);
        assertThat(notifications.findById(next).orElseThrow().getStatus())
                .isEqualTo(com.notification.domain.NotificationStatus.SENT);
    }
}
