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
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.time.LocalDateTime;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
    @Mock com.notification.application.port.out.ProviderCircuitPort providerCircuitPort;
    @Spy RetrySchedulePolicy retrySchedulePolicy = new RetrySchedulePolicy(() -> 42);
    // 정책은 Mock이 아니라 실물을 쓴다. 지연·기한 판정 자체가 이 테스트의 검증 대상이기 때문이다.
    @Spy RetryDecisionPolicy retryDecisionPolicy = new RetryDecisionPolicy(new RetrySchedulePolicy(() -> 42));

    @InjectMocks NotificationDispatchService notificationDispatchService;

    private Notification notification;

    /** 선점 결과. 반환 계약은 선점 TX가 확보한 값이므로 테스트도 명시적으로 만든다. */
    private DispatchStateService.ClaimedDispatch claimedDispatch() {
        return new DispatchStateService.ClaimedDispatch(notification,
                new DispatchStateService.ClaimedNotification(
                        notification.getId(), "token", NotificationStatus.PENDING, null));
    }

    @BeforeEach
    void setUp() {
        lenient().when(notificationRepositoryPort.currentTime())
                .thenReturn(LocalDateTime.of(2026, 9, 21, 10, 0));
        // 호출 직전 방어(P0-c)의 기본 전제: 선점 직후라 lease가 넉넉하다. 부족/만료 분기는 아래 전용 테스트에서 본다.
        lenient().when(dispatchStateService.tryBeginExternalCall(any(), anyInt())).thenReturn(true);
        // @Value 필드는 Spring 없이 주입되지 않는다. 비워 두면 scope가 null인 채로 차단기를 부른다.
        ReflectionTestUtils.setField(notificationDispatchService, "providerScope", "EMAIL:test");
        ReflectionTestUtils.setField(notificationDispatchService, "providerHoldSeconds", 30);
        // 차단기는 기본적으로 허가한다. 차단 동작 자체는 ProviderCircuitIntegrationTest가 본다.
        lenient().when(providerCircuitPort.tryAcquire(anyString()))
                .thenReturn(Optional.of(new com.notification.domain.ProviderCallPermit("EMAIL:test", 1L, null)));
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
        given(dispatchStateService.claim(1L)).willReturn(Optional.of(claimedDispatch()));

        notificationDispatchService.dispatch(1L);

        InOrder inOrder = inOrder(dispatchStateService, channelSenderPort);
        inOrder.verify(dispatchStateService).claim(1L);
        inOrder.verify(channelSenderPort).send(notification);
        inOrder.verify(dispatchStateService).finish(eq(notification), eq(1), isNull());

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        // 조율자는 트랜잭션이 없다. 소유권 확인과 시도 의도 기록은 상태 서비스의 짧은 TX에 맡기고,
        // 저장소를 직접 만지지 않는다. 정상 경로에서는 그 한 번으로 끝난다.
        verify(dispatchStateService).tryBeginExternalCall(any(), anyInt());
        verifyNoInteractions(notificationRepositoryPort, notificationLogRepositoryPort);
    }

    // [시나리오] 외부 채널 일시 장애 → FAILED로 끝내면 재시도 기회를 잃음
    // → RETRYING + retryCount 1 + 실패 코드가 finish에 전달되는지 검증
    @Test
    @DisplayName("Retryable 예외: RETRYING(retryCount=1) 전이 후 실패 코드와 함께 finish")
    void dispatch_retryableException_finishesWithRetrying() {
        given(dispatchStateService.claim(1L)).willReturn(Optional.of(claimedDispatch()));
        willThrow(new RetryableChannelException(ChannelFailureCode.CHANNEL_UNAVAILABLE))
                .given(channelSenderPort).send(any());

        notificationDispatchService.dispatch(1L);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(dispatchStateService).finish(captor.capture(), eq(1), eq("CHANNEL_UNAVAILABLE"));
        assertThat(captor.getValue().getStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(captor.getValue().getRetryCount()).isEqualTo(1);
        assertThat(captor.getValue().getNextRetryAt()).isNotNull();
    }

    @Test
    @DisplayName("429: 어댑터가 전달한 Retry-After 120초와 지터 42초를 결과 저장까지 전달")
    void dispatch_rateLimited_preservesProviderDelay() {
        given(dispatchStateService.claim(1L)).willReturn(Optional.of(claimedDispatch()));
        willThrow(new RetryableChannelException(ChannelFailureCode.CHANNEL_RATE_LIMITED, Duration.ofSeconds(120)))
                .given(channelSenderPort).send(any());

        notificationDispatchService.dispatch(1L);

        assertThat(notification.getNextRetryAt()).isEqualTo(LocalDateTime.of(2026, 9, 21, 10, 2, 42));
        assertThat(notification.getRetryCount()).isEqualTo(1);
        verify(dispatchStateService).finish(notification, 1, "CHANNEL_RATE_LIMITED");
    }

    // [시나리오] 인증 키 만료처럼 재시도해도 무의미한 오류 → 계속 RETRYING하면 MAX까지 낭비
    // → retryCount 증가 없이 즉시 FAILED로 finish되는지 검증
    @Test
    @DisplayName("NonRetryable 예외: 즉시 FAILED, retryCount 변화 없음")
    void dispatch_nonRetryableException_finishesWithFailed() {
        given(dispatchStateService.claim(1L)).willReturn(Optional.of(claimedDispatch()));
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
    // → 보수적으로 재시도하되 CHANNEL_UNKNOWN으로 분리한다. UNAVAILABLE로 뭉개면
    //   코드 버그가 외부 장애로 위장돼 failure_reason만 보고는 영원히 원인을 못 찾는다 (F2-3)
    @Test
    @DisplayName("분류 못 한 예외: CHANNEL_UNKNOWN으로 RETRYING 처리, finish 반드시 1회")
    void dispatch_unknownException_treatedAsRetryable() {
        given(dispatchStateService.claim(1L)).willReturn(Optional.of(claimedDispatch()));
        willThrow(new RuntimeException("unexpected")).given(channelSenderPort).send(any());

        notificationDispatchService.dispatch(1L);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(dispatchStateService, times(1)).finish(captor.capture(), eq(1), eq("CHANNEL_UNKNOWN"));
        assertThat(captor.getValue().getStatus()).isEqualTo(NotificationStatus.RETRYING);
        // 발송 여부 불명 → 재시도가 중복을 만들 수 있다는 사실이 코드에 남아 있어야 한다
        assertThat(ChannelFailureCode.CHANNEL_UNKNOWN.isDeliveryUnknown()).isTrue();
    }

    // [시나리오] 결과 반영(TX-B) 중 DB 장애 → 여기서 예외를 삼키면 "발송했는데 기록 없음"이 조용히 묻힘
    // → 예외가 호출자(스케줄러·이벤트 핸들러)로 전파되는지 검증. 행은 PROCESSING으로 남아 Stuck 복구가 회수한다
    @Test
    @DisplayName("finish의 DB 예외는 삼키지 않고 호출자로 전파")
    void dispatch_finishThrows_propagates() {
        given(dispatchStateService.claim(1L)).willReturn(Optional.of(claimedDispatch()));
        willThrow(new IllegalStateException("db down"))
                .given(dispatchStateService).finish(any(), anyInt(), any());

        assertThatThrownBy(() -> notificationDispatchService.dispatch(1L))
                .isInstanceOf(IllegalStateException.class);
    }

    // ── P0-b: 회수 실행당 예산 ───────────────────────────────────────────
    // DB 없이 "언제 다음 batch를 시작하지 않는가"만 본다. 실제 250/1,000건 소진은 통합 테스트가 맡는다.

    private void 회수예산설정(int batchSize, int maxBatches, long timeBudgetMs) {
        ReflectionTestUtils.setField(notificationDispatchService, "stuckRecoveryBatchSize", batchSize);
        ReflectionTestUtils.setField(notificationDispatchService, "stuckRecoveryMaxBatches", maxBatches);
        ReflectionTestUtils.setField(notificationDispatchService, "stuckRecoveryTimeBudgetMs", timeBudgetMs);
    }

    @Test
    @DisplayName("시간 예산을 넘기면 새 batch를 시작하지 않는다 (batch 상한에 도달하기 전이라도)")
    void 시간예산을_넘기면_다음_batch를_시작하지_않는다() {
        회수예산설정(10, 5, 50);
        given(dispatchStateService.recoverStuckBatch(10)).willAnswer(invocation -> {
            Thread.sleep(40);   // 느린 DB를 흉내 낸다. 진행 중인 batch를 중단시키지는 않는다
            return new DispatchStateService.StuckRecoveryBatch(10, 10, 0);
        });

        var result = notificationDispatchService.recoverStuck();

        assertThat(result.stopReason()).isEqualTo(NotificationDispatchService.StopReason.TIME_BUDGET);
        assertThat(result.batches()).as("5 batch 상한에 닿기 전에 시간 예산이 먼저 멈춘다").isLessThan(5);
        assertThat(result.recovered()).isEqualTo(result.batches() * 10);
    }

    @Test
    @DisplayName("읽었는데 한 건도 회수하지 못하면 같은 행을 다시 읽지 않고 중단한다")
    void 진전이_없으면_중단한다() {
        회수예산설정(10, 5, 60_000);
        // 다른 노드가 먼저 가져갔거나 정체된 상황. 반복하면 같은 100건만 계속 읽는 낭비가 된다
        given(dispatchStateService.recoverStuckBatch(10))
                .willReturn(new DispatchStateService.StuckRecoveryBatch(10, 0, 10));

        var result = notificationDispatchService.recoverStuck();

        assertThat(result.stopReason()).isEqualTo(NotificationDispatchService.StopReason.NO_PROGRESS);
        assertThat(result.batches()).isEqualTo(1);
        assertThat(result.fencedOut()).isEqualTo(10);
        verify(dispatchStateService, times(1)).recoverStuckBatch(anyInt());
    }

    @Test
    @DisplayName("batch 도중 DB 오류가 나면 이번 실행만 중단하고 앞선 회수 결과는 유지한다")
    void DB_오류는_이번_실행만_중단한다() {
        회수예산설정(10, 5, 60_000);
        given(dispatchStateService.recoverStuckBatch(10))
                .willReturn(new DispatchStateService.StuckRecoveryBatch(10, 10, 0))
                .willThrow(new org.springframework.dao.QueryTimeoutException("lock wait timeout"));

        var result = notificationDispatchService.recoverStuck();

        assertThat(result.stopReason()).isEqualTo(NotificationDispatchService.StopReason.DB_ERROR);
        assertThat(result.batches()).isEqualTo(1);
        assertThat(result.recovered()).as("이미 커밋된 batch의 회수는 남는다").isEqualTo(10);
    }

    @Test
    @DisplayName("token 없는 만료 행은 회수하지 않고 경보용으로만 센다")
    void token_없는_이상행은_세기만_한다() {
        회수예산설정(10, 5, 60_000);
        given(dispatchStateService.recoverStuckBatch(10))
                .willReturn(new DispatchStateService.StuckRecoveryBatch(0, 0, 0));
        given(notificationRepositoryPort.countExpiredProcessingWithoutToken()).willReturn(7L);

        var result = notificationDispatchService.recoverStuck();

        assertThat(result.tokenMissing()).isEqualTo(7);
        assertThat(result.recovered()).isZero();
        assertThat(result.stopReason()).isEqualTo(NotificationDispatchService.StopReason.EMPTY);
    }

    // ── P0-c: 외부 호출 직전 방어 ─────────────────────────────────────

    @Test
    @DisplayName("잔여 lease가 예산보다 적으면 send 없이 반환한다 (즉시 경로)")
    void 잔여_lease가_부족하면_즉시경로도_호출하지_않는다() {
        ReflectionTestUtils.setField(notificationDispatchService, "minRemainingLeaseSeconds", 220);
        given(dispatchStateService.claim(1L)).willReturn(Optional.of(claimedDispatch()));
        given(dispatchStateService.tryBeginExternalCall(any(), anyInt())).willReturn(false);
        given(notificationRepositoryPort.remainingLeaseSeconds(any(), any())).willReturn(Optional.of(60L));
        given(notificationRepositoryPort.withinExpiry(any(), any())).willReturn(Optional.of(true));

        notificationDispatchService.dispatch(1L);

        verify(channelSenderPort, never()).send(any());
        verify(dispatchStateService).releaseClaim(any(), eq(DispatchStateService.LEASE_BUDGET_INSUFFICIENT));
        verify(dispatchStateService, never()).finish(any(), anyInt(), any());
    }

    @Test
    @DisplayName("이미 만료된 lease는 send도 반환도 하지 않는다 — 회수 경로와 경합하지 않기 위해")
    void 만료된_lease는_반환하지도_않는다() {
        given(dispatchStateService.claim(1L)).willReturn(Optional.of(claimedDispatch()));
        given(dispatchStateService.tryBeginExternalCall(any(), anyInt())).willReturn(false);
        given(notificationRepositoryPort.remainingLeaseSeconds(any(), any())).willReturn(Optional.of(-5L));

        notificationDispatchService.dispatch(1L);

        verify(channelSenderPort, never()).send(any());
        verify(dispatchStateService, never()).releaseClaim(any(), any());
        verify(dispatchStateService, never()).finish(any(), anyInt(), any());
    }

    @Test
    @DisplayName("소유권 확인 쿼리가 실패하면 새 외부 호출을 시작하지 않는다")
    void 확인_실패시_외부호출을_시작하지_않는다() {
        given(dispatchStateService.claim(1L)).willReturn(Optional.of(claimedDispatch()));
        given(dispatchStateService.tryBeginExternalCall(any(), anyInt()))
                .willThrow(new org.springframework.dao.QueryTimeoutException("lock wait timeout"));

        notificationDispatchService.dispatch(1L);

        verify(channelSenderPort, never()).send(any());
        // 상태를 바꾸지 않는다. PROCESSING으로 남아 lease 회수가 맡는다
        verify(dispatchStateService, never()).releaseClaim(any(), any());
        verify(dispatchStateService, never()).finish(any(), anyInt(), any());
    }

    @Test
    @DisplayName("소유권을 잃었으면(행 없음) 호출하지 않는다")
    void 소유권을_잃으면_호출하지_않는다() {
        given(dispatchStateService.claim(1L)).willReturn(Optional.of(claimedDispatch()));
        given(dispatchStateService.tryBeginExternalCall(any(), anyInt())).willReturn(false);
        given(notificationRepositoryPort.remainingLeaseSeconds(any(), any())).willReturn(Optional.empty());

        notificationDispatchService.dispatch(1L);

        verify(channelSenderPort, never()).send(any());
        verify(dispatchStateService, never()).releaseClaim(any(), any());
    }
}
