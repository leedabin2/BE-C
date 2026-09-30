package com.notification.application.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;

/**
 * 재시도 시각 계산만 담당하는 정책 객체.
 *
 * <p>상태 전이는 {@code Notification} 도메인이 맡고, 이 객체는 "언제 다시 시도할지"만 계산한다.
 * 기준 시각(DB UTC)을 인수로 받고 고정 jitter를 주입해 경계값을 결정적으로 테스트한다.
 */
@Component
public class RetrySchedulePolicy {

    static final int MAX_JITTER_SECONDS = 60;

    private final IntSupplier jitterSeconds;

    /** 운영 기본값: 요청마다 독립적인 0~60초 positive jitter. */
    public RetrySchedulePolicy() {
        this(() -> ThreadLocalRandom.current().nextInt(MAX_JITTER_SECONDS + 1));
    }

    /** 테스트에서 고정 지터를 주입하기 위한 생성자. */
    RetrySchedulePolicy(IntSupplier jitterSeconds) {
        this.jitterSeconds = jitterSeconds;
    }

    /**
     * 주어진 기본 지연에 제공자 지시와 지터를 얹어 다음 시각을 만든다. 지연 목록을 아는 것은
     * {@link RetryDecisionPolicy}이고, 여기서는 "언제로 미룰지"의 계산 규칙만 담당한다.
     *
     * @param baseDelay  정책이 고른 기본 대기
     * @param retryAfter 제공자가 지시한 최소 대기. 기본 대기보다 길면 이쪽이 이긴다
     */
    public LocalDateTime scheduleAfter(Duration baseDelay, Optional<Duration> retryAfter, LocalDateTime now) {
        Duration providerDelay = retryAfter.orElse(Duration.ZERO);
        if (providerDelay.isNegative()) {
            throw new IllegalArgumentException("Retry-After는 음수일 수 없습니다.");
        }

        int jitter = jitterSeconds.getAsInt();
        if (jitter < 0 || jitter > MAX_JITTER_SECONDS) {
            throw new IllegalArgumentException("jitter는 0~%d초여야 합니다.".formatted(MAX_JITTER_SECONDS));
        }

        // Retry-After는 제공자가 요구한 최소 휴식 시간이다. 더 짧게 잘라 재시도하지 않는다.
        Duration delay = providerDelay.compareTo(baseDelay) > 0 ? providerDelay : baseDelay;
        return now.plus(delay).plusSeconds(jitter);
    }

}
