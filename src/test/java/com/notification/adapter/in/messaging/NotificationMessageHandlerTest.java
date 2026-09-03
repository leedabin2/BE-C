package com.notification.adapter.in.messaging;

import com.notification.adapter.in.web.dto.NotificationRequest;
import com.notification.application.port.in.RegisterNotificationUseCase;
import com.notification.application.port.in.command.RegisterNotificationCommand;
import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

/**
 * 메시징 인그레스 어댑터 단위 테스트.
 *
 * 검증 대상은 F4-2("브로커 전환 가능한 구조")가 코드로 성립하는가다. 두 가지를 못 박는다.
 * ① 메시징 인그레스가 HTTP 인그레스와 <b>동일한 Command</b>로 수렴한다 → application 이하가 인그레스를 모른다
 * ② 등록 예외를 삼키지 않고 전파한다 → 리스너가 오프셋을 커밋하지 않고 브로커가 재전달한다
 */
@ExtendWith(MockitoExtension.class)
class NotificationMessageHandlerTest {

    @Mock RegisterNotificationUseCase registerNotificationUseCase;
    @InjectMocks NotificationMessageHandler handler;

    private static final LocalDateTime SCHEDULED_AT = LocalDateTime.of(2026, 6, 1, 9, 0);

    private NotificationMessage message() {
        return new NotificationMessage(1L, NotificationType.PAYMENT_CONFIRMED, NotificationChannel.EMAIL,
                "user@example.com", "pay-attempt-4412", 101L, "PAYMENT", "{\"amount\":1000}", SCHEDULED_AT);
    }

    private NotificationRequest sameRequestOverHttp() {
        return new NotificationRequest(1L, NotificationType.PAYMENT_CONFIRMED, NotificationChannel.EMAIL,
                "user@example.com", "pay-attempt-4412", 101L, "PAYMENT", "{\"amount\":1000}", SCHEDULED_AT);
    }

    @Test
    @DisplayName("메시지를 Command로 변환해 UseCase에 그대로 넘긴다")
    void 메시지를_Command로_변환해_위임한다() {
        handler.handle(message());

        ArgumentCaptor<RegisterNotificationCommand> captor =
                ArgumentCaptor.forClass(RegisterNotificationCommand.class);
        verify(registerNotificationUseCase).register(captor.capture());

        RegisterNotificationCommand command = captor.getValue();
        assertThat(command.eventId()).isEqualTo("pay-attempt-4412");
        assertThat(command.channel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(command.channelTarget()).isEqualTo("user@example.com");
        assertThat(command.scheduledAt()).isEqualTo(SCHEDULED_AT);
    }

    @Test
    @DisplayName("같은 이벤트라면 HTTP로 오든 메시지로 오든 완전히 같은 Command가 된다 (F4-2·M4)")
    void 인그레스가_달라도_Command는_동일하다() {
        // record equals가 전 필드를 비교한다. 하나라도 어긋나면 멱등성 키가 갈라진다.
        assertThat(message().toCommand()).isEqualTo(sameRequestOverHttp().toCommand());
    }

    @Test
    @DisplayName("등록 실패는 삼키지 않고 전파한다 — 오프셋 미커밋 → 브로커 재전달")
    void 등록_예외를_전파한다() {
        willThrow(new IllegalStateException("DB down"))
                .given(registerNotificationUseCase).register(org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> handler.handle(message()))
                .isInstanceOf(IllegalStateException.class);
    }
}
