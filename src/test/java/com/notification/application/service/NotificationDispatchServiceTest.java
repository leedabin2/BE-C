package com.notification.application.service;

import com.notification.application.exception.ChannelFailureCode;
import com.notification.application.exception.NonRetryableChannelException;
import com.notification.application.exception.RetryableChannelException;
import com.notification.application.port.out.ChannelSenderPort;
import com.notification.application.port.out.NotificationLogRepositoryPort;
import com.notification.application.port.out.NotificationRepositoryPort;
import com.notification.domain.Notification;
import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationStatus;
import com.notification.domain.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.*;

/**
 * 조율자 단위 테스트. DB 상태 전이는 {@link DispatchStateService} Mock으로 대체한다.
 * 여기서 검증하는 것은 "선점 → 외부 발송 → 결과 반영" 순서와, 예외 분류가 도메인 전이로 정확히 이어지는가다.
 */
@ExtendWith(MockitoExtension.class)
class NotificationDispatchServiceTest {

    @Mock NotificationRepositoryPort notificationRepositoryPort;
    @Mock ChannelSenderPort channelSenderPort;
    @Mock NotificationLogRepositoryPort notificationLogRepositoryPort;
    @Mock DispatchStateService dispatchStateService;

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

    // [시나리오] 다중 인스턴스에서 두 스케줄러가 같은 알림을 동시에 꺼냄
    // → 선점(TX-A) 실패한 쪽은 채널 발송도, 결과 반영(TX-B)도 하지 않아야 중복 발송이 없다
    @Test
    @DisplayName("선점 실패: 발송·결과 반영 모두 없이 즉시 리턴")
    void dispatch_claimFails_skipsEverything() {
        given(dispatchStateService.claim(1L)).willReturn(Optional.empty());

        notificationDispatchService.dispatch(1L);

        verify(channelSenderPort, never()).send(any());
        verify(dispatchStateService, never()).finish(any(), anyInt(), any());
    }

    // [시나리오] 외부 발송이 트랜잭션 안에 있으면 응답 시간만큼 커넥션·행 락이 묶인다
    // → 선점 커밋(claim) → 발송(send) → 결과 반영(finish) 순서가 지켜지는지, 조율자는 저장소를 직접 쓰지 않는지 검증
    @Test
    @DisplayName("발송 성공: claim → send → finish 순서. SENT 전이가 finish로 전달되고 회차는 1")
    void dispatch_success_claimSendFinishInOrder() {
        given(dispatchStateService.claim(1L)).willReturn(Optional.of(notification));

        notificationDispatchService.dispatch(1L);

        InOrder inOrder = inOrder(dispatchStateService, channelSenderPort);
        inOrder.verify(dispatchStateService).claim(1L);
        inOrder.verify(channelSenderPort).send(notification);
        inOrder.verify(dispatchStateService).finish(eq(notification), eq(1), isNull());

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        // 조율자는 트랜잭션이 없으므로 저장소를 직접 만지면 안 된다
        verifyNoInteractions(notificationRepositoryPort, notificationLogRepositoryPort);
    }

    // [시나리오] 외부 채널 일시 장애 → FAILED로 끝내면 재시도 기회를 잃음
    // → RETRYING + retryCount 1 + 실패 코드가 finish에 전달되는지 검증
    @Test
    @DisplayName("Retryable 예외: RETRYING(retryCount=1) 전이 후 실패 코드와 함께 finish")
    void dispatch_retryableException_finishesWithRetrying() {
        given(dispatchStateService.claim(1L)).willReturn(Optional.of(notification));
        willThrow(new RetryableChannelException(ChannelFailureCode.CHANNEL_UNAVAILABLE))
                .given(channelSenderPort).send(any());

        notificationDispatchService.dispatch(1L);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(dispatchStateService).finish(captor.capture(), eq(1), eq("CHANNEL_UNAVAILABLE"));
        assertThat(captor.getValue().getStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(captor.getValue().getRetryCount()).isEqualTo(1);
        assertThat(captor.getValue().getNextRetryAt()).isNotNull();
    }

    // [시나리오] 인증 키 만료처럼 재시도해도 무의미한 오류 → 계속 RETRYING하면 MAX까지 낭비
    // → retryCount 증가 없이 즉시 FAILED로 finish되는지 검증
    @Test
    @DisplayName("NonRetryable 예외: 즉시 FAILED, retryCount 변화 없음")
    void dispatch_nonRetryableException_finishesWithFailed() {
        given(dispatchStateService.claim(1L)).willReturn(Optional.of(notification));
        willThrow(new NonRetryableChannelException(ChannelFailureCode.CHANNEL_AUTH_FAILED))
                .given(channelSenderPort).send(any());

        notificationDispatchService.dispatch(1L);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(dispatchStateService).finish(captor.capture(), eq(1), eq("CHANNEL_AUTH_FAILED"));
        assertThat(captor.getValue().getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(captor.getValue().getRetryCount()).isEqualTo(0);
        assertThat(captor.getValue().getFailureReason()).isEqualTo("CHANNEL_AUTH_FAILED");
    }

    // [시나리오] 어댑터가 분류하지 못한 예외(NPE 등)가 새면 PROCESSING이 남아 10분 뒤 Stuck 복구까지 기다려야 함
    // → 원인 불명은 보수적으로 Retryable(CHANNEL_UNAVAILABLE)로 분류해 즉시 finish되는지 검증
    @Test
    @DisplayName("원인 불명 예외: CHANNEL_UNAVAILABLE로 RETRYING 처리, finish 반드시 1회")
    void dispatch_unknownException_treatedAsRetryable() {
        given(dispatchStateService.claim(1L)).willReturn(Optional.of(notification));
        willThrow(new RuntimeException("unexpected")).given(channelSenderPort).send(any());

        notificationDispatchService.dispatch(1L);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(dispatchStateService, times(1)).finish(captor.capture(), eq(1), eq("CHANNEL_UNAVAILABLE"));
        assertThat(captor.getValue().getStatus()).isEqualTo(NotificationStatus.RETRYING);
    }

    // [시나리오] 결과 반영(TX-B) 중 DB 장애 → 여기서 예외를 삼키면 "발송했는데 기록 없음"이 조용히 묻힘
    // → 예외가 호출자(스케줄러·이벤트 핸들러)로 전파되는지 검증. 행은 PROCESSING으로 남아 Stuck 복구가 회수한다
    @Test
    @DisplayName("finish의 DB 예외는 삼키지 않고 호출자로 전파")
    void dispatch_finishThrows_propagates() {
        given(dispatchStateService.claim(1L)).willReturn(Optional.of(notification));
        willThrow(new IllegalStateException("db down"))
                .given(dispatchStateService).finish(any(), anyInt(), any());

        assertThatThrownBy(() -> notificationDispatchService.dispatch(1L))
                .isInstanceOf(IllegalStateException.class);
    }
}
