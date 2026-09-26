package com.notification.infrastructure.adapter.out.channel;

import com.notification.application.port.out.ChannelSenderPort;
import com.notification.domain.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * S5 부하 실험 전용의 느린 외부 provider Mock.
 *
 * <p>기본 {@link LogChannelSenderAdapter}와 달리, 지정한 시간만큼 blocking I/O를 흉내 낸다.
 * 실제 SMTP/FCM을 호출하지 않으므로 과제의 "실제 이메일 발송 불필요" 제약을 지키면서도
 * 입력량이 provider 처리량을 초과할 때 DB backlog와 executor backpressure가 어떻게 동작하는지 측정할 수 있다.</p>
 *
 * <p>{@code loadtest} 프로필에서만 {@code @Primary}로 활성화된다. 일반 실행 경로에는 전혀 영향을 주지 않는다.</p>
 */
@Slf4j
@Primary
@Component
@Profile("loadtest")
public class LoadTestDelayChannelSenderAdapter implements ChannelSenderPort {

    @Value("${notification.loadtest.sender.delay-ms:0}")
    private long delayMs;

    @Override
    public void send(Notification notification) {
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // 종료/중단은 성공으로 위장하지 않는다. DispatchService가 재시도 가능한 실패로 분류한다.
                throw new java.util.concurrent.CancellationException("load-test provider call interrupted");
            }
        }
        log.debug("[S5 slow-provider mock] notificationId={}, delayMs={}", notification.getId(), delayMs);
    }
}
