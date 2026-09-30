package com.notification.resilience;

import com.notification.application.service.DispatchStateService;
import com.notification.application.service.NotificationDispatchService;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-c. <b>외부 호출을 시작해도 되는가</b>를 호출 직전에 DB로 다시 확인한다.
 *
 * <p>claim과 실제 send 사이에는 큐 대기가 있다. 그 사이에 lease가 만료되면 Stuck 회수가 같은 알림을
 * 다른 노드에 넘기는데, 옛 worker가 그대로 send를 시작하면 <b>메일이 두 번 나간다.</b>
 * token 일치만 보는 검사로는 부족하다 — 만료된 lease의 token은 회수 전까지 여전히 일치하기 때문이다.
 *
 * <pre>
 * 잔여 lease ≥ 예산   → 발송한다
 * 0 < 잔여 < 예산      → 아직 내 소유이고 호출 전이 확실하다 → token 조건으로 안전 반환(실패 예산 불변)
 * 잔여 ≤ 0            → 이미 만료. 손대지 않고 회수 경로에 맡긴다
 * 소유권 없음/DB 오류  → 새 외부 호출을 시작하지 않는다
 * </pre>
 *
 * <p>검사 직후 프로세스가 멈추는 구간은 그대로 남는다. 외부 exactly-once를 만드는 장치가 아니라
 * "명백히 늦은 호출"을 줄이는 장치다.
 */
@TestPropertySource(properties = {
        "notification.scheduler.retry.fixed-delay-ms=3600000",
        "notification.scheduler.stuck.fixed-delay-ms=3600000",
        "notification.scheduler.metrics.fixed-delay-ms=3600000"
})
class DispatchAdmissionIntegrationTest extends AbstractIntegrationTest {

    @Autowired NotificationDispatchService dispatch;
    @Autowired DispatchStateService state;
    @Autowired NotificationJpaRepository notifications;
    @Autowired NotificationLogJpaRepository logs;
    @Autowired DispatchHistoryJpaRepository histories;
    @Autowired TestChannelSenderAdapter sender;
    @Autowired JdbcTemplate jdbc;

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

    /** due 1건을 실제 batch 경로로 선점한다. worker에게는 ID/token만 넘어간다. */
    private DispatchStateService.ClaimedNotification claimOne() {
        List<DispatchStateService.ClaimedNotification> claimed = state.claimDueBatch(1);
        assertThat(claimed).hasSize(1);
        return claimed.get(0);
    }

    /** 큐에서 오래 기다린 상황을 만든다. 앱 시계가 아니라 DB의 lease 시각을 직접 당긴다. */
    private void setRemainingLease(Long id, int seconds) {
        jdbc.update("UPDATE notification SET lease_until = TIMESTAMPADD(SECOND, ?, UTC_TIMESTAMP(6)) WHERE id = ?",
                seconds, id);
    }

    @Test
    @DisplayName("잔여 lease가 발송 예산보다 적으면 호출하지 않고 원래 대기 상태로 안전 반환한다")
    void 잔여_lease가_부족하면_호출하지_않고_반환한다() {
        Long id = create("admission-short-lease");
        var workItem = claimOne();
        // 최악 발송 소요(215초)를 감당할 수 없는 잔여 lease. 아직 만료는 아니다
        setRemainingLease(id, 60);

        dispatch.dispatchClaimed(workItem);

        assertThat(sender.getSendCallCount()).as("완료 전에 lease가 끝날 호출은 시작하지 않는다").isZero();
        Notification after = notifications.findById(id).orElseThrow();
        assertThat(after.getStatus()).as("호출 전이 확실하므로 즉시 대기 상태로 돌려준다")
                .isEqualTo(NotificationStatus.PENDING);
        assertThat(after.getProcessingToken()).isNull();
        assertThat(after.getRetryCount()).as("호출하지 않았으므로 실패 예산은 그대로여야 한다").isZero();
        assertThat(histories.count()).as("시도가 없었으므로 시도 이력도 없다").isZero();
    }

    @Test
    @DisplayName("이미 lease가 만료됐으면 호출도 반환도 하지 않고 회수 경로에 맡긴다")
    void 만료된_lease는_손대지_않고_회수에_맡긴다() {
        Long id = create("admission-expired-lease");
        var workItem = claimOne();
        setRemainingLease(id, -1);

        dispatch.dispatchClaimed(workItem);

        assertThat(sender.getSendCallCount()).isZero();
        Notification after = notifications.findById(id).orElseThrow();
        assertThat(after.getStatus()).as("다른 노드가 회수 중일 수 있다. 상태를 바꾸지 않는다")
                .isEqualTo(NotificationStatus.PROCESSING);
        assertThat(after.getProcessingToken()).isEqualTo(workItem.processingToken());

        // 회수 스케줄러가 정상적으로 이어받는다
        assertThat(dispatch.recoverStuck().recovered()).isEqualTo(1);
        assertThat(notifications.findById(id).orElseThrow().getStatus()).isEqualTo(NotificationStatus.RETRYING);
    }

    @Test
    @DisplayName("반환된 작업은 다음 scan이 다시 집어 정상 발송한다 (유실이 아니라 지연)")
    void 반환된_작업은_다음_scan이_발송한다() {
        Long id = create("admission-requeue");
        var workItem = claimOne();
        setRemainingLease(id, 60);
        dispatch.dispatchClaimed(workItem);
        assertThat(sender.getSendCallCount()).isZero();

        dispatch.dispatchClaimed(claimOne());

        assertThat(sender.getSendCallCount()).isEqualTo(1);
        assertThat(notifications.findById(id).orElseThrow().getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    @DisplayName("lease가 충분하면 그대로 발송한다 — 검사가 정상 경로를 막지 않는다")
    void 충분한_lease는_정상_발송된다() {
        Long id = create("admission-healthy");

        dispatch.dispatchClaimed(claimOne());

        assertThat(sender.getSendCallCount()).isEqualTo(1);
        assertThat(notifications.findById(id).orElseThrow().getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    @DisplayName("소유권이 이미 다른 token으로 바뀌었으면 호출하지 않고 상태도 건드리지 않는다")
    void 소유권을_잃었으면_호출하지_않는다() {
        Long id = create("admission-fenced");
        var workItem = claimOne();
        jdbc.update("UPDATE notification SET processing_token = 'someone-else' WHERE id = ?", id);

        dispatch.dispatchClaimed(workItem);

        assertThat(sender.getSendCallCount()).isZero();
        Notification after = notifications.findById(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(NotificationStatus.PROCESSING);
        assertThat(after.getProcessingToken()).isEqualTo("someone-else");
    }

    @Test
    @DisplayName("재시도 대기 중이던 작업을 반환하면 RETRYING과 예약 시각이 그대로 복원된다")
    void 재시도_작업의_반환은_원래_예약을_보존한다() {
        Long id = create("admission-retrying");
        sender.setFailCount(1);
        dispatch.dispatch(id);                       // 1차 실패 → RETRYING + 미래 예약
        Notification afterFail = notifications.findById(id).orElseThrow();
        assertThat(afterFail.getStatus()).isEqualTo(NotificationStatus.RETRYING);
        LocalDateTime scheduledRetryAt = afterFail.getNextRetryAt();

        // 재시도 시각이 도래해 batch가 선점한 뒤, 큐에서 밀려 lease 예산이 모자라진 상황
        jdbc.update("UPDATE notification SET next_retry_at = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6)) WHERE id = ?", id);
        var workItem = claimOne();
        assertThat(workItem.previousStatus()).isEqualTo(NotificationStatus.RETRYING);
        setRemainingLease(id, 60);

        dispatch.dispatchClaimed(workItem);

        Notification after = notifications.findById(id).orElseThrow();
        assertThat(sender.getSendCallCount()).as("1차 실패 호출 외에 새 호출은 없다").isEqualTo(1);
        assertThat(after.getStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(after.getRetryCount()).isEqualTo(1);
        assertThat(after.getNextRetryAt()).as("반환이 예약 시각을 지우면 재시도 간격이 무너진다")
                .isEqualTo(workItem.previousNextRetryAt());
        assertThat(scheduledRetryAt).isNotNull();
    }
}
