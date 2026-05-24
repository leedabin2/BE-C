package com.notification.support;

import com.notification.application.exception.ChannelFailureCode;
import com.notification.application.exception.NonRetryableChannelException;
import com.notification.application.exception.RetryableChannelException;
import com.notification.application.port.out.ChannelSenderPort;
import com.notification.domain.Notification;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 테스트용 채널 발송 어댑터.
 *
 * 시나리오별 동작을 제어할 수 있도록 설계됐다.
 * - 기본: 즉시 성공
 * - setFailCount(n): n회 RetryableException 발생 후 성공
 * - setThrowTimeout(true): NonRetryableException(타임아웃) 발생
 */
@Primary
@Profile("test")
@Component
public class TestChannelSenderAdapter implements ChannelSenderPort {

    private final AtomicInteger failCount = new AtomicInteger(0);
    private volatile boolean throwTimeout = false;
    private volatile long sendDelayMs = 0;

    @Override
    public void send(Notification notification) {
        if (sendDelayMs > 0) {
            try {
                Thread.sleep(sendDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (throwTimeout) {
            throw new NonRetryableChannelException(ChannelFailureCode.CHANNEL_UNAVAILABLE);
        }

        if (failCount.get() > 0) {
            failCount.decrementAndGet();
            throw new RetryableChannelException(ChannelFailureCode.CHANNEL_UNAVAILABLE);
        }
    }

    /** n회 RetryableException 발생 후 성공하도록 설정 */
    public void setFailCount(int count) {
        this.failCount.set(count);
    }

    /** true 설정 시 NonRetryableException(타임아웃 모사) 발생 */
    public void setThrowTimeout(boolean throwTimeout) {
        this.throwTimeout = throwTimeout;
    }

    /** 발송 지연 시간 설정 (CAS 경합 재현용) */
    public void setSendDelayMs(long delayMs) {
        this.sendDelayMs = delayMs;
    }

    /** 테스트 간 상태 초기화 */
    public void reset() {
        this.failCount.set(0);
        this.throwTimeout = false;
        this.sendDelayMs = 0;
    }
}
