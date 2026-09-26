package com.notification.multiprocess;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S4: 같은 DB를 공유하는 별도 Spring Boot JVM들의 협업 검증.
 *
 * <p>일반 {@code @SpringBootTest}는 JVM 하나 안의 여러 스레드만 검증한다. 이 테스트는 bootJar를
 * 실제로 세 번 실행하고, multiprocess 프로필의 DB probe를 이용해 "외부 호출" 횟수를 프로세스 밖에서
 * 집계한다. 따라서 static 메모리, Spring context, executor가 공유되지 않는 N대 배포를 재현한다.</p>
 *
 * <p>두 번째 시나리오의 lease/backoff 시간은 DB에서 과거로 당겨 진행한다. 10분 lease와 1분 backoff를
 * 실시간으로 기다리는 대신, <em>실제 JVM 강제 종료 → 만료 lease 회수 → 다른 JVM 발송</em>의 상태 전이는
 * 그대로 검증한다.</p>
 */
class MultiProcessClusterIntegrationTest {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("notification_multiprocess_test")
            .withUsername("test")
            .withPassword("test");

    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(30);

    private final List<StartedNode> startedNodes = new ArrayList<>();

    @BeforeAll
    static void startDatabase() {
        MYSQL.start();
    }

    @AfterEach
    void stopNodes() {
        for (StartedNode node : startedNodes) {
            stop(node.process());
        }
        startedNodes.clear();
    }

    @AfterAll
    static void stopDatabase() {
        MYSQL.stop();
    }

    @Test
    void three_independent_nodes_claim_due_rows_once_and_send_once() throws Exception {
        startNode("node-a", 0);
        startNode("node-b", 0);
        startNode("node-c", 0);
        clearBusinessTables();

        int notificationCount = 30;
        insertPendingNotifications(notificationCount, "normal-cluster-event-");

        await("all due work is sent")
                .untilAsserted(() -> assertThat(notificationCountWithStatus("SENT")).isEqualTo(notificationCount));

        assertThat(probeCount()).isEqualTo(notificationCount);
        assertThat(distinctProbedNotificationCount()).isEqualTo(notificationCount);
        assertThat(duplicateProbedNotificationCount()).isZero();
        assertThat(probeCountFromUnknownNode()).isZero();
    }

    @Test
    void another_process_recovers_a_claim_when_the_original_process_is_killed_before_external_send() throws Exception {
        StartedNode stalledNode = startNode("stalled-node", 10_000);
        clearBusinessTables();

        long notificationId = insertPendingNotification("crash-recovery-event");
        await("stalled node has claimed, but has not called the external channel")
                .untilAsserted(() -> {
                    assertThat(notificationStatus(notificationId)).isEqualTo("PROCESSING");
                    assertThat(probeCount()).isZero();
                });

        // 외부 I/O 직전 sleep 중인 실제 JVM을 죽인다. 따라서 finish TX도, probe INSERT도 수행되지 않는다.
        stop(stalledNode.process());
        startedNodes.remove(stalledNode);

        // 실제 운영에서는 lease(10분) 및 ShedLock의 lockAtMostFor가 지나면 가능한 전이다.
        // 테스트는 기다리지 않도록 DB 시각 기준으로 두 만료 시각을 과거로 만든다.
        executeUpdate("UPDATE notification SET lease_until = DATE_SUB(NOW(6), INTERVAL 1 SECOND) WHERE id = ?", notificationId);
        executeUpdate("UPDATE shedlock SET lock_until = DATE_SUB(NOW(6), INTERVAL 1 SECOND) "
                + "WHERE name = 'stuckRecoveryScheduler'");

        startNode("recovery-node", 0);
        await("recovery node changes expired PROCESSING to RETRYING")
                .untilAsserted(() -> assertThat(notificationStatus(notificationId)).isEqualTo("RETRYING"));

        // recovery도 RetryPolicy(최소 1분 + jitter)를 적용했음을 확인한 뒤, 테스트 실행 시간을 위해 due를 앞당긴다.
        assertThat(notificationRetryCount(notificationId)).isEqualTo(1);
        executeUpdate("UPDATE notification SET next_retry_at = DATE_SUB(NOW(6), INTERVAL 1 SECOND) WHERE id = ?", notificationId);

        await("recovery node sends exactly once")
                .untilAsserted(() -> {
                    assertThat(notificationStatus(notificationId)).isEqualTo("SENT");
                    assertThat(probeCount()).isEqualTo(1);
                    assertThat(probeCountForNode("stalled-node")).isZero();
                    assertThat(probeCountForNode("recovery-node")).isEqualTo(1);
                });
    }

    private StartedNode startNode(String nodeId, long beforeSendDelayMs) throws Exception {
        Path jar = Path.of("build", "libs", "notification-0.0.1-SNAPSHOT.jar").toAbsolutePath();
        assertThat(Files.isRegularFile(jar))
                .as("test task depends on bootJar, so the executable application jar must exist")
                .isTrue();

        Path logFile = Files.createTempFile("notification-" + nodeId + "-", ".log");
        ProcessBuilder builder = new ProcessBuilder(
                javaExecutable(), "-jar", jar.toString(),
                "--spring.profiles.active=multiprocess",
                "--server.port=0",
                "--spring.datasource.url=" + MYSQL.getJdbcUrl(),
                "--spring.datasource.username=" + MYSQL.getUsername(),
                "--spring.datasource.password=" + MYSQL.getPassword(),
                "--spring.datasource.hikari.maximum-pool-size=5",
                "--notification.node-id=" + nodeId,
                "--notification.multiprocess.probe.before-send-delay-ms=" + beforeSendDelayMs,
                "--notification.scheduler.retry.fixed-delay-ms=100",
                "--notification.scheduler.retry.batch-size=10",
                "--notification.scheduler.stuck.fixed-delay-ms=100",
                "--notification.scheduler.stuck.batch-size=10",
                "--notification.scheduler.metrics.fixed-delay-ms=3600000"
        );
        // application.yml의 ${DB_*} placeholder도 command-line 값과 일관되게 채운다.
        builder.environment().put("DB_URL", MYSQL.getJdbcUrl());
        builder.environment().put("DB_USERNAME", MYSQL.getUsername());
        builder.environment().put("DB_PASSWORD", MYSQL.getPassword());
        builder.redirectErrorStream(true);
        builder.redirectOutput(logFile.toFile());

        Process process = builder.start();
        StartedNode node = new StartedNode(nodeId, process, logFile);
        startedNodes.add(node);

        try {
            await("node " + nodeId + " completes Spring Boot startup")
                    .atMost(Duration.ofSeconds(45))
                    .untilAsserted(() -> assertThat(nodeIsReady(node)).isTrue());
        } catch (AssertionError failure) {
            stop(process);
            startedNodes.remove(node);
            throw new AssertionError("Node " + nodeId + " did not start. log=" + logFile + "\n" + readLog(logFile), failure);
        }
        return node;
    }

    private void clearBusinessTables() throws SQLException {
        executeUpdate("DELETE FROM multiprocess_delivery_probe");
        executeUpdate("DELETE FROM notification_log");
        executeUpdate("DELETE FROM dispatch_history");
        executeUpdate("DELETE FROM notification");
        executeUpdate("DELETE FROM shedlock");
    }

    private void insertPendingNotifications(int count, String eventPrefix) throws SQLException {
        for (int i = 0; i < count; i++) {
            insertPendingNotification(eventPrefix + i);
        }
    }

    private long insertPendingNotification(String eventId) throws SQLException {
        String sql = """
                INSERT INTO notification (
                    idempotency_key, receiver_id, channel_target, notification_type, channel,
                    event_id, reference_id, reference_type, content_data, status, retry_count,
                    is_read, created_at, updated_at
                ) VALUES (?, ?, ?, 'PAYMENT_CONFIRMED', 'EMAIL', ?, ?, 'PAYMENT', '{}', 'PENDING', 0, false, NOW(6), NOW(6))
                """;
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, "multiprocess-key-" + eventId);
            statement.setLong(2, 1000L);
            statement.setString(3, "test@example.com");
            statement.setString(4, eventId);
            statement.setLong(5, 10L);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                return keys.getLong(1);
            }
        }
    }

    private String notificationStatus(long notificationId) throws SQLException {
        return queryForString("SELECT status FROM notification WHERE id = ?", notificationId);
    }

    private int notificationRetryCount(long notificationId) throws SQLException {
        return queryForInt("SELECT retry_count FROM notification WHERE id = ?", notificationId);
    }

    private int notificationCountWithStatus(String status) throws SQLException {
        return queryForInt("SELECT COUNT(*) FROM notification WHERE status = ?", status);
    }

    private int probeCount() throws SQLException {
        return queryForInt("SELECT COUNT(*) FROM multiprocess_delivery_probe");
    }

    private int distinctProbedNotificationCount() throws SQLException {
        return queryForInt("SELECT COUNT(DISTINCT notification_id) FROM multiprocess_delivery_probe");
    }

    private int duplicateProbedNotificationCount() throws SQLException {
        return queryForInt("SELECT COUNT(*) FROM (SELECT notification_id FROM multiprocess_delivery_probe "
                + "GROUP BY notification_id HAVING COUNT(*) > 1) duplicate_notifications");
    }

    private int probeCountForNode(String nodeId) throws SQLException {
        return queryForInt("SELECT COUNT(*) FROM multiprocess_delivery_probe WHERE node_id = ?", nodeId);
    }

    private int probeCountFromUnknownNode() throws SQLException {
        return queryForInt("SELECT COUNT(*) FROM multiprocess_delivery_probe "
                + "WHERE node_id NOT IN ('node-a', 'node-b', 'node-c')");
    }

    private boolean tableExists(String tableName) throws SQLException {
        return queryForInt("SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema = DATABASE() AND table_name = ?", tableName) == 1;
    }

    private boolean nodeIsReady(StartedNode node) {
        return node.process().isAlive()
                && readLog(node.logFile()).contains("Started NotificationApplication");
    }

    private void executeUpdate(String sql, Object... parameters) throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            statement.executeUpdate();
        }
    }

    private int queryForInt(String sql, Object... parameters) throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getInt(1);
            }
        }
    }

    private String queryForString(String sql, Object... parameters) throws SQLException {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private void bind(PreparedStatement statement, Object... parameters) throws SQLException {
        for (int i = 0; i < parameters.length; i++) {
            statement.setObject(i + 1, parameters[i]);
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private org.awaitility.core.ConditionFactory await(String description) {
        return Awaitility.await(description)
                .pollInterval(Duration.ofMillis(100))
                .atMost(DELIVERY_TIMEOUT)
                .ignoreExceptions();
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static void stop(Process process) {
        if (!process.isAlive()) return;
        process.destroyForcibly();
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String readLog(Path logFile) {
        try {
            return Files.readString(logFile);
        } catch (IOException ignored) {
            return "<unable to read process log>";
        }
    }

    private record StartedNode(String nodeId, Process process, Path logFile) {
    }
}
