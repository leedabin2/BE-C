package com.notification.adapter.in.messaging;
// PRD: F4-2 → docs/prd/F4.md

import com.notification.application.port.in.RegisterNotificationUseCase;
import com.notification.application.port.in.result.RegisterNotificationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 메시징 인그레스. 리스너가 붙을 자리다.
 *
 * <pre>{@code
 * @KafkaListener(topics = "notification-events")
 * public void consume(NotificationMessage msg, Acknowledgment ack) {
 *     handler.handle(msg);   // ① PENDING 저장 커밋
 *     ack.acknowledge();     // ② 그 다음 오프셋 커밋
 * }
 * }</pre>
 *
 * ①→② 순서를 뒤집으면 저장 전 장애 시 재전달이 안 돼 유실된다.
 * 이 순서면 중복만 생기고, 중복은 멱등성 키가 흡수한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationMessageHandler {

    private final RegisterNotificationUseCase registerNotificationUseCase;

    /**
     * 저장(PENDING)까지만 하고 반환한다. 여기서 SMTP를 기다리면
     * max.poll.interval.ms를 넘겨 컨슈머 리밸런싱이 터진다. 발송은 워커·스케줄러 몫.
     *
     * 예외는 삼키지 않는다. 리스너로 전파해 오프셋을 커밋하지 않게 하고 재전달을 받는다.
     */
    public RegisterNotificationResult handle(NotificationMessage message) {
        log.debug("메시지 수신. eventId={}, channel={}", message.eventId(), message.channel());
        return registerNotificationUseCase.register(message.toCommand());
    }
}
