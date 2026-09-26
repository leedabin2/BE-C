package com.notification.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationTest {

    private static final LocalDateTime FIRST_RETRY_AT = LocalDateTime.of(2026, 9, 23, 3, 1);
    private static final LocalDateTime SECOND_RETRY_AT = LocalDateTime.of(2026, 9, 23, 3, 5);

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
    // → 수동 재시도 시 retryCount=0 초기화 + PENDING 전이 + 호출자가 준 DB 기준 시각인지 검증
    @Test
    @DisplayName("resetForManualRetry: retryCount=0, PENDING, 호출자가 준 due 시각")
    void resetForManualRetry_resetsStateForRetry() {
        notification.applyRetryableFailure(NotificationStatus.RETRYING, null, "CHANNEL_UNAVAILABLE", FIRST_RETRY_AT);
        notification.applyRetryableFailure(NotificationStatus.RETRYING, null, "CHANNEL_UNAVAILABLE", SECOND_RETRY_AT);
        notification.applyRetryableFailure(NotificationStatus.FAILED, "RETRY_EXHAUSTED", "CHANNEL_UNAVAILABLE", null);

        notification.resetForManualRetry(FIRST_RETRY_AT);

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.getRetryCount()).isEqualTo(0);
        assertThat(notification.getNextRetryAt())
                .isEqualTo(FIRST_RETRY_AT);
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
