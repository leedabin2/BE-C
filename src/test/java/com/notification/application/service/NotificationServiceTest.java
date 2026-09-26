package com.notification.application.service;

import com.notification.application.event.NotificationCreatedEvent;
import com.notification.application.port.in.command.RegisterNotificationCommand;
import com.notification.application.port.in.result.RegisterNotificationResult;
import com.notification.application.port.out.NotificationEventPublisherPort;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock NotificationRepositoryPort notificationRepositoryPort;
    @Mock NotificationEventPublisherPort eventPublisherPort;
    @Mock NotificationLogRepositoryPort notificationLogRepositoryPort;

    // TYPE_V1 스위치가 꺼진 기본 상태를 그대로 쓴다. 신규 행도 LEGACY_V0로 등록되는지 함께 확인된다.

    @Spy NotificationPolicyAssigner policyAssigner =

            new NotificationPolicyAssigner(new RetryDecisionPolicy(new RetrySchedulePolicy(() -> 0)));


    @InjectMocks NotificationService notificationService;

    private RegisterNotificationCommand command;
    private Notification savedNotification;

    @BeforeEach
    void setUp() {
        // register()는 트랜잭션 경계를 나누려고 self(자기 프록시)로 내부 메서드를 부른다.
        // 단위 테스트엔 프록시가 없으므로 자기 자신을 넣는다. 호출 순서 검증은 그대로 유효하다.
        ReflectionTestUtils.setField(notificationService, "self", notificationService);

        // 시간창은 DB UTC 기준으로 정한다. 앱 시계를 쓰지 않는 계약이라 여기서도 DB 시각을 흉내 낸다.
        lenient().when(notificationRepositoryPort.currentTime())
                .thenReturn(java.time.LocalDateTime.of(2026, 9, 25, 10, 0));

        command = new RegisterNotificationCommand(
                42L,
                NotificationType.PAYMENT_CONFIRMED,
                NotificationChannel.EMAIL,
                "user@test.com",
                "evt-001",
                100L,
                "PAYMENT",
                "{\"amount\":10000}",
                null,
                null
        );

        savedNotification = Notification.builder()
                .receiverId(42L)
                .notificationType(NotificationType.PAYMENT_CONFIRMED)
                .channel(NotificationChannel.EMAIL)
                .eventId("evt-001")
                .idempotencyKey("dummy-key")
                .build();
    }

    // [시나리오] 이벤트 발행 전에 저장이 빠지면 발송 이벤트가 유실 → 알림이 처리되지 않음
    // → save 1회·NotificationLog CREATED 기록·publish 1회 순서가 모두 지켜지는지 검증
    @Test
    @DisplayName("신규 요청: save 1회, publish 1회, NotificationLog CREATED 기록")
    void register_newRequest_savesAndPublishes() {
        given(notificationRepositoryPort.findByIdempotencyKey(any())).willReturn(Optional.empty());
        given(notificationRepositoryPort.saveAndFlush(any())).willReturn(savedNotification);

        notificationService.register(command);

        verify(notificationRepositoryPort, times(1)).saveAndFlush(any(Notification.class));
        verify(eventPublisherPort, times(1)).publish(any(NotificationCreatedEvent.class));

        ArgumentCaptor<com.notification.domain.NotificationLog> logCaptor =
                ArgumentCaptor.forClass(com.notification.domain.NotificationLog.class);
        verify(notificationLogRepositoryPort, times(1)).save(logCaptor.capture());

        assertThat(logCaptor.getValue().getToStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(logCaptor.getValue().getReason()).isEqualTo("CREATED");
    }

    // [시나리오] 네트워크 재전송·클라이언트 재시도로 같은 요청이 여러 번 들어옴 → 중복 저장·발송 발생
    // → 이미 존재하는 key면 save·publish·log 호출 없이 기존 결과를 그대로 반환하는지 검증
    @Test
    @DisplayName("중복 요청: save/publish 호출 없음, 기존 결과 반환")
    void register_duplicateRequest_returnsExistingWithoutSave() {
        given(notificationRepositoryPort.findByIdempotencyKey(any()))
                .willReturn(Optional.of(savedNotification));

        RegisterNotificationResult result = notificationService.register(command);

        verify(notificationRepositoryPort, never()).saveAndFlush(any());
        verify(eventPublisherPort, never()).publish(any());
        verify(notificationLogRepositoryPort, never()).save(any());

        assertThat(result).isNotNull();
    }

    // [시나리오] key 생성에 랜덤 값이 섞이면 재요청마다 다른 key → 중복 체크가 무력화됨
    // → 동일 커맨드를 두 번 보냈을 때 findByIdempotencyKey에 전달된 key가 항상 일치하는지 검증
    @Test
    @DisplayName("동일 커맨드는 항상 동일한 idempotency key를 생성한다")
    void register_sameCommand_generatesSameIdempotencyKey() {
        given(notificationRepositoryPort.findByIdempotencyKey(any())).willReturn(Optional.empty());
        given(notificationRepositoryPort.saveAndFlush(any())).willReturn(savedNotification);

        notificationService.register(command);
        notificationService.register(command);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationRepositoryPort, times(2)).findByIdempotencyKey(keyCaptor.capture());

        assertThat(keyCaptor.getAllValues().get(0))
                .isEqualTo(keyCaptor.getAllValues().get(1));
    }
}
