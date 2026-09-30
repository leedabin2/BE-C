package com.notification.load;

import com.notification.domain.NotificationStatus;
import com.notification.infrastructure.repository.NotificationJpaRepository;
import com.notification.support.AbstractIntegrationTest;
import com.notification.support.TestChannelSenderAdapter;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 발송 부하 테스트 — 실제 HTTP로 동시 요청을 넣고 SMTP 응답까지 측정한다.
 *
 * 서비스 직접 호출이 아니라 TestRestTemplate으로 톰캣을 통과시킨다.
 * 그래야 톰캣 스레드 · 커넥션 풀 · 워커풀이 함께 경쟁하는 실제 구간이 보인다.
 *
 * <b>핵심 검증</b>: SMTP가 2초 걸려도 HTTP 응답은 그것과 무관해야 한다. (F1-1 접수 완료 응답)
 * p99가 SMTP 지연에 근접하면 어딘가에서 발송이 응답을 막고 있다는 뜻이다.
 *
 * → 결과 해석: docs/DECISIONS.md D-015, D-016
 */
@Slf4j
// 즉시 발송 경로가 수용 못 한 몫은 거부되어 스케줄러가 회수한다.
// 기본 60초 주기면 측정이 몇 분씩 늘어지므로 2초로 줄인다. 회수 경로 자체는 그대로다.
@TestPropertySource(properties = "notification.scheduler.retry.fixed-delay-ms=2000")
@DisplayName("발송 부하 테스트 (HTTP)")
class DispatchThroughputLoadTest extends AbstractIntegrationTest {

    /** 총 요청 수. 전부 다른 eventId라 멱등성으로 합쳐지지 않는다. */
    private static final int TOTAL = 1000;

    /**
     * 동시에 출발하는 클라이언트 수.
     *
     * 이 테스트의 목적은 노트북의 Tomcat accept queue 한계를 재는 것이 아니라,
     * SMTP 지연이 접수 응답으로 전파되지 않는지를 보는 것이다. 1,000개 OS 스레드를
     * 한 번에 기동하면 클라이언트·Tomcat·DB 접수 큐 자체가 포화돼 p99가 왜 느린지
     * 구분할 수 없으므로, 요청 수와 접수 동시성은 분리한다.
     */
    private static final int CONCURRENCY = 100;

    /** 외부 SMTP 응답 지연(ms). */
    private static final long SMTP_DELAY_MS = 2000;

    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    @Autowired TestRestTemplate restTemplate;
    @Autowired NotificationJpaRepository notificationJpaRepository;
    @Autowired TestChannelSenderAdapter channelSender;
    @Autowired DataSource dataSource;

    @Autowired @Qualifier("realtimeExecutor") ThreadPoolTaskExecutor realtimeExecutor;
    @Autowired @Qualifier("batchExecutor")    ThreadPoolTaskExecutor batchExecutor;

    @BeforeEach
    void setUp() {
        notificationJpaRepository.deleteAll();
        channelSender.reset();
        channelSender.setSendDelayMs(SMTP_DELAY_MS);
    }

    @Test
    @DisplayName("HTTP 1000건·동시 100 요청 → SMTP 2초 → 전건 발송 완료까지 측정")
    void 동시_HTTP_요청부터_발송_완료까지_측정한다() throws InterruptedException {
        long[] latencyMs = new long[TOTAL];
        AtomicInteger httpError = new AtomicInteger();
        ExecutorService clients = Executors.newFixedThreadPool(CONCURRENCY);
        CountDownLatch ready = new CountDownLatch(CONCURRENCY);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(TOTAL);

        for (int i = 0; i < TOTAL; i++) {
            final int seq = i;
            clients.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    long t0 = System.nanoTime();
                    ResponseEntity<String> res = restTemplate.postForEntity(
                            "/api/v1/notifications", request(seq), String.class);
                    latencyMs[seq] = (System.nanoTime() - t0) / 1_000_000;
                    if (!res.getStatusCode().is2xxSuccessful()) {
                        httpError.incrementAndGet();
                        log.warn("[부하] HTTP {} seq={} body={}", res.getStatusCode(), seq, res.getBody());
                    }
                } catch (Exception e) {
                    httpError.incrementAndGet();
                    log.warn("[부하] 요청 실패 seq={} : {}", seq, e.toString());
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        log.info("[부하] {}건 준비 완료 → 동시 출발 (동시성={}, SMTP지연={}ms)", TOTAL, CONCURRENCY, SMTP_DELAY_MS);

        long t0 = System.nanoTime();
        start.countDown();
        assertThat(done.await(TIMEOUT.toSeconds(), SECONDS)).as("HTTP 응답 완료").isTrue();
        long registerMs = (System.nanoTime() - t0) / 1_000_000;
        clients.shutdownNow();

        snapshot("HTTP 응답 완료 시점");
        int immediatelyQueued = (int) notificationJpaRepository.findAll().stream()
                .filter(n -> n.getStatus() != NotificationStatus.PENDING).count();

        long t1 = System.nanoTime();
        try {
            Awaitility.await("전건 발송 완료")
                    .atMost(TIMEOUT)
                    .pollInterval(Duration.ofSeconds(1))
                    .until(() -> notificationJpaRepository.findAll().stream()
                            .noneMatch(n -> n.getStatus() == NotificationStatus.PENDING
                                         || n.getStatus() == NotificationStatus.PROCESSING
                                         || n.getStatus() == NotificationStatus.RETRYING));
        } finally {
            // 타임아웃으로 끝나도 측정값은 봐야 한다. 어디까지 갔는지가 곧 진단이다
            report(latencyMs, registerMs, (System.nanoTime() - t1) / 1_000_000,
                    httpError.get(), immediatelyQueued);
            snapshot("발송 종료 시점");
        }

        assertThat(httpError.get()).as("HTTP 실패 건수").isZero();
        assertThat(notificationJpaRepository.count()).isEqualTo(TOTAL);
        assertThat(notificationJpaRepository.countByStatusIn(java.util.List.of(NotificationStatus.SENT)))
                .as("무장애 Mock의 전건 SENT").isEqualTo(TOTAL);
        assertThat(channelSender.getSendCallCount()).as("이 컨텍스트의 실제 외부 호출 수").isEqualTo(TOTAL);
        assertThat(channelSender.getRecords()).extracting(TestChannelSenderAdapter.SendRecord::notificationId)
                .doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(
                        notificationJpaRepository.findAll().stream().map(n -> n.getId()).toList());
        // ★ 핵심: SMTP 2초가 HTTP 응답을 막지 않아야 한다 (F1-1).
        // DB 등록 경합의 p99는 이 노트북/컨테이너 자원에 따라 달라진다. “SMTP의 절반”은
        // 제품 SLO가 아니라 임의 수치이므로, 여기서는 직접 SMTP 호출(최소 2초 대기)을 구별하는 경계만 둔다.
        // 고정 p99 SLO는 S5 nGrinder의 기준선 측정 뒤 정한다.
        assertThat(percentile(latencyMs, 99))
                .as("HTTP p99는 SMTP 지연(%dms)을 기다리면 안 된다", SMTP_DELAY_MS)
                .isLessThan(SMTP_DELAY_MS);
    }

    private HttpEntity<String> request(int seq) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", "1");
        String body = """
                {"receiverId":%d,"notificationType":"PAYMENT_CONFIRMED","channel":"EMAIL",
                 "channelTarget":"user%d@test.com","eventId":"load-%d",
                 "referenceId":%d,"referenceType":"PAYMENT","contentData":"{}"}
                """.formatted(seq + 1, seq, seq, seq + 1);
        return new HttpEntity<>(body, headers);
    }

    private void snapshot(String phase) {
        HikariPoolMXBean pool = ((HikariDataSource) dataSource).getHikariPoolMXBean();
        log.info("[부하] {} — 커넥션 total={} active={} idle={} 대기={} | rt active={} queue={} | batch active={} queue={}",
                phase, pool.getTotalConnections(), pool.getActiveConnections(), pool.getIdleConnections(),
                pool.getThreadsAwaitingConnection(),
                realtimeExecutor.getActiveCount(), realtimeExecutor.getThreadPoolExecutor().getQueue().size(),
                batchExecutor.getActiveCount(), batchExecutor.getThreadPoolExecutor().getQueue().size());
    }

    private void report(long[] latency, long registerMs, long dispatchMs, int httpError, int immediatelyQueued) {
        var all = notificationJpaRepository.findAll();
        long sent   = all.stream().filter(n -> n.getStatus() == NotificationStatus.SENT).count();
        long failed = all.stream().filter(n -> n.getStatus() == NotificationStatus.FAILED).count();
        int sendCalls = channelSender.getSendCallCount();
        int rtCapacity = realtimeExecutor.getMaxPoolSize() + realtimeExecutor.getQueueCapacity();

        log.info("""

                ╔════════════════ 부하 테스트 결과 (HTTP) ════════════════
                ║ 요청       : {}건 동시 {} · SMTP {}ms
                ║ 워커풀     : rt {}/{}/{} (수용≈{})  ·  batch {}/{}/{}
                ║ 커넥션풀   : max {}
                ╠═════════════════ HTTP 응답 (접수) ══════════════════════
                ║ 전체 소요  : {}ms   →  {} TPS
                ║ p50 {}ms · p95 {}ms · p99 {}ms · max {}ms
                ║ HTTP 실패  : {}건
                ╠═════════════════ 발송 (SMTP) ═══════════════════════════
                ║ 즉시 발송 착수 : {}건 / {}건  (나머지는 스케줄러 회수)
                ║ 발송 완료까지  : {}ms  →  {} TPS
                ║ SENT {} · FAILED {} · 채널호출 {}회 (호출-요청 차이 {}회)
                ╠═════════════════════════════════════════════════════════
                ║ 전체(요청~완료) : {}ms
                ╚═════════════════════════════════════════════════════════""",
                TOTAL, CONCURRENCY, SMTP_DELAY_MS,
                realtimeExecutor.getCorePoolSize(), realtimeExecutor.getMaxPoolSize(),
                realtimeExecutor.getQueueCapacity(), rtCapacity,
                batchExecutor.getCorePoolSize(), batchExecutor.getMaxPoolSize(), batchExecutor.getQueueCapacity(),
                ((HikariDataSource) dataSource).getMaximumPoolSize(),
                registerMs, tps(TOTAL, registerMs),
                percentile(latency, 50), percentile(latency, 95), percentile(latency, 99),
                Arrays.stream(latency).max().orElse(0),
                httpError,
                immediatelyQueued, TOTAL,
                dispatchMs, tps(TOTAL, dispatchMs),
                sent, failed, sendCalls, sendCalls - TOTAL,
                registerMs + dispatchMs);
    }

    private static long percentile(long[] values, int p) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * p / 100.0) - 1)];
    }

    private static String tps(int count, long ms) {
        return ms == 0 ? "∞" : String.valueOf(count * 1000L / ms);
    }
}
