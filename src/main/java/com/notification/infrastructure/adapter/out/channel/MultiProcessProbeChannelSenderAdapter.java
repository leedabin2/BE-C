package com.notification.infrastructure.adapter.out.channel;

import com.notification.application.port.out.ChannelSenderPort;
import com.notification.domain.Notification;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * S4의 별도 JVM 검증 전용 채널 Mock.
 *
 * {@code multiprocess} 프로필에서만 실제 채널 대신 공용 DB probe에 호출 사실을 남긴다.
 * 여러 프로세스의 메모리는 공유되지 않으므로, 이 테이블로 어느 node가 어떤 알림을 외부 호출했는지 합산한다.
 * production/test 프로필에서는 생성되지 않는다.
 */
@Slf4j
@Primary
@Component
@Profile("multiprocess")
@RequiredArgsConstructor
public class MultiProcessProbeChannelSenderAdapter implements ChannelSenderPort {

    private final JdbcTemplate jdbcTemplate;

    @Value("${notification.node-id:unknown}")
    private String nodeId;

    /** kill 시나리오에서 claim 뒤·외부 호출 전의 창을 만들기 위한 S4 전용 지연. */
    @Value("${notification.multiprocess.probe.before-send-delay-ms:0}")
    private long beforeSendDelayMs;

    @PostConstruct
    void initializeProbeTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS multiprocess_delivery_probe (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    notification_id BIGINT NOT NULL,
                    node_id VARCHAR(100) NOT NULL,
                    sent_at DATETIME(6) NOT NULL,
                    PRIMARY KEY (id),
                    INDEX idx_probe_notification (notification_id)
                )
                """);
    }

    @Override
    public void send(Notification notification) {
        if (beforeSendDelayMs > 0) {
            try {
                Thread.sleep(beforeSendDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        jdbcTemplate.update("INSERT INTO multiprocess_delivery_probe(notification_id, node_id, sent_at) VALUES (?, ?, UTC_TIMESTAMP(6))",
                notification.getId(), nodeId);
        log.info("[S4 probe] external-send notificationId={}, nodeId={}", notification.getId(), nodeId);
    }
}
