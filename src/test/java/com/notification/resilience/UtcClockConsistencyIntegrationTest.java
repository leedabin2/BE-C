package com.notification.resilience;

import com.notification.application.port.out.NotificationRepositoryPort;
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
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * P0-a 회귀. <b>DB 세션 시간대가 UTC가 아니어도</b> due·lease·재시도 예약이 같은 instant를 가리켜야 한다.
 *
 * <p>왜 필요한가: 저장된 DATETIME은 UTC 벽시각인데 {@code NOW()}/{@code CURRENT_TIMESTAMP}는
 * <b>세션 시간대</b>를 따른다. 세션이 +09:00이면 SQL의 "지금"만 9시간 앞서므로
 * ① 9시간 뒤 예약이 지금 due로 보이고 ② 아직 유효한 lease가 만료로 보인다.
 * 둘 다 조기 발송·조기 회수라는 중복 발송 경로다.
 * 운영은 Hikari가 세션을 UTC로 고정하지만, 그 설정 하나가 유일한 방어선이면 안 된다.
 *
 * <p>이 클래스는 그 설정을 <b>일부러 +09:00으로 깨뜨린 컨텍스트</b>로 띄운다.
 * LocalDateTime 바인딩은 세션 시간대 변환을 거치지 않으므로 저장 값은 그대로 UTC 벽시각이고,
 * 달라지는 것은 SQL 시간 함수뿐이다. 기준값은 전부 {@code UTC_TIMESTAMP(6)}로 직접 쓴다.
 * (MySQL 시간 함수: https://dev.mysql.com/doc/refman/8.0/en/date-and-time-functions.html)
 */
@TestPropertySource(properties = {
        "notification.scheduler.retry.fixed-delay-ms=3600000",
        "notification.scheduler.stuck.fixed-delay-ms=3600000",
        "notification.scheduler.metrics.fixed-delay-ms=3600000",
        "spring.datasource.hikari.data-source-properties.connectionTimeZone=+09:00"
})
class UtcClockConsistencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired NotificationJpaRepository notifications;
    @Autowired NotificationLogJpaRepository logs;
    @Autowired DispatchHistoryJpaRepository histories;
    @Autowired NotificationRepositoryPort repository;
    @Autowired NotificationDispatchService dispatch;
    @Autowired DispatchStateService state;
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

    /** DB가 읽는 "진짜 지금"(UTC). 세션 시간대와 무관하다. */
    private LocalDateTime databaseUtcNow() {
        return jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", (rs, rowNum) -> rs.getObject(1, LocalDateTime.class));
    }

    @Test
    @DisplayName("전제 확인: 이 컨텍스트의 세션은 +09:00이고 NOW()가 UTC보다 9시간 앞선다")
    void 세션시간대가_실제로_어긋나_있다() {
        assertThat(jdbc.queryForObject("SELECT @@session.time_zone", String.class)).isEqualTo("+09:00");
        Long skewSeconds = jdbc.queryForObject(
                "SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(6), NOW(6))", Long.class);
        assertThat(skewSeconds).as("이 전제가 깨지면 아래 테스트들은 아무것도 검증하지 못한다")
                .isBetween(9L * 3600 - 2, 9L * 3600 + 2);
    }

    @Test
    @DisplayName("currentTime()은 세션 시간대가 아니라 UTC 벽시각을 돌려준다")
    void currentTime은_UTC다() {
        assertThat(repository.currentTime())
                .isCloseTo(LocalDateTime.now(ZoneOffset.UTC), within(10, ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("1시간 뒤 예약은 세션이 +09:00이어도 due가 아니다 — 조회·CAS·집계 전부")
    void 미래_예약은_세션시간대로_앞당겨지지_않는다() {
        Long id = create("utc-future-scheduled");
        jdbc.update("UPDATE notification SET scheduled_at = TIMESTAMPADD(HOUR, 1, UTC_TIMESTAMP(6)) WHERE id = ?", id);

        assertThat(dispatch.fetchPendingIds(10)).as("due 후보 조회").doesNotContain(id);
        assertThat(state.claim(id)).as("claim CAS").isEmpty();
        assertThat(repository.getOperationalSnapshot().dueBacklog()).as("운영 snapshot 집계").isZero();

        assertThat(notifications.findById(id).orElseThrow().getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(sender.getSendCallCount()).isZero();
    }

    @Test
    @DisplayName("이미 due인 작업은 그대로 선점된다 — UTC 기준이 조회를 막는 부작용이 아님을 확인")
    void 현재_due는_정상적으로_선점된다() {
        Long id = create("utc-already-due");
        jdbc.update("UPDATE notification SET scheduled_at = TIMESTAMPADD(MINUTE, -1, UTC_TIMESTAMP(6)) WHERE id = ?", id);

        assertThat(dispatch.fetchPendingIds(10)).contains(id);
        assertThat(state.claim(id)).isPresent();
        assertThat(repository.getOperationalSnapshot().processingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("10분 남은 lease는 세션이 +09:00이어도 만료로 보이지 않는다 (진짜 만료 건만 회수)")
    void 유효한_lease는_조기_회수되지_않는다() {
        Long alive = create("utc-lease-alive");
        Long expired = create("utc-lease-expired");
        // 다른 UTC 노드가 정상 claim한 상태를 그대로 재현한다. 기준은 세션과 무관한 UTC_TIMESTAMP다.
        jdbc.update("UPDATE notification SET status = 'PROCESSING', processing_token = 'alive-token', "
                + "claimed_at = UTC_TIMESTAMP(6), lease_until = TIMESTAMPADD(MINUTE, 10, UTC_TIMESTAMP(6)) WHERE id = ?", alive);
        jdbc.update("UPDATE notification SET status = 'PROCESSING', processing_token = 'expired-token', "
                + "claimed_at = TIMESTAMPADD(MINUTE, -11, UTC_TIMESTAMP(6)), "
                + "lease_until = TIMESTAMPADD(MINUTE, -1, UTC_TIMESTAMP(6)) WHERE id = ?", expired);

        assertThat(repository.findExpiredProcessingLease(10).stream().map(Notification::getId))
                .containsExactly(expired);
        assertThat(repository.getOperationalSnapshot().expiredLeaseCount()).isEqualTo(1);

        NotificationDispatchService.StuckRecoveryResult result = dispatch.recoverStuck();

        assertThat(result.selected()).isEqualTo(1);
        assertThat(result.recovered()).isEqualTo(1);
        assertThat(notifications.findById(alive).orElseThrow().getStatus())
                .as("아직 발송 중인 소유자의 작업을 빼앗으면 중복 발송이 된다")
                .isEqualTo(NotificationStatus.PROCESSING);
        assertThat(notifications.findById(alive).orElseThrow().getProcessingToken()).isEqualTo("alive-token");
        assertThat(notifications.findById(expired).orElseThrow().getStatus()).isEqualTo(NotificationStatus.RETRYING);
    }

    @Test
    @DisplayName("실패 후 재시도 예약도 UTC 기준 1~2분 뒤다 (세션 시간대만큼 밀리지 않는다)")
    void 재시도_예약이_UTC_기준이다() {
        Long id = create("utc-retry-schedule");
        sender.setFailCount(1);

        LocalDateTime before = databaseUtcNow();
        dispatch.dispatch(id);
        LocalDateTime after = databaseUtcNow();

        LocalDateTime storedRetryAt = jdbc.queryForObject(
                "SELECT next_retry_at FROM notification WHERE id = ?",
                (rs, rowNum) -> rs.getObject(1, LocalDateTime.class), id);
        assertThat(notifications.findById(id).orElseThrow().getStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(storedRetryAt).isBetween(before.plusMinutes(1), after.plusMinutes(2));
    }
}
