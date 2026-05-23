package com.notification.application.port.out;

import com.notification.application.exception.NonRetryableChannelException;
import com.notification.application.exception.RetryableChannelException;
import com.notification.domain.Notification;

/**
 * 채널별 알림 발송 출력 포트.
 *
 * 구현체는 infrastructure/adapter/out/channel에 위치한다.
 * 실패 시 RetryableChannelException 또는 NonRetryableChannelException을 던진다.
 */
public interface ChannelSenderPort {

    /**
     * 알림을 채널을 통해 발송한다.
     *
     * @throws RetryableChannelException 일시적 장애 (네트워크 오류, 5xx, 429)
     * @throws NonRetryableChannelException 영구 오류 (잘못된 수신자, 인증 실패)
     */
    void send(Notification notification);
}
