package com.notification.resilience;

import com.notification.application.port.out.NotificationRepositoryPort;
import com.notification.application.service.NotificationDispatchService;
import com.notification.domain.*;
import com.notification.infrastructure.repository.DispatchHistoryJpaRepository;
import com.notification.infrastructure.repository.NotificationJpaRepository;
import com.notification.infrastructure.repository.NotificationLogJpaRepository;
import com.notification.support.AbstractIntegrationTest;
import com.notification.support.TestChannelSenderAdapter;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 장애·복구 통합 테스트.
 *
 * [실행 환경]
 * - Docker Desktop이 실행 중이어야 한다 (Testcontainers가 MySQL 8.0 컨테이너를 자동 기동)
 * - 실행 명령: ./gradlew test --tests "com.notification.resilience.*"
 *
 * [테스트 전략]
 * - 스케줄러를 직접 호출하면 ShedLock 최소 점유(lockAtLeastFor=10s)로 테스트 간 간섭 발생
 * - 대신 dispatchService.fetchPendingIds() + dispatch() 조합으로 스케줄러 흐름을 동일하게 재현
 * - PROCESSING stuck 복구는 dispatchService.recoverStuck() 직접 호출
 * - nextRetryAt, updated_at 조작이 필요한 시나리오는 JdbcTemplate으로 DB 직접 수정
 *   (@PreUpdate 콜백을 우회해야 하기 때문)
 */
@Slf4j
@DisplayName("장애·복구 통합 테스트")
class ResilienceIntegrationTest extends AbstractIntegrationTest {

    @Autowired NotificationDispatchService dispatchService;
    @Autowired NotificationRepositoryPort notificationRepositoryPort;
    @Autowired NotificationJpaRepository notificationJpaRepository;
    @Autowired DispatchHistoryJpaRepository dispatchHistoryJpaRepository;
    @Autowired NotificationLogJpaRepository notificationLogJpaRepository;
    @Autowired TestChannelSenderAdapter channelSender;
    @Autowired JdbcTemplate jdbcTemplate;

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

    private void setNextRetryAtInPast(Long id) {
        jdbcTemplate.update(
                "UPDATE notification SET next_retry_at = ? WHERE id = ?",
                LocalDateTime.now().minusSeconds(1), id
        );
    }

    private void logDbState(String phase) {
        List<Notification> notifications = notificationJpaRepository.findAll();
        List<DispatchHistory> histories = dispatchHistoryJpaRepository.findAll();

        log.info("── [{}] DB 상태 ─────────────────────────────────", phase);
        log.info("  notification 총 {}건", notifications.size());
        for (Notification n : notifications) {
            log.info("    id={} | status={} | retryCount={} | failureReason={} | nextRetryAt={}",
                    n.getId(), n.getStatus(), n.getRetryCount(), n.getFailureReason(), n.getNextRetryAt());
        }
        log.info("  dispatch_history 총 {}건", histories.size());
        for (DispatchHistory h : histories) {
            log.info("    id={} | notificationId={} | status={} | attempt={} | error={}",
                    h.getId(), h.getNotificationId(), h.getStatus(), h.getAttemptNumber(), h.getErrorMessage());
        }
        log.info("────────────────────────────────────────────────────");
    }

    // ── 시나리오 A: 서버 재시작 후 PENDING 알림 재처리 ─────────────────────────

    // [시나리오] 서버 재시작 → 이벤트 핸들러가 실행되지 않아 PENDING이 DB에 잔류
    //           → retryScheduler 실행 시 이벤트 없이 스케줄러만으로 발송 완료돼야 함
    // → fetchPendingIds + dispatch로 SENT 전이, DispatchHistory 1건 검증
    @Test
    @DisplayName("시나리오A - 서버 재시작 후 PENDING 잔류 알림: 스케줄러만으로 발송 완료")
    void retryScheduler_pendingAfterServerRestart_getsSent() {
        // 이벤트 발행 없이 DB에 직접 PENDING 삽입 — 서버 재시작 상황 모사
        Notification notification = saveNotification("restart-001");
        log.info("[시나리오A] PENDING 알림 직접 저장 id={}", notification.getId());
        logDbState("시작 전");

        // retryScheduler 흐름 재현: fetchPendingIds → dispatch
        List<Long> pendingIds = dispatchService.fetchPendingIds(10);
        log.info("[시나리오A] fetchPendingIds 결과: {}건 → {}", pendingIds.size(), pendingIds);
        for (Long id : pendingIds) {
            dispatchService.dispatch(id);
        }

        logDbState("dispatch 완료 후");

        Notification result = notificationJpaRepository.findById(notification.getId()).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(dispatchHistoryJpaRepository.count()).isEqualTo(1L);
        assertThat(channelSender.getSendCallCount()).isEqualTo(1);
        log.info("[시나리오A] 검증 완료 — 이벤트 핸들러 없이 스케줄러만으로 SENT ✓");
    }

    // ── 시나리오 B: PROCESSING stuck 복구 전체 흐름 ─────────────────────────────

    // [시나리오] 서버 장애로 PROCESSING 상태에서 20분째 멈춤 → stuck 판정(threshold=10분)
    //           → stuckRecoveryScheduler가 PENDING으로 복구 → retryScheduler가 재발송
    // → STUCK_RECOVERY NotificationLog 기록 + 최종 SENT 전이 검증
    @Test
    @DisplayName("시나리오B - PROCESSING stuck 20분 → PENDING 복구 → 재발송 → SENT")
    void stuckRecovery_processingStuck20Min_recoveredAndSent() {
        Notification notification = saveNotification("stuck-001");
        Long id = notification.getId();

        // PROCESSING 상태 + updated_at = 20분 전으로 DB 직접 설정 (서버 장애 상황 모사)
        // @PreUpdate 우회를 위해 JdbcTemplate 사용
        jdbcTemplate.update(
                "UPDATE notification SET status = 'PROCESSING', updated_at = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(20), id
        );
        log.info("[시나리오B] PROCESSING + 20분 전 updated_at 설정 id={}", id);
        logDbState("stuck 설정 후");

        // stuckRecoveryScheduler 흐름 재현: threshold=10분 → 20분 전이면 대상
        dispatchService.recoverStuck(10);

        Notification recovered = notificationJpaRepository.findById(id).orElseThrow();
        log.info("[시나리오B] 복구 후 status={}, retryCount={}", recovered.getStatus(), recovered.getRetryCount());
        assertThat(recovered.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(recovered.getRetryCount()).isEqualTo(0);

        // NotificationLog에 STUCK_RECOVERY reason 기록 확인
        boolean hasStuckLog = notificationLogJpaRepository.findAll().stream()
                .anyMatch(l -> "STUCK_RECOVERY".equals(l.getReason()));
        assertThat(hasStuckLog).isTrue();
        log.info("[시나리오B] STUCK_RECOVERY 로그 확인 ✓");

        // 복구 후 retryScheduler 재실행 → 최종 SENT 전이
        List<Long> pendingIds = dispatchService.fetchPendingIds(10);
        for (Long pendingId : pendingIds) {
            dispatchService.dispatch(pendingId);
        }

        logDbState("재발송 후");

        Notification sent = notificationJpaRepository.findById(id).orElseThrow();
        assertThat(sent.getStatus()).isEqualTo(NotificationStatus.SENT);
        log.info("[시나리오B] 검증 완료 — stuck → PENDING 복구 → SENT ✓");
    }

    // ── 시나리오 C: 외부 채널 일시 장애 → 재시도 → 성공 ───────────────────────

    // [시나리오] 채널 서버 1회 오류(RetryableException) → RETRYING 상태로 대기
    //           → nextRetryAt 경과 후 스케줄러 재실행 → 성공
    // → DispatchHistory 2건(FAILED + SENT), retryCount=1 → SENT 전이 검증
    @Test
    @DisplayName("시나리오C - 채널 1회 일시 장애 → RETRYING → nextRetryAt 경과 후 재발송 → SENT")
    void channelTemporaryFailure_retry_getsSent() {
        Notification notification = saveNotification("retry-once-001");
        Long id = notification.getId();

        // 1회 RetryableException 발생 후 성공하도록 설정
        channelSender.setFailCount(1);

        log.info("[시나리오C] 1차 dispatch 시작 id={}", id);
        dispatchService.dispatch(id);

        Notification afterFirst = notificationJpaRepository.findById(id).orElseThrow();
        log.info("[시나리오C] 1차 dispatch 후 status={}, retryCount={}, nextRetryAt={}",
                afterFirst.getStatus(), afterFirst.getRetryCount(), afterFirst.getNextRetryAt());

        // 1회 실패 후: RETRYING, retryCount=1, nextRetryAt = 1분 후 설정
        assertThat(afterFirst.getStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(afterFirst.getRetryCount()).isEqualTo(1);
        assertThat(afterFirst.getNextRetryAt()).isAfter(LocalDateTime.now().minusSeconds(1));

        // nextRetryAt 경과 시뮬레이션 (실제 1분 대기 대신 DB 직접 수정)
        setNextRetryAtInPast(id);
        log.info("[시나리오C] nextRetryAt 과거로 설정 → 스케줄러 재실행");

        List<Long> pendingIds = dispatchService.fetchPendingIds(10);
        assertThat(pendingIds).contains(id);
        for (Long pendingId : pendingIds) {
            dispatchService.dispatch(pendingId);
        }

        logDbState("재발송 후");

        Notification result = notificationJpaRepository.findById(id).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(NotificationStatus.SENT);

        // DispatchHistory: 1차 FAILED + 2차 SENT = 2건
        List<DispatchHistory> histories = dispatchHistoryJpaRepository.findAll();
        assertThat(histories).hasSize(2);
        assertThat(histories.stream().filter(h -> h.getStatus() == DispatchStatus.FAILED).count()).isEqualTo(1L);
        assertThat(histories.stream().filter(h -> h.getStatus() == DispatchStatus.SENT).count()).isEqualTo(1L);
        log.info("[시나리오C] 검증 완료 — DispatchHistory 2건(FAILED+SENT), 최종 SENT ✓");
    }

    // ── 시나리오 D: 외부 서버 타임아웃 → NonRetryable → 즉시 FAILED ───────────

    // [시나리오] 채널 타임아웃 → NonRetryableException → 재시도 없이 즉시 FAILED
    //           [정책 근거] 외부 서비스가 이미 처리했을 가능성 → 재시도 시 중복 발송 위험
    // → retryCount=0 유지, FAILED, DispatchHistory 1건(FAILED), 이후 스케줄러 대상 제외 검증
    @Test
    @DisplayName("시나리오D - 채널 타임아웃 → NonRetryable → 재시도 없이 즉시 FAILED")
    void channelTimeout_nonRetryable_immediatelyFailed() {
        Notification notification = saveNotification("timeout-001");
        Long id = notification.getId();

        // NonRetryableException(타임아웃 모사) 설정
        channelSender.setThrowTimeout(true);

        log.info("[시나리오D] dispatch 시작 id={}", id);
        dispatchService.dispatch(id);

        logDbState("dispatch 후");

        Notification result = notificationJpaRepository.findById(id).orElseThrow();
        // 재시도 없이 즉시 FAILED: retryCount 변화 없음
        assertThat(result.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(result.getRetryCount()).isEqualTo(0);
        assertThat(result.getNextRetryAt()).isNull();

        assertThat(dispatchHistoryJpaRepository.count()).isEqualTo(1L);
        DispatchHistory history = dispatchHistoryJpaRepository.findAll().get(0);
        assertThat(history.getStatus()).isEqualTo(DispatchStatus.FAILED);

        // FAILED 상태는 fetchPendingIds 조건(PENDING, RETRYING)에 해당하지 않음
        List<Long> pendingIds = dispatchService.fetchPendingIds(10);
        assertThat(pendingIds).doesNotContain(id);
        log.info("[시나리오D] 검증 완료 — 재시도 없이 즉시 FAILED, 스케줄러 재처리 대상 아님 ✓");
    }

    // ── 시나리오 E: MAX_RETRY 초과 → 최종 FAILED ──────────────────────────────

    // [시나리오] 채널이 계속 RetryableException → 3회 dispatch 후 FAILED 전이
    //           MAX_RETRY_COUNT=3: retryCount가 3에 도달하면 RETRYING이 아닌 FAILED로 전이
    // → retryCount=3, FAILED, DispatchHistory 3건(모두 FAILED), 4회차 스케줄러 대상 제외 검증
    @Test
    @DisplayName("시나리오E - MAX_RETRY(3) 초과 → 최종 FAILED, 이후 스케줄러 대상 제외")
    void maxRetryExceeded_finallyFailed_notPickedUpAgain() {
        Notification notification = saveNotification("max-retry-001");
        Long id = notification.getId();

        // 항상 RetryableException 발생하도록 설정
        channelSender.setFailCount(99);

        // 1차 dispatch (이벤트 핸들러 또는 스케줄러 첫 실행 역할)
        log.info("[시나리오E] 1차 dispatch id={}", id);
        dispatchService.dispatch(id);
        assertThat(notificationJpaRepository.findById(id).orElseThrow().getStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(notificationJpaRepository.findById(id).orElseThrow().getRetryCount()).isEqualTo(1);

        // 2차 dispatch: nextRetryAt 경과 후 스케줄러 재실행
        setNextRetryAtInPast(id);
        List<Long> batch1 = dispatchService.fetchPendingIds(10);
        assertThat(batch1).contains(id);
        log.info("[시나리오E] 2차 dispatch id={}", id);
        dispatchService.dispatch(id);
        assertThat(notificationJpaRepository.findById(id).orElseThrow().getStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(notificationJpaRepository.findById(id).orElseThrow().getRetryCount()).isEqualTo(2);

        // 3차 dispatch: MAX_RETRY_COUNT(3) 도달 → FAILED 전이 예상
        setNextRetryAtInPast(id);
        List<Long> batch2 = dispatchService.fetchPendingIds(10);
        assertThat(batch2).contains(id);
        log.info("[시나리오E] 3차 dispatch → MAX_RETRY 도달, FAILED 예상 id={}", id);
        dispatchService.dispatch(id);

        logDbState("3차 dispatch 후");

        Notification result = notificationJpaRepository.findById(id).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(result.getRetryCount()).isEqualTo(3);
        assertThat(result.getNextRetryAt()).isNull();

        // DispatchHistory: 3건 모두 FAILED
        List<DispatchHistory> histories = dispatchHistoryJpaRepository.findAll();
        assertThat(histories).hasSize(3);
        assertThat(histories.stream().allMatch(h -> h.getStatus() == DispatchStatus.FAILED)).isTrue();

        // 4회차: FAILED는 스케줄러 재처리 대상이 아님
        List<Long> batch3 = dispatchService.fetchPendingIds(10);
        assertThat(batch3).doesNotContain(id);
        log.info("[시나리오E] 검증 완료 — 3회 실패 후 FAILED, 이후 스케줄러 대상 제외 ✓");
    }
}
