package com.notification.infrastructure.adapter.out.channel;

import com.notification.application.exception.ChannelFailureCode;
import com.notification.application.exception.NonRetryableChannelException;
import com.notification.application.port.out.ChannelSenderPort;
import com.notification.domain.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 로그 출력 기반 채널 발송 Mock 구현체.
 *
 * 실제 이메일/푸시 발송 없이 로그로 대체한다.
 * 운영 전환 시 이 구현체를 실제 발송 어댑터(EmailSenderAdapter 등)로 교체하면 된다.
 * ChannelSenderPort만 구현하므로 상위 레이어 코드 변경 없음.
 */
@Slf4j
@Component
public class LogChannelSenderAdapter implements ChannelSenderPort {

    @Override
    public void send(Notification notification) {
        switch (notification.getChannel()) {
            case EMAIL -> sendEmail(notification);
            case IN_APP -> sendInApp(notification);
        }
    }

    private void sendEmail(Notification notification) {
        if (notification.getChannelTarget() == null || notification.getChannelTarget().isBlank()) {
            throw new NonRetryableChannelException(ChannelFailureCode.CHANNEL_INVALID_TARGET);
        }
        log.info("[EMAIL 발송] to={}, type={}, referenceId={}, referenceType={}",
                notification.getChannelTarget(),
                notification.getNotificationType(),
                notification.getReferenceId(),
                notification.getReferenceType());
    }

    private void sendInApp(Notification notification) {
        log.info("[IN_APP 발송] receiverId={}, type={}, referenceId={}, referenceType={}",
                notification.getReceiverId(),
                notification.getNotificationType(),
                notification.getReferenceId(),
                notification.getReferenceType());
    }
}
