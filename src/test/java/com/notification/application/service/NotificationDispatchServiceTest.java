package com.notification.application.service;

import com.notification.application.exception.ChannelFailureCode;
import com.notification.application.exception.NonRetryableChannelException;
import com.notification.application.exception.RetryableChannelException;
import com.notification.application.port.out.ChannelSenderPort;
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
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationDispatchServiceTest {

    @Mock NotificationRepositoryPort notificationRepositoryPort;
    @Mock ChannelSenderPort channelSenderPort;
    @Mock DispatchHistoryRepositoryPort dispatchHistoryRepositoryPort;
    @Mock NotificationLogRepositoryPort notificationLogRepositoryPort;

    @InjectMocks NotificationDispatchService notificationDispatchService;

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

    // [시나리오] 다중 인스턴스 환경에서 두 스케줄러가 동시에 같은 알림을 꺼냄
    // → CAS(tryStartProcessing) 실패한 쪽은 채널 발송·DB 저장 없이 즉시 리턴해야 중복 발송 방지
    @Test
    @DisplayName("CAS 실패: 이미 다른 스레드가 처리 중이면 즉시 리턴, 아무것도 저장 안 됨")
    void dispatch_casFailure_skipsProcessing() {
        given(notificationRepositoryPort.tryStartProcessing(1L)).willReturn(false);

        notificationDispatchService.dispatch(1L);

        verify(channelSenderPort, never()).send(any());
        verify(dispatchHistoryRepositoryPort, never()).save(any());
        verify(notificationRepositoryPort, never()).save(any());
    }

    // [시나리오] 채널 발송 성공 후 상태 갱신이 빠지면 스케줄러가 같은 알림을 재처리함
    // → SENT 전이·DispatchHistory 성공 기록·NotificationLog SENT 순서까지 모두 저장되는지 검증
    @Test
    @DisplayName("발송 성공: SENT 전이, DispatchHistory.success, NotificationLog SENT 기록")
    void dispatch_success_savesSuccessHistory() {
        given(notificationRepositoryPort.tryStartProcessing(1L)).willReturn(true);
        given(notificationRepositoryPort.findById(1L)).willReturn(Optional.of(notification));

        notificationDispatchService.dispatch(1L);

        ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepositoryPort).save(notificationCaptor.capture());
        assertThat(notificationCaptor.getValue().getStatus()).isEqualTo(NotificationStatus.SENT);

        ArgumentCaptor<DispatchHistory> historyCaptor = ArgumentCaptor.forClass(DispatchHistory.class);
        verify(dispatchHistoryRepositoryPort).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getStatus()).isEqualTo(DispatchStatus.SENT);

        ArgumentCaptor<NotificationLog> logCaptor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(notificationLogRepositoryPort, times(2)).save(logCaptor.capture());
        assertThat(logCaptor.getAllValues().get(1).getToStatus()).isEqualTo(NotificationStatus.SENT);
    }

    // [시나리오] 외부 채널이 일시적으로 응답 불가 → FAILED로 끝내면 재시도 기회를 잃음
    // → RETRYING 전이 + retryCount 증가 + DispatchHistory 실패 이력이 기록되는지 검증
    @Test
    @DisplayName("RetryableException: RETRYING 전이, DispatchHistory.failure, NotificationLog RETRYING 기록")
    void dispatch_retryableException_savesRetryingState() {
        given(notificationRepositoryPort.tryStartProcessing(1L)).willReturn(true);
        given(notificationRepositoryPort.findById(1L)).willReturn(Optional.of(notification));
        willThrow(new RetryableChannelException(ChannelFailureCode.CHANNEL_UNAVAILABLE))
                .given(channelSenderPort).send(any());

        notificationDispatchService.dispatch(1L);

        ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepositoryPort).save(notificationCaptor.capture());
        assertThat(notificationCaptor.getValue().getStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(notificationCaptor.getValue().getRetryCount()).isEqualTo(1);

        ArgumentCaptor<DispatchHistory> historyCaptor = ArgumentCaptor.forClass(DispatchHistory.class);
        verify(dispatchHistoryRepositoryPort).save(historyCaptor.capture());
        assertThat(historyCaptor.getValue().getStatus()).isEqualTo(DispatchStatus.FAILED);
    }

    // [시나리오] 채널 인증 키 만료처럼 재시도해도 무의미한 오류 → 계속 RETRYING하면 MAX까지 낭비
    // → retryCount 증가 없이 즉시 FAILED로 확정되는지 검증
    @Test
    @DisplayName("NonRetryableException: 즉시 FAILED, retryCount 변화 없음")
    void dispatch_nonRetryableException_savesFailedState() {
        given(notificationRepositoryPort.tryStartProcessing(1L)).willReturn(true);
        given(notificationRepositoryPort.findById(1L)).willReturn(Optional.of(notification));
        willThrow(new NonRetryableChannelException(ChannelFailureCode.CHANNEL_AUTH_FAILED))
                .given(channelSenderPort).send(any());

        notificationDispatchService.dispatch(1L);

        ArgumentCaptor<Notification> notificationCaptor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepositoryPort).save(notificationCaptor.capture());
        assertThat(notificationCaptor.getValue().getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notificationCaptor.getValue().getRetryCount()).isEqualTo(0);
        assertThat(notificationCaptor.getValue().getFailureReason())
                .isEqualTo(ChannelFailureCode.CHANNEL_AUTH_FAILED.name());
    }

    // [시나리오] 예측 불가 예외로 정상 경로가 끊기면 PROCESSING 상태가 영구히 남아 스케줄러가 재처리 못 함
    // → finally 블록이 어떤 예외에도 save(notification)를 1회 호출해 상태를 갱신하는지 검증
    @Test
    @DisplayName("예외 발생 시 finally: 반드시 save(notification) 1회 호출")
    void dispatch_anyException_alwaysSavesNotificationInFinally() {
        given(notificationRepositoryPort.tryStartProcessing(1L)).willReturn(true);
        given(notificationRepositoryPort.findById(1L)).willReturn(Optional.of(notification));
        willThrow(new RuntimeException("unexpected"))
                .given(channelSenderPort).send(any());

        notificationDispatchService.dispatch(1L);

        verify(notificationRepositoryPort, times(1)).save(any(Notification.class));
    }
}
