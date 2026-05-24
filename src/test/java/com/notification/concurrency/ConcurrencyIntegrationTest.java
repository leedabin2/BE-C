package com.notification.concurrency;

import com.notification.application.port.in.RegisterNotificationUseCase;
import com.notification.application.port.in.command.RegisterNotificationCommand;
import com.notification.application.port.in.result.RegisterNotificationResult;
import com.notification.application.port.out.NotificationRepositoryPort;
import com.notification.application.service.NotificationDispatchService;
import com.notification.domain.*;
import com.notification.infrastructure.repository.DispatchHistoryJpaRepository;
import com.notification.infrastructure.repository.NotificationJpaRepository;
import com.notification.infrastructure.repository.NotificationLogJpaRepository;
import com.notification.support.AbstractIntegrationTest;
import com.notification.support.TestChannelSenderAdapter;
import lombok.extern.slf4j.Slf4j;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동시성 통합 테스트.
 *
 * [실행 환경]
 * - Docker Desktop이 실행 중이어야 한다 (Testcontainers가 MySQL 8.0 컨테이너를 자동 기동)
 * - 최초 실행 시 mysql:8.0 이미지 pull (~300MB), 이후 캐시 재사용
 * - 실행 명령: ./gradlew test --tests "com.notification.concurrency.*"
 */
@Slf4j
@DisplayName("동시성 통합 테스트")
class ConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired NotificationDispatchService dispatchService;
    @Autowired RegisterNotificationUseCase registerUseCase;
    @Autowired NotificationRepositoryPort notificationRepositoryPort;
    @Autowired NotificationJpaRepository notificationJpaRepository;
    @Autowired DispatchHistoryJpaRepository dispatchHistoryJpaRepository;
    @Autowired NotificationLogJpaRepository notificationLogJpaRepository;
    @Autowired TestChannelSenderAdapter channelSender;
    @Autowired TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() {
        dispatchHistoryJpaRepository.deleteAll();
        notificationLogJpaRepository.deleteAll();
        notificationJpaRepository.deleteAll();
        channelSender.reset();
    }

    private Notification saveNotification(String key) {
        return notificationRepositoryPort.save(
                Notification.builder()
                        .receiverId(1L)
                        .notificationType(NotificationType.PAYMENT_CONFIRMED)
                        .channel(NotificationChannel.EMAIL)
                        .eventId("evt-" + key)
                        .idempotencyKey(key)
                        .build()
        );
    }

    private void logDbState(String phase) {
        List<Notification> notifications = notificationJpaRepository.findAll();
        List<DispatchHistory> histories = dispatchHistoryJpaRepository.findAll();

        log.info("── [{}] DB 상태 ─────────────────────────────────", phase);
        log.info("  notification 총 {}건", notifications.size());
        for (Notification n : notifications) {
            log.info("    id={} | status={} | retryCount={} | failureReason={}",
                    n.getId(), n.getStatus(), n.getRetryCount(), n.getFailureReason());
        }
        log.info("  dispatch_history 총 {}건", histories.size());
        for (DispatchHistory h : histories) {
            log.info("    id={} | notificationId={} | status={} | attempt={} | error={}",
                    h.getId(), h.getNotificationId(), h.getStatus(), h.getAttemptNumber(), h.getErrorMessage());
        }
        log.info("────────────────────────────────────────────────────");
    }

    // [시나리오] 이벤트 핸들러와 스케줄러가 동시에 같은 알림 ID를 dispatch → 중복 발송 위험
    // → CAS(tryStartProcessing)로 1개 스레드만 처리, DispatchHistory 1건, SENT 1번 검증
    @Test
    @DisplayName("동일 알림 동시 dispatch: CAS로 1건만 발송, DispatchHistory 1건")
    void dispatch_concurrentSameId_onlyOneSucceeds() throws InterruptedException {
        Notification saved = saveNotification("cas-001");
        channelSender.setSendDelayMs(150); // Thread A가 트랜잭션 열고 있는 시간 확보

        log.info("[CAS 테스트] 알림 저장 완료: id={}, status={}", saved.getId(), saved.getStatus());
        logDbState("테스트 시작 전");

        int threadCount = 5;
        AtomicInteger casSuccessCount = new AtomicInteger(0);
        AtomicInteger casSkipCount = new AtomicInteger(0);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);

        for (int i = 0; i < threadCount; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    log.info("[CAS 테스트] Thread-{} dispatch 시작", threadIndex);
                    long before = dispatchHistoryJpaRepository.count();
                    dispatchService.dispatch(saved.getId());
                    long after = dispatchHistoryJpaRepository.count();
                    if (after > before) {
                        casSuccessCount.incrementAndGet();
                        log.info("[CAS 테스트] Thread-{} → 실제 발송 처리됨", threadIndex);
                    } else {
                        casSkipCount.incrementAndGet();
                        log.info("[CAS 테스트] Thread-{} → CAS 실패, 스킵", threadIndex);
                    }
                } catch (Exception e) {
                    log.warn("[CAS 테스트] Thread-{} 예외: {}", threadIndex, e.getMessage());
                }
            });
        }

        ready.await();
        log.info("[CAS 테스트] {}개 스레드 준비 완료 → 동시 출발", threadCount);
        start.countDown();
        executor.shutdown();
        executor.awaitTermination(10, SECONDS);

        log.info("[CAS 테스트] 완료 → 실제발송={}건, CAS스킵={}건", casSuccessCount.get(), casSkipCount.get());
        logDbState("테스트 완료 후");

        Notification result = notificationRepositoryPort.findById(saved.getId()).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(dispatchHistoryJpaRepository.count()).isEqualTo(1L);
    }

    // [시나리오] 다중 알림을 여러 스레드에서 동시 처리 → 알림마다 중복 발송 없이 1번씩만 처리돼야 함
    // → N개 알림, 각 2개 스레드 동시 dispatch 시 DispatchHistory = N건, 모든 알림 SENT 검증
    @Test
    @DisplayName("다수 알림 동시 dispatch: 각 알림 정확히 1번 처리, 중복 DispatchHistory 없음")
    void dispatch_multipleNotificationsConcurrently_eachProcessedOnce() throws InterruptedException {
        int count = 5;
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(saveNotification("multi-" + i).getId());
        }

        log.info("[다중 dispatch 테스트] 알림 {}건 저장 완료: ids={}", count, ids);
        logDbState("테스트 시작 전");

        int perNotificationThreads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(count * perNotificationThreads);
        CountDownLatch ready = new CountDownLatch(count * perNotificationThreads);
        CountDownLatch start = new CountDownLatch(1);

        for (Long id : ids) {
            for (int j = 0; j < perNotificationThreads; j++) {
                final int threadIndex = j;
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        log.info("[다중 dispatch 테스트] notificationId={} Thread-{} dispatch 시작", id, threadIndex);
                        dispatchService.dispatch(id);
                    } catch (Exception e) {
                        log.warn("[다중 dispatch 테스트] id={} 예외: {}", id, e.getMessage());
                    }
                });
            }
        }

        ready.await();
        log.info("[다중 dispatch 테스트] {}개 스레드 준비 완료 → 동시 출발", count * perNotificationThreads);
        start.countDown();
        executor.shutdown();
        executor.awaitTermination(15, SECONDS);

        logDbState("테스트 완료 후");

        for (Long id : ids) {
            Notification n = notificationRepositoryPort.findById(id).orElseThrow();
            assertThat(n.getStatus())
                    .as("알림 id=%d 은 SENT 상태여야 한다", id)
                    .isEqualTo(NotificationStatus.SENT);
        }
        assertThat(dispatchHistoryJpaRepository.count()).isEqualTo((long) count);
    }

    // [시나리오] 두 스케줄러 인스턴스가 동시에 실행 → 같은 알림을 중복 pick 시 중복 발송 발생
    // → SKIP LOCKED(fetch 중복 방지) + CAS(dispatch 중복 방지) 조합으로 DispatchHistory = N건 검증
    @Test
    @DisplayName("스케줄러 2개 동시 실행: SKIP LOCKED + CAS로 중복 처리 없음")
    void schedulerFlow_twoInstancesConcurrent_noDuplicateDispatching() throws InterruptedException {
        int notificationCount = 8;
        for (int i = 0; i < notificationCount; i++) {
            saveNotification("sched-" + i);
        }

        log.info("[스케줄러 테스트] 알림 {}건 저장 완료", notificationCount);
        logDbState("테스트 시작 전");

        int schedulerCount = 2;
        List<List<Long>> fetchedByThread = new ArrayList<>();
        for (int i = 0; i < schedulerCount; i++) fetchedByThread.add(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(schedulerCount);
        CountDownLatch ready = new CountDownLatch(schedulerCount);
        CountDownLatch start = new CountDownLatch(1);

        for (int i = 0; i < schedulerCount; i++) {
            final int schedulerIndex = i;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    log.info("[스케줄러 테스트] Scheduler-{} fetchPendingIds 시작", schedulerIndex);
                    List<Long> pending = dispatchService.fetchPendingIds(notificationCount);
                    fetchedByThread.get(schedulerIndex).addAll(pending);
                    log.info("[스케줄러 테스트] Scheduler-{} fetch 결과: {}건 → ids={}", schedulerIndex, pending.size(), pending);
                    for (Long id : pending) {
                        dispatchService.dispatch(id);
                        log.info("[스케줄러 테스트] Scheduler-{} dispatched id={}", schedulerIndex, id);
                    }
                } catch (Exception e) {
                    log.warn("[스케줄러 테스트] Scheduler-{} 예외: {}", schedulerIndex, e.getMessage());
                }
            });
        }

        ready.await();
        log.info("[스케줄러 테스트] {}개 스케줄러 준비 완료 → 동시 출발", schedulerCount);
        start.countDown();
        executor.shutdown();
        executor.awaitTermination(15, SECONDS);

        log.info("[스케줄러 테스트] Scheduler-0 fetch: {}건, Scheduler-1 fetch: {}건",
                fetchedByThread.get(0).size(), fetchedByThread.get(1).size());
        logDbState("테스트 완료 후");

        assertThat(dispatchHistoryJpaRepository.count()).isEqualTo((long) notificationCount);
    }

    // ── 시나리오 1: 멱등성 동시 중복 요청 ────────────────────────────────────────

    // [시나리오] 네트워크 재전송 등으로 동일 요청이 10개 스레드에서 동시에 들어옴 → 중복 저장 위험
    // → DB unique constraint + idempotency 체크로 notification 1건만 저장되는지 검증
    @Test
    @DisplayName("시나리오1 - 동일 key 10개 스레드 동시 register: notification 1건만 저장")
    void register_concurrent_onlyOneNotificationSaved() throws InterruptedException {
        RegisterNotificationCommand command = new RegisterNotificationCommand(
                42L, NotificationType.PAYMENT_CONFIRMED, NotificationChannel.EMAIL,
                "user@test.com", "evt-idem-001", 100L, "PAYMENT", "{}", null);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        List<RegisterNotificationResult> results = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger exceptionCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    results.add(registerUseCase.register(command));
                } catch (Exception e) {
                    exceptionCount.incrementAndGet();
                    log.warn("[멱등성 테스트] 예외 발생: {}", e.getMessage());
                }
            });
        }

        ready.await();
        log.info("[멱등성 테스트] {}개 스레드 준비 완료 → 동시 출발", threadCount);
        start.countDown();
        executor.shutdown();
        executor.awaitTermination(10, SECONDS);

        log.info("[멱등성 테스트] 성공={}건, 예외={}건", results.size(), exceptionCount.get());
        logDbState("테스트 완료 후");

        assertThat(notificationJpaRepository.count()).isEqualTo(1L);
        Set<Long> distinctIds = new HashSet<>();
        results.forEach(r -> distinctIds.add(r.id()));
        log.info("[멱등성 테스트] 반환된 notification id 집합: {}", distinctIds);
        assertThat(distinctIds).hasSize(1);
    }

    // ── 시나리오 2: Transactional Outbox — 커밋 후 이벤트 발행 → 발송 검증 ──────

    // [시나리오] register() 커밋 후 AFTER_COMMIT 이벤트가 실제로 dispatch를 트리거하지 않으면
    //           알림이 PENDING에 영구히 남음
    // → register() 호출 후 Awaitility로 SENT 전이를 기다려 full flow 검증
    @Test
    @DisplayName("시나리오2 - register 후 AFTER_COMMIT 이벤트 → 비동기 dispatch → SENT 전이")
    void register_afterCommit_eventHandlerDispatchesExactlyOnce() {
        RegisterNotificationCommand command = new RegisterNotificationCommand(
                42L, NotificationType.PAYMENT_CONFIRMED, NotificationChannel.EMAIL,
                "user@test.com", "evt-outbox-001", 100L, "PAYMENT", "{}", null);

        log.info("[Outbox 테스트] register() 호출");
        RegisterNotificationResult result = registerUseCase.register(command);
        log.info("[Outbox 테스트] register() 완료 id={}, status={}", result.id(), result.status());

        Awaitility.await()
                .atMost(5, SECONDS)
                .pollInterval(200, MILLISECONDS)
                .untilAsserted(() -> {
                    Notification n = notificationJpaRepository.findById(result.id()).orElseThrow();
                    log.info("[Outbox 테스트] 현재 status={}, sendCallCount={}",
                            n.getStatus(), channelSender.getSendCallCount());
                    assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
                });

        logDbState("dispatch 완료 후");
        assertThat(dispatchHistoryJpaRepository.count()).isEqualTo(1L);
        assertThat(channelSender.getSendCallCount()).isEqualTo(1);
    }

    // ── 시나리오 3: SKIP LOCKED 100건 5개 스레드 ────────────────────────────────

    // [시나리오] 5개 스케줄러 인스턴스가 동시에 fetch → 같은 알림을 여러 인스턴스가 중복 pick할 위험
    // → SKIP LOCKED로 스레드별 ID 집합이 겹치지 않고 합집합 ≤ 100건임을 검증
    @Test
    @DisplayName("시나리오3 - PENDING 100건 5개 스레드 동시 fetch: ID 중복 없음")
    void fetchPendingIds_fiveThreadsConcurrent_noIdOverlap() throws InterruptedException {
        int notificationCount = 100;
        for (int i = 0; i < notificationCount; i++) {
            saveNotification("skip-locked-" + i);
        }
        log.info("[SKIP LOCKED 테스트] 알림 {}건 저장 완료", notificationCount);

        int threadCount = 5;
        List<List<Long>> fetchedByThread = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            fetchedByThread.add(Collections.synchronizedList(new ArrayList<>()));
        }

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    List<Long> fetched = dispatchService.fetchPendingIds(notificationCount);
                    fetchedByThread.get(idx).addAll(fetched);
                    log.info("[SKIP LOCKED 테스트] Thread-{} fetch 결과: {}건", idx, fetched.size());
                } catch (Exception e) {
                    log.warn("[SKIP LOCKED 테스트] Thread-{} 예외: {}", idx, e.getMessage());
                }
            });
        }

        ready.await();
        log.info("[SKIP LOCKED 테스트] {}개 스레드 준비 완료 → 동시 출발", threadCount);
        start.countDown();
        executor.shutdown();
        executor.awaitTermination(10, SECONDS);

        List<Long> allFetched = fetchedByThread.stream().flatMap(Collection::stream).toList();
        Set<Long> uniqueFetched = new HashSet<>(allFetched);
        for (int i = 0; i < threadCount; i++) {
            log.info("[SKIP LOCKED 테스트] Thread-{}: {}건", i, fetchedByThread.get(i).size());
        }
        log.info("[SKIP LOCKED 테스트] 총 fetch: {}건, 중복 제거 후: {}건", allFetched.size(), uniqueFetched.size());

        assertThat(uniqueFetched).hasSize(allFetched.size());
        assertThat(allFetched.size()).isLessThanOrEqualTo(notificationCount);
    }

    // ── 시나리오 4: Transactional Outbox — 롤백 시 이벤트 미발행 ──────────────

    // [시나리오] register() 도중 트랜잭션 롤백 → notification 저장 취소 + 이벤트 미발행이어야 함
    //           AFTER_COMMIT이므로 커밋이 없으면 이벤트가 발행되지 않음 → dispatch 0건 보장
    // → TransactionTemplate으로 강제 롤백 후 DB 및 채널 호출 0건 검증
    @Test
    @DisplayName("시나리오4 - 트랜잭션 롤백 시 notification 저장 안 되고 이벤트 미발행")
    void register_rollback_noNotificationAndNoDispatch() throws InterruptedException {
        log.info("[롤백 테스트] 트랜잭션 내 save 후 강제 롤백 시작");

        transactionTemplate.execute(status -> {
            notificationRepositoryPort.save(
                    Notification.builder()
                            .receiverId(99L)
                            .notificationType(NotificationType.PAYMENT_CONFIRMED)
                            .channel(NotificationChannel.EMAIL)
                            .eventId("evt-rollback-001")
                            .idempotencyKey("rollback-key-001")
                            .build()
            );
            log.info("[롤백 테스트] save 완료 → rollbackOnly 설정");
            status.setRollbackOnly();
            return null;
        });

        Thread.sleep(500);
        logDbState("롤백 후");

        assertThat(notificationJpaRepository.count()).isEqualTo(0L);
        assertThat(dispatchHistoryJpaRepository.count()).isEqualTo(0L);
        assertThat(channelSender.getSendCallCount()).isEqualTo(0);
        log.info("[롤백 테스트] 검증 완료 - notification 0건, dispatch 0건, send 0회 ✓");
    }
}
