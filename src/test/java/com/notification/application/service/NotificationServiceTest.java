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
import org.mockito.junit.jupiter.MockitoExtension;

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

    @InjectMocks NotificationService notificationService;

    private RegisterNotificationCommand command;
    private Notification savedNotification;

    @BeforeEach
    void setUp() {
        command = new RegisterNotificationCommand(
                42L,
                NotificationType.PAYMENT_CONFIRMED,
                NotificationChannel.EMAIL,
                "user@test.com",
                "evt-001",
                100L,
                "PAYMENT",
                "{\"amount\":10000}",
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
        given(notificationRepositoryPort.save(any())).willReturn(savedNotification);

        notificationService.register(command);

        verify(notificationRepositoryPort, times(1)).save(any(Notification.class));
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

        verify(notificationRepositoryPort, never()).save(any());
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
        given(notificationRepositoryPort.save(any())).willReturn(savedNotification);

        notificationService.register(command);
        notificationService.register(command);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationRepositoryPort, times(2)).findByIdempotencyKey(keyCaptor.capture());

        assertThat(keyCaptor.getAllValues().get(0))
                .isEqualTo(keyCaptor.getAllValues().get(1));
    }
}
