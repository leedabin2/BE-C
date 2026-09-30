package com.notification.load;

import com.notification.domain.Notification;
import com.notification.domain.NotificationStatus;
import com.notification.infrastructure.repository.NotificationJpaRepository;
import com.notification.support.AbstractIntegrationTest;
import com.notification.support.TestChannelSenderAdapter;
import com.notification.support.TestChannelSenderAdapter.SendRecord;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 만 건 규모 동시성 거동 관찰.
 *
 * 처리량이 아니라 <b>거동</b>을 본다.
 * ① 같은 eventId가 동시에 오면 몇 건이 저장되는가 (F3-1·F3-2)
 * ② 어느 스레드가 얼마나 처리하는가 — 즉시 발송(rt) vs 스케줄러 회수(batch)
 * ③ 등록 순서와 발송 순서가 얼마나 어긋나는가 (D-006)
 *
 * SMTP 지연을 0으로 둔다. 지연을 넣으면 만 건이 몇 분씩 걸려 거동 관찰이 어렵다.
 */
@Slf4j
@TestPropertySource(properties = {
        "notification.scheduler.retry.fixed-delay-ms=1000",
        "notification.scheduler.retry.batch-size=500"
})
@DisplayName("만 건 동시성 거동")
class ConcurrencyBehaviorLoadTest extends AbstractIntegrationTest {

    /** 서로 다른 eventId. 전부 저장돼야 한다. */
    private static final int UNIQUE = 9_000;
    /** 중복 그룹 수. 그룹당 DUP_PER_KEY 개 요청이 같은 eventId로 동시에 들어간다. */
    private static final int DUP_KEYS = 10;
    private static final int DUP_PER_KEY = 100;
    /** 총 요청 = 9000 + 1000 = 10,000. 기대 저장 = 9000 + 10 = 9,010 */
    private static final int TOTAL_REQUESTS = UNIQUE + DUP_KEYS * DUP_PER_KEY;
    private static final int EXPECTED_ROWS = UNIQUE + DUP_KEYS;

    /**
     * 총량 1만과 HTTP 동시성은 분리한다. 200개는 이 노트북의 Tomcat/DB 접수 용량을 넘겨
     * 503을 의도적으로 만들었고, 그때는 "정상 무장애 발송 1회"를 검증하는 테스트가 아니다.
     * 과부하 임계·503 호출자 재시도는 S5 nGrinder에서 별도 측정한다.
     */
    private static final int CLIENTS = 100;
    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    @Autowired TestRestTemplate restTemplate;
    @Autowired NotificationJpaRepository notificationJpaRepository;
    @Autowired TestChannelSenderAdapter channelSender;

    @BeforeEach
    void setUp() {
        notificationJpaRepository.deleteAll();
        channelSender.reset();
        channelSender.setSendDelayMs(0);
    }

    @Test
    @DisplayName("HTTP 10,000건(중복 1,000 포함) → 중복 제거·스레드 분포·처리 순서 관찰")
    void 만건_동시_요청의_거동을_관찰한다() throws InterruptedException {
        ExecutorService clients = Executors.newFixedThreadPool(CLIENTS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(TOTAL_REQUESTS);
        AtomicInteger httpError = new AtomicInteger();

        // 중복 그룹과 고유 요청을 섞어서 제출한다. 순서가 뒤죽박죽이어야 실제 경합이 재현된다
        for (int i = 0; i < UNIQUE; i++) submit(clients, start, done, httpError, "uniq-" + i, i);
        for (int k = 0; k < DUP_KEYS; k++) {
            for (int n = 0; n < DUP_PER_KEY; n++) submit(clients, start, done, httpError, "dup-" + k, k);
        }

        log.info("[거동] {}건 제출 완료 (고유 {} · 중복 {}그룹×{}) → 동시 출발",
                TOTAL_REQUESTS, UNIQUE, DUP_KEYS, DUP_PER_KEY);
        long t0 = System.nanoTime();
        start.countDown();
        assertThat(done.await(TIMEOUT.toSeconds(), SECONDS)).as("HTTP 응답 완료").isTrue();
        long registerMs = (System.nanoTime() - t0) / 1_000_000;
        clients.shutdownNow();

        long t1 = System.nanoTime();
        Awaitility.await("전건 발송 완료").atMost(TIMEOUT).pollInterval(Duration.ofSeconds(1))
                .until(() -> notificationJpaRepository.countByStatusIn(
                        List.of(NotificationStatus.PENDING, NotificationStatus.PROCESSING,
                                NotificationStatus.RETRYING)) == 0);
        long dispatchMs = (System.nanoTime() - t1) / 1_000_000;

        report(registerMs, dispatchMs, httpError.get());

        assertThat(httpError.get()).as("HTTP 실패").isZero();
        // ★ F3-1·F3-2: 같은 eventId 100건이 동시에 와도 행은 1개
        assertThat(notificationJpaRepository.count()).as("중복 제거 후 행 수").isEqualTo(EXPECTED_ROWS);
        assertThat(notificationJpaRepository.countByStatusIn(List.of(NotificationStatus.SENT)))
                .as("무장애 Mock의 전건 SENT").isEqualTo(EXPECTED_ROWS);
        // ★ F3-1: 이 시나리오의 각 DB 작업은 채널까지 정확히 한 번만 도달한다.
        assertThat(channelSender.getRecords()).as("채널 호출 수").hasSize(EXPECTED_ROWS)
                .extracting(SendRecord::notificationId)
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(notificationJpaRepository.findAll().stream()
                        .map(Notification::getId).toList());
    }

    private void submit(ExecutorService pool, CountDownLatch start, CountDownLatch done,
                        AtomicInteger err, String eventId, int seq) {
        pool.submit(() -> {
            try {
                start.await();
                var res = restTemplate.postForEntity("/api/v1/notifications", body(eventId, seq), String.class);
                if (!res.getStatusCode().is2xxSuccessful()) err.incrementAndGet();
            } catch (Exception e) {
                err.incrementAndGet();
            } finally {
                done.countDown();
            }
        });
    }

    private HttpEntity<String> body(String eventId, int seq) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", "1");
        return new HttpEntity<>("""
                {"receiverId":%d,"notificationType":"PAYMENT_CONFIRMED","channel":"EMAIL",
                 "channelTarget":"u%d@test.com","eventId":"%s","referenceId":%d,
                 "referenceType":"PAYMENT","contentData":"{}"}
                """.formatted(seq + 1, seq, eventId, seq + 1), headers);
    }

    private void report(long registerMs, long dispatchMs, int httpError) {
        List<SendRecord> records = channelSender.getRecords();

        // ② 스레드 분포
        Map<String, Long> byThread = records.stream()
                .collect(Collectors.groupingBy(SendRecord::thread, Collectors.counting()));
        long viaRt = records.stream().filter(SendRecord::viaRealtime).count();

        // ③ 등록 순서(id 오름차순) 대비 발송 순서 역전율
        List<SendRecord> byId = records.stream()
                .sorted(Comparator.comparingLong(SendRecord::notificationId)).toList();
        int inversions = 0;
        for (int i = 1; i < byId.size(); i++) {
            if (byId.get(i).order() < byId.get(i - 1).order()) inversions++;
        }
        double inversionRate = byId.isEmpty() ? 0 : inversions * 100.0 / byId.size();

        String threadTable = byThread.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> "║   %-24s %6d건".formatted(e.getKey(), e.getValue()))
                .collect(Collectors.joining("\n"));

        log.info("""

                ╔═════════════ 만 건 동시성 거동 ═════════════
                ║ 요청     : {}건 (고유 {} · 중복 {}그룹×{})
                ║ 클라이언트: {} 스레드 동시
                ╠═════════════ ① 중복 제거 (F3-1·F3-2) ══════
                ║ 저장된 행 : {} (기대 {})
                ║ 채널 호출 : {}회  → 알림당 {}회
                ║ HTTP 실패 : {}건
                ╠═════════════ ② 스레드 분포 ════════════════
                ║ 즉시발송(rt) {}건 / 스케줄러(batch) {}건
                {}
                ╠═════════════ ③ 처리 순서 (D-006) ══════════
                ║ id 순서 대비 발송 순서 역전 : {}건 ({}%)
                ║ → FIFO는 "꺼내는 순서"만이다. 완료 순서는 보장되지 않는다
                ╠════════════════════════════════════════════
                ║ 등록 {}ms · 발송 {}ms
                ╚════════════════════════════════════════════""",
                TOTAL_REQUESTS, UNIQUE, DUP_KEYS, DUP_PER_KEY, CLIENTS,
                notificationJpaRepository.count(), EXPECTED_ROWS,
                records.size(),
                records.isEmpty() ? 0 : String.format("%.2f", records.size() * 1.0 / EXPECTED_ROWS),
                httpError,
                viaRt, records.size() - viaRt, threadTable,
                inversions, String.format("%.1f", inversionRate),
                registerMs, dispatchMs);
    }
}
