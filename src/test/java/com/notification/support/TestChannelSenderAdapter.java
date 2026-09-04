package com.notification.support;

import com.notification.application.exception.ChannelFailureCode;
import com.notification.application.exception.NonRetryableChannelException;
import com.notification.application.exception.RetryableChannelException;
import com.notification.application.port.out.ChannelSenderPort;
import com.notification.domain.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 테스트용 채널 발송 어댑터.
 *
 * 시나리오별 동작을 제어할 수 있도록 설계됐다.
 * - 기본: 즉시 성공
 * - setFailCount(n): n회 RetryableException 발생 후 성공
 * - setThrowTimeout(true): NonRetryableException(타임아웃) 발생
 */
@Slf4j
@Primary
@Profile("test")
@Component
public class TestChannelSenderAdapter implements ChannelSenderPort {

    /** 발송 1건의 기록. 어느 스레드가 몇 번째로 무엇을 보냈는지. */
    public record SendRecord(long notificationId, String thread, int order, long atNanos) {
        /** notification-rt-* 이면 즉시 발송 경로, notification-batch-* 면 스케줄러 회수 경로. */
        public boolean viaRealtime() { return thread.startsWith("notification-rt-"); }
    }

    private final Queue<SendRecord> records = new ConcurrentLinkedQueue<>();
    private final AtomicInteger failCount = new AtomicInteger(0);
    private final AtomicInteger sendCallCount = new AtomicInteger(0);
    private volatile boolean throwTimeout = false;
    private volatile long sendDelayMs = 0;

    @Override
    public void send(Notification notification) {
        String thread = Thread.currentThread().getName();
        int order = sendCallCount.incrementAndGet();
        records.add(new SendRecord(notification.getId(), thread, order, System.nanoTime()));
        if (log.isDebugEnabled()) {
            log.debug("[TestChannel] send 진입 id={} thread={} (delay={}ms) [총 호출={}]",
                    notification.getId(), thread, sendDelayMs, order);
        }

        if (sendDelayMs > 0) {
            try {
                Thread.sleep(sendDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (throwTimeout) {
            log.warn("[TestChannel] NonRetryable 예외 발생 id={} thread={}", notification.getId(), thread);
            throw new NonRetryableChannelException(ChannelFailureCode.CHANNEL_UNAVAILABLE);
        }

        if (failCount.get() > 0) {
            failCount.decrementAndGet();
            log.warn("[TestChannel] Retryable 예외 발생 id={} thread={} (남은 failCount={})",
                    notification.getId(), thread, failCount.get());
            throw new RetryableChannelException(ChannelFailureCode.CHANNEL_UNAVAILABLE);
        }

        log.info("[TestChannel] 발송 성공 id={} thread={}", notification.getId(), thread);
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

    /** 실제 send() 호출 횟수 (CAS 성공 여부와 무관하게 채널까지 도달한 횟수) */
    public int getSendCallCount() {
        return sendCallCount.get();
    }

    /** 발송 기록. 스레드 분포·처리 순서 분석용. */
    public List<SendRecord> getRecords() {
        return List.copyOf(records);
    }

    /** 테스트 간 상태 초기화 */
    public void reset() {
        this.records.clear();
        this.failCount.set(0);
        this.sendCallCount.set(0);
        this.throwTimeout = false;
        this.sendDelayMs = 0;
    }
}
