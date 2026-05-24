package com.notification.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class NotificationTest {

    private Notification notification;

    @BeforeEach
    void setUp() {
        notification = Notification.builder()
                .receiverId(1L)
                .notificationType(NotificationType.PAYMENT_CONFIRMED)
                .channel(NotificationChannel.EMAIL)
                .eventId("evt-001")
                .idempotencyKey("test-key")
                .build();
    }

    // ── markSent ──────────────────────────────────────────────────────────────

    // [시나리오] 채널 발송 후 상태 갱신이 빠지면 스케줄러가 같은 알림을 재처리함
    // → markSent() 호출 시 SENT로 전이되는지 검증
    @Test
    @DisplayName("markSent: SENT 상태로 전이된다")
    void markSent_changesStatusToSent() {
        notification.markSent();

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    // ── markRetrying ──────────────────────────────────────────────────────────

    // [시나리오] 첫 실패 후 너무 짧은 간격으로 재시도하면 채널에 불필요한 부하 발생
    // → 1회 실패 시 RETRYING 전이 + retryCount=1 + nextRetryAt이 1분 후인지 검증
    @Test
    @DisplayName("markRetrying 1회: RETRYING 상태, retryCount=1, nextRetryAt=1분 후")
    void markRetrying_firstAttempt_setsRetrying() {
        notification.markRetrying("CHANNEL_UNAVAILABLE");

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(notification.getRetryCount()).isEqualTo(1);
        assertThat(notification.getFailureReason()).isEqualTo("CHANNEL_UNAVAILABLE");
        assertThat(notification.getNextRetryAt())
                .isCloseTo(LocalDateTime.now().plusMinutes(1), within(5, ChronoUnit.SECONDS));
    }

    // [시나리오] 재시도 간격이 고정이면 채널 장애 지속 시 지속적으로 실패만 쌓임
    // → 2회 실패 시 지수 백오프가 적용돼 nextRetryAt이 5분 후인지 검증
    @Test
    @DisplayName("markRetrying 2회: RETRYING 상태, retryCount=2, nextRetryAt=5분 후")
    void markRetrying_secondAttempt_setsRetrying() {
        notification.markRetrying("CHANNEL_UNAVAILABLE");
        notification.markRetrying("CHANNEL_UNAVAILABLE");

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(notification.getRetryCount()).isEqualTo(2);
        assertThat(notification.getNextRetryAt())
                .isCloseTo(LocalDateTime.now().plusMinutes(5), within(5, ChronoUnit.SECONDS));
    }

    // [시나리오] MAX 없이 무한 재시도하면 좀비 알림이 스케줄러를 영원히 점유
    // → 3회 도달 시 FAILED로 확정되고 nextRetryAt=null이 되는지 검증
    @Test
    @DisplayName("markRetrying 3회(MAX 초과): FAILED 전이, nextRetryAt=null")
    void markRetrying_thirdAttempt_exceedsMax_changeStatusToFailed() {
        notification.markRetrying("CHANNEL_UNAVAILABLE");
        notification.markRetrying("CHANNEL_UNAVAILABLE");
        notification.markRetrying("CHANNEL_UNAVAILABLE");

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getRetryCount()).isEqualTo(3);
        assertThat(notification.getNextRetryAt()).isNull();
    }

    // ── markFailed ────────────────────────────────────────────────────────────

    // [시나리오] 인증 키 만료처럼 재시도해도 무의미한 오류를 RETRYING으로 처리하면 MAX까지 낭비
    // → markFailed() 시 retryCount 증가 없이 즉시 FAILED로 확정되는지 검증
    @Test
    @DisplayName("markFailed: 즉시 FAILED, retryCount 변화 없음, nextRetryAt=null")
    void markFailed_changesStatusToFailed_withoutIncrementingRetryCount() {
        notification.markFailed("CHANNEL_AUTH_FAILED");

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getRetryCount()).isEqualTo(0);
        assertThat(notification.getFailureReason()).isEqualTo("CHANNEL_AUTH_FAILED");
        assertThat(notification.getNextRetryAt()).isNull();
    }

    // ── resetForManualRetry ───────────────────────────────────────────────────

    // [시나리오] retryCount가 MAX인 채로 PENDING 복귀하면 스케줄러가 즉시 FAILED로 재확정함
    // → 수동 재시도 시 retryCount=0 초기화 + PENDING 전이 + nextRetryAt=now인지 검증
    @Test
    @DisplayName("resetForManualRetry: retryCount=0, PENDING, nextRetryAt=now")
    void resetForManualRetry_resetsStateForRetry() {
        notification.markRetrying("CHANNEL_UNAVAILABLE");
        notification.markRetrying("CHANNEL_UNAVAILABLE");
        notification.markRetrying("CHANNEL_UNAVAILABLE");

        notification.resetForManualRetry();

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.getRetryCount()).isEqualTo(0);
        assertThat(notification.getNextRetryAt())
                .isCloseTo(LocalDateTime.now(), within(5, ChronoUnit.SECONDS));
    }

    // ── canRetry ──────────────────────────────────────────────────────────────

    // [시나리오] canRetry=false인 알림을 스케줄러가 재처리하면 MAX 이후에도 계속 시도됨
    // → retryCount가 0·1·2일 때 canRetry()가 true를 반환하는지 검증
    @Test
    @DisplayName("canRetry: retryCount < MAX(3)이면 true")
    void canRetry_returnsTrueWhenBelowMax() {
        assertThat(notification.canRetry()).isTrue();

        notification.markRetrying("CHANNEL_UNAVAILABLE");
        assertThat(notification.canRetry()).isTrue();

        notification.markRetrying("CHANNEL_UNAVAILABLE");
        assertThat(notification.canRetry()).isTrue();
    }

    // [시나리오] MAX 도달 후에도 canRetry=true면 스케줄러가 FAILED 알림을 계속 재처리
    // → retryCount=3(MAX) 도달 시 canRetry()가 false를 반환하는지 검증
    @Test
    @DisplayName("canRetry: retryCount >= MAX(3)이면 false")
    void canRetry_returnsFalseWhenAtMax() {
        notification.markRetrying("CHANNEL_UNAVAILABLE");
        notification.markRetrying("CHANNEL_UNAVAILABLE");
        notification.markRetrying("CHANNEL_UNAVAILABLE");

        assertThat(notification.canRetry()).isFalse();
    }

    // ── isFailed ──────────────────────────────────────────────────────────────

    // [시나리오] isFailed 판단이 잘못되면 수동 재시도 API가 PENDING·RETRYING 알림에도 실행됨
    // → FAILED 상태일 때만 isFailed()=true, 그 외 상태에선 false인지 검증
    @Test
    @DisplayName("isFailed: FAILED 상태일 때만 true")
    void isFailed_returnsTrueOnlyWhenFailed() {
        assertThat(notification.isFailed()).isFalse();

        notification.markFailed("CHANNEL_AUTH_FAILED");

        assertThat(notification.isFailed()).isTrue();
    }

    // ── isInApp ───────────────────────────────────────────────────────────────

    // [시나리오] EMAIL 알림에 isRead를 적용하면 외부 서버 수신 여부를 앱에서 임의 조작하게 됨
    // → isInApp()이 IN_APP 채널일 때만 true를 반환하는지 검증
    @Test
    @DisplayName("isInApp: IN_APP 채널일 때만 true")
    void isInApp_returnsTrueOnlyForInAppChannel() {
        Notification inApp = Notification.builder()
                .receiverId(1L)
                .notificationType(NotificationType.PAYMENT_CONFIRMED)
                .channel(NotificationChannel.IN_APP)
                .eventId("evt-002")
                .idempotencyKey("test-key-2")
                .build();

        assertThat(notification.isInApp()).isFalse();
        assertThat(inApp.isInApp()).isTrue();
    }
}
