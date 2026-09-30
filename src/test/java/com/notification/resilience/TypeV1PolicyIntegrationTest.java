package com.notification.resilience;

import com.notification.application.port.in.RegisterNotificationUseCase;
import com.notification.application.port.in.command.RegisterNotificationCommand;
import com.notification.application.port.out.NotificationRepositoryPort;
import com.notification.application.service.DispatchStateService;
import com.notification.application.service.NotificationDispatchService;
import com.notification.domain.Notification;
import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationStatus;
import com.notification.domain.NotificationType;
import com.notification.domain.PolicyVersion;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1-b. TYPE_V1을 켠 상태에서 <b>기한이 실제로 지켜지는가</b>와 <b>기존 행이 그대로 도는가</b>.
 *
 * <p>정책 계산 자체는 단위 테스트가 본다. 여기서는 계산 결과가 DB 조건·선점·호출 방어·정리까지
 * 일관되게 반영되는지, 그리고 기한 없는 LEGACY_V0 행이 같은 코드에서 예전처럼 동작하는지를 본다.
 * 한 경로만 기한을 빠뜨려도 만료된 알림이 그 경로로 빠져나간다.
 */
@TestPropertySource(properties = {
        "notification.policy.type-v1-enabled=true",
        "notification.scheduler.retry.fixed-delay-ms=3600000",
        "notification.scheduler.stuck.fixed-delay-ms=3600000",
        "notification.scheduler.metrics.fixed-delay-ms=3600000",
        "notification.scheduler.expiry.fixed-delay-ms=3600000"
})
class TypeV1PolicyIntegrationTest extends AbstractIntegrationTest {

    @Autowired RegisterNotificationUseCase register;
    @Autowired NotificationDispatchService dispatch;
    @Autowired DispatchStateService state;
    @Autowired NotificationRepositoryPort repository;
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

    private RegisterNotificationCommand command(NotificationType type, NotificationChannel channel,
                                                String eventId, LocalDateTime scheduledAt,
                                                LocalDateTime expiresAt) {
        return new RegisterNotificationCommand(1L, type, channel,
                channel == NotificationChannel.EMAIL ? "user@test.com" : null,
                eventId, 10L, "PAYMENT", "{}", scheduledAt, expiresAt);
    }

    private Long registerPayment(String eventId) {
        return register.register(command(NotificationType.PAYMENT_CONFIRMED, NotificationChannel.EMAIL,
                eventId, null, null)).id();
    }

    /**
     * 거동 검증용 TYPE_V1 행을 직접 만든다.
     *
     * <p>{@code register()}를 쓰면 커밋 직후 즉시 발송 이벤트가 비동기로 같은 행을 가져가, 테스트가
     * 의도한 시점에 상태를 관찰할 수 없다. 등록 경로 자체는 위의 정책 부여 테스트가 검증한다.
     */
    private Long insertTypeV1(String key, NotificationType type, LocalDateTime expiresAt) {
        LocalDateTime now = dbNow();
        return notifications.saveAndFlush(Notification.builder()
                .receiverId(1L).notificationType(type).channel(NotificationChannel.EMAIL)
                .channelTarget("user@test.com").eventId(key).idempotencyKey(key)
                .policyVersion(PolicyVersion.TYPE_V1).eligibleAt(now).expiresAt(expiresAt)
                .build()).getId();
    }

    private Long insertPayment(String key) {
        return insertTypeV1(key, NotificationType.PAYMENT_CONFIRMED, dbNow().plusHours(24));
    }

    private LocalDateTime dbNow() {
        return repository.currentTime();
    }

    @Test
    @DisplayName("신규 EMAIL 등록은 TYPE_V1과 타입별 기한을 갖는다 (결제 = 24시간)")
    void 신규_EMAIL은_TYPE_V1으로_등록된다() {
        LocalDateTime before = dbNow();

        Long id = registerPayment("v1-payment");

        Notification saved = notifications.findById(id).orElseThrow();
        assertThat(saved.policyVersion()).isEqualTo(PolicyVersion.TYPE_V1);
        assertThat(saved.getEligibleAt()).isBetween(before, dbNow());
        assertThat(saved.getExpiresAt()).isBetween(before.plusHours(24), dbNow().plusHours(24));
    }

    @Test
    @DisplayName("IN_APP은 이번 단계의 기한 정책 대상이 아니다 — 알림함 저장은 메일 업체 호출이 아니다")
    void 인앱은_LEGACY로_남는다() {
        Long id = register.register(command(NotificationType.PAYMENT_CONFIRMED, NotificationChannel.IN_APP,
                "v1-inapp", null, null)).id();

        Notification saved = notifications.findById(id).orElseThrow();
        assertThat(saved.policyVersion()).isEqualTo(PolicyVersion.LEGACY_V0);
        assertThat(saved.getExpiresAt()).isNull();
    }

    @Test
    @DisplayName("강의 시작 안내는 생산자가 기한을 줘야 한다 — '등록 후 N시간'으로 대신할 수 없다")
    void 강의_안내는_기한이_필수다() {
        assertThatThrownBy(() -> register.register(command(NotificationType.LECTURE_START_REMINDER,
                NotificationChannel.EMAIL, "v1-lecture-no-ttl", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expiresAt");

        assertThat(notifications.count()).as("400으로 거절된 요청은 행을 만들지 않는다").isZero();
    }

    @Test
    @DisplayName("다음 달 예약은 오늘 만료되지 않는다 — 기한은 등록 시각이 아니라 발송 가능 시각 기준이다")
    void 미래_예약은_오늘_만료되지_않는다() {
        LocalDateTime nextMonth = dbNow().plusDays(30);

        Long id = register.register(command(NotificationType.PAYMENT_CONFIRMED, NotificationChannel.EMAIL,
                "v1-future", nextMonth, null)).id();

        Notification saved = notifications.findById(id).orElseThrow();
        assertThat(saved.getEligibleAt()).isEqualTo(nextMonth);
        assertThat(saved.getExpiresAt()).isEqualTo(nextMonth.plusHours(24));
        assertThat(saved.getScheduledAt()).as("업무 예약값은 그대로 보존한다").isEqualTo(nextMonth);
    }

    @Test
    @DisplayName("생산자 입력이 기본 기한보다 길어도 기본 기한을 넘기지 않는다")
    void 입력으로_기한을_늘릴_수_없다() {
        Long id = register.register(command(NotificationType.PAYMENT_CONFIRMED, NotificationChannel.EMAIL,
                "v1-longer", null, dbNow().plusDays(7))).id();

        assertThat(notifications.findById(id).orElseThrow().getExpiresAt())
                .isBefore(dbNow().plusHours(25));
    }

    @Test
    @DisplayName("기한이 지난 대기 알림은 선점되지 않는다 — 외부 호출 0")
    void 만료된_알림은_선점되지_않는다() {
        Long id = insertPayment("v1-expired-claim");
        jdbc.update("UPDATE notification SET expires_at = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6)) WHERE id = ?", id);

        assertThat(state.claimDueBatch(10)).isEmpty();
        assertThat(state.claim(id)).isEmpty();
        assertThat(sender.getSendCallCount()).isZero();
    }

    @Test
    @DisplayName("선점 뒤 기한이 지나면 호출 직전에 멈춘다 — 큐에서 기다리는 동안 만료될 수 있다")
    void 호출_직전에도_기한을_본다() {
        Long id = insertPayment("v1-expire-in-queue");
        var workItem = state.claimDueBatch(1).get(0);
        jdbc.update("UPDATE notification SET expires_at = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6)) WHERE id = ?", id);

        dispatch.dispatchClaimed(workItem);

        assertThat(sender.getSendCallCount()).isZero();
        assertThat(histories.count()).as("호출하지 않았으므로 시도 이력도 없다").isZero();
    }

    @Test
    @DisplayName("대기 중 만료된 알림은 정리 작업이 FAILED(EXPIRED)로 끝내고 이력을 남긴다")
    void 만료_정리가_대기_알림을_끝낸다() {
        Long expired = insertPayment("v1-cleanup-expired");
        Long alive = insertPayment("v1-cleanup-alive");
        jdbc.update("UPDATE notification SET expires_at = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6)) WHERE id = ?", expired);

        int cleaned = dispatch.expireOverdue();

        assertThat(cleaned).isEqualTo(1);
        Notification after = notifications.findById(expired).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(after.getFailureReason()).isEqualTo("EXPIRED");
        assertThat(notifications.findById(alive).orElseThrow().getStatus())
                .as("기한이 남은 알림은 건드리지 않는다").isEqualTo(NotificationStatus.PENDING);
        assertThat(logs.findAll()).anyMatch(l -> "EXPIRED".equals(l.getReason()));
    }

    @Test
    @DisplayName("정리 작업은 PROCESSING을 일괄 FAILED로 덮지 않는다 — 이미 호출 중일 수 있다")
    void 정리는_발송중인_작업을_건드리지_않는다() {
        Long id = insertPayment("v1-cleanup-processing");
        state.claimDueBatch(1);
        jdbc.update("UPDATE notification SET expires_at = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6)) WHERE id = ?", id);

        assertThat(dispatch.expireOverdue()).isZero();
        assertThat(notifications.findById(id).orElseThrow().getStatus()).isEqualTo(NotificationStatus.PROCESSING);
    }

    @Test
    @DisplayName("TYPE_V1 결제 알림은 3회 실패로 끝나지 않는다 — 타입별 예산이 적용된다")
    void 타입별_예산이_실제로_적용된다() {
        Long id = insertPayment("v1-budget");
        sender.setFailCount(10);

        for (int attempt = 0; attempt < 3; attempt++) {
            jdbc.update("UPDATE notification SET next_retry_at = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6)) WHERE id = ?", id);
            dispatch.dispatch(id);
        }

        Notification after = notifications.findById(id).orElseThrow();
        assertThat(after.getStatus()).as("LEGACY였다면 3회에서 FAILED가 된다").isEqualTo(NotificationStatus.RETRYING);
        assertThat(after.getRetryCount()).isEqualTo(3);
        assertThat(sender.getSendCallCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("기한 없는 기존 행(LEGACY_V0)은 같은 코드에서 예전처럼 발송된다")
    void 기존_행은_그대로_동작한다() {
        Long id = insertPayment("v1-legacy-coexist");
        // 컬럼 추가 전에 등록된 행을 재현한다: 정책 버전·기한·새 카운터가 전부 NULL
        jdbc.update("UPDATE notification SET policy_version = NULL, expires_at = NULL, eligible_at = NULL, "
                + "cycle_recovery_count = NULL, attempt_cycle = NULL, attempt_intent_count = NULL WHERE id = ?", id);

        dispatch.dispatch(id);

        Notification after = notifications.findById(id).orElseThrow();
        assertThat(after.policyVersion()).isEqualTo(PolicyVersion.LEGACY_V0);
        assertThat(after.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(sender.getSendCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("lease 회수는 TYPE_V1의 실패 예산을 쓰지 않고 회수 횟수만 올린다")
    void 회수는_실패_예산과_분리된다() {
        Long id = insertPayment("v1-recovery-budget");
        state.claimDueBatch(1);
        jdbc.update("UPDATE notification SET lease_until = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6)) WHERE id = ?", id);

        assertThat(dispatch.recoverStuck().recovered()).isEqualTo(1);

        Notification after = notifications.findById(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(after.getRetryCount()).as("회수는 발송 실패가 아니다").isZero();
        assertThat(after.getCycleRecoveryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("회수된 TYPE_V1 알림(RETRYING·retryCount=0)을 즉시 경로가 선점하면 이전 상태를 RETRYING으로 확보한다")
    void 회수된_알림의_이전_상태를_선점_TX에서_확보한다() {
        Long id = insertPayment("v1-recovered-claim-state");
        state.claimDueBatch(1);
        jdbc.update("UPDATE notification SET lease_until = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6)) WHERE id = ?", id);
        assertThat(dispatch.recoverStuck().recovered()).isEqualTo(1);
        // TYPE_V1 회수는 실패 예산을 쓰지 않는다 → RETRYING인데 retryCount는 0이다.
        Notification recovered = notifications.findById(id).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(recovered.getRetryCount()).isZero();
        LocalDateTime scheduledRetryAt = recovered.getNextRetryAt();
        jdbc.update("UPDATE notification SET next_retry_at = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6)) WHERE id = ?", id);
        LocalDateTime dueRetryAt = notifications.findById(id).orElseThrow().getNextRetryAt();

        var claimed = state.claim(id).orElseThrow();

        // retryCount로 추정하면 여기서 PENDING이 나온다. 선점 TX가 실제 값을 확보해야 한다.
        assertThat(claimed.workItem().previousStatus())
                .as("회수 뒤 RETRYING은 retryCount가 0이라 추정으로는 알 수 없다")
                .isEqualTo(NotificationStatus.RETRYING);
        assertThat(claimed.workItem().previousNextRetryAt()).isEqualTo(dueRetryAt);
        assertThat(scheduledRetryAt).isNotNull();
    }

    @Test
    @DisplayName("호출 전 반환은 회수된 알림을 PENDING이 아니라 RETRYING으로 되돌린다")
    void 호출_전_반환이_RETRYING을_복원한다() {
        Long id = insertPayment("v1-recovered-release");
        state.claimDueBatch(1);
        jdbc.update("UPDATE notification SET lease_until = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6)) WHERE id = ?", id);
        dispatch.recoverStuck();
        jdbc.update("UPDATE notification SET next_retry_at = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6)) WHERE id = ?", id);
        LocalDateTime dueRetryAt = notifications.findById(id).orElseThrow().getNextRetryAt();
        var claimed = state.claim(id).orElseThrow();

        state.releaseClaim(claimed.workItem(), DispatchStateService.LEASE_BUDGET_INSUFFICIENT);

        Notification after = notifications.findById(id).orElseThrow();
        assertThat(after.getStatus()).as("PENDING으로 되돌리면 재시도 이력이 최초 발송처럼 보인다")
                .isEqualTo(NotificationStatus.RETRYING);
        assertThat(after.getNextRetryAt()).isEqualTo(dueRetryAt);
        assertThat(after.getRetryCount()).as("반환은 실패가 아니다").isZero();
        assertThat(after.getProcessingToken()).isNull();
        assertThat(sender.getSendCallCount()).isZero();
    }
}
