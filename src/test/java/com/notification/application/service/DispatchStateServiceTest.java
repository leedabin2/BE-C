package com.notification.application.service;

import com.notification.application.port.out.DispatchHistoryRepositoryPort;
import com.notification.application.port.out.NotificationLogRepositoryPort;
import com.notification.application.port.out.NotificationRepositoryPort;
import com.notification.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * TX-A(claim) · TX-B(finish) 단위 테스트.
 * 트랜잭션 경계 자체는 통합 테스트가 확인하고, 여기서는 조건부 UPDATE 결과(1행/0행)에 따른 분기를 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class DispatchStateServiceTest {

    @Mock NotificationRepositoryPort notificationRepositoryPort;
    @Mock DispatchHistoryRepositoryPort dispatchHistoryRepositoryPort;
    @Mock NotificationLogRepositoryPort notificationLogRepositoryPort;

    @InjectMocks DispatchStateService dispatchStateService;

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

    // [시나리오] 두 스레드가 동시에 선점 → 조건부 UPDATE는 한 쪽만 1행
    // → 0행이면 조회·이력 없이 empty, 1행이면 스냅샷 + DISPATCH_START 이력
    @Test
    @DisplayName("claim: 선점 0행이면 empty, 조회·이력 없음")
    void claim_casFails_returnsEmpty() {
        given(notificationRepositoryPort.tryStartProcessing(1L)).willReturn(false);

        assertThat(dispatchStateService.claim(1L)).isEmpty();

        verify(notificationRepositoryPort, never()).findById(any());
        verify(notificationLogRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("claim: 선점 1행이면 스냅샷 반환 + DISPATCH_START 이력")
    void claim_casSucceeds_returnsSnapshotAndLogs() {
        given(notificationRepositoryPort.tryStartProcessing(1L)).willReturn(true);
        given(notificationRepositoryPort.findById(1L)).willReturn(Optional.of(notification));

        assertThat(dispatchStateService.claim(1L)).contains(notification);

        ArgumentCaptor<NotificationLog> logCaptor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepositoryPort).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getToStatus()).isEqualTo(NotificationStatus.PROCESSING);
        assertThat(logCaptor.getValue().getReason()).isEqualTo("DISPATCH_START");
    }

    // [시나리오] 정상 경로: 내가 선점한 PROCESSING이 그대로 있음 → 결과 반영 1행
    // → 성공 이력 + PROCESSING→SENT 전이 로그
    @Test
    @DisplayName("finish 1행(성공): DispatchHistory.success + PROCESSING→SENT 로그")
    void finish_applied_success_recordsHistoryAndTransition() {
        notification.markSent();
        given(notificationRepositoryPort.tryFinishProcessing(notification)).willReturn(true);

        dispatchStateService.finish(notification, 1, null);

        ArgumentCaptor<DispatchHistory> h = ArgumentCaptor.forClass(DispatchHistory.class);
        verify(dispatchHistoryRepositoryPort).save(h.capture());
        assertThat(h.getValue().getStatus()).isEqualTo(DispatchStatus.SENT);
        assertThat(h.getValue().getAttemptNumber()).isEqualTo(1);

        ArgumentCaptor<NotificationLog> l = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepositoryPort).save(l.capture());
        assertThat(l.getValue().getFromStatus()).isEqualTo(NotificationStatus.PROCESSING);
        assertThat(l.getValue().getToStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    @DisplayName("finish 1행(실패): DispatchHistory.failure(code) + PROCESSING→RETRYING 로그")
    void finish_applied_failure_recordsHistoryAndTransition() {
        notification.markRetrying("CHANNEL_UNAVAILABLE");
        given(notificationRepositoryPort.tryFinishProcessing(notification)).willReturn(true);

        dispatchStateService.finish(notification, 1, "CHANNEL_UNAVAILABLE");

        ArgumentCaptor<DispatchHistory> h = ArgumentCaptor.forClass(DispatchHistory.class);
        verify(dispatchHistoryRepositoryPort).save(h.capture());
        assertThat(h.getValue().getStatus()).isEqualTo(DispatchStatus.FAILED);
        assertThat(h.getValue().getErrorMessage()).isEqualTo("CHANNEL_UNAVAILABLE");

        ArgumentCaptor<NotificationLog> l = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepositoryPort).save(l.capture());
        assertThat(l.getValue().getToStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(l.getValue().getReason()).isEqualTo("CHANNEL_UNAVAILABLE");
    }

    // [시나리오] 외부 발송이 10분 넘게 걸려 Stuck 복구가 먼저 PENDING으로 되돌림 → 결과 반영 0행
    // → 상태 전이 로그 대신 LATE_RESULT_IGNORED를 남기고, 시도 이력은 그대로 남긴다 (시도는 실제로 있었다)
    @Test
    @DisplayName("finish 0행(늦은 결과): 이력은 남기고 LATE_RESULT_IGNORED로 기록")
    void finish_notApplied_recordsLateResult() {
        notification.markSent();
        given(notificationRepositoryPort.tryFinishProcessing(notification)).willReturn(false);

        dispatchStateService.finish(notification, 1, null);

        verify(dispatchHistoryRepositoryPort).save(any(DispatchHistory.class));

        ArgumentCaptor<NotificationLog> l = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepositoryPort).save(l.capture());
        assertThat(l.getValue().getReason()).isEqualTo(DispatchStateService.LATE_RESULT_IGNORED);
        // 시도 결과(SENT)는 남기되, reason으로 "반영되지 않았다"를 구분한다
        assertThat(l.getValue().getToStatus()).isEqualTo(NotificationStatus.SENT);
    }
}
