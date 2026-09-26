package com.notification.application.exception;
// PRD: F2-2 → docs/prd/F2.md

import lombok.Getter;

import java.time.Duration;
import java.util.Optional;

/** 재시도 가능한 채널 발송 실패. 일시적 장애로 지수 백오프 후 재시도한다. */
@Getter
public class RetryableChannelException extends RuntimeException {

    private final ChannelFailureCode failureCode;
    /** HTTP 429 등에서 제공자가 준 최소 재시도 대기. 없으면 자체 정책을 쓴다. */
    private final Optional<Duration> retryAfter;

    public RetryableChannelException(ChannelFailureCode failureCode) {
        this(failureCode, Optional.empty());
    }

    public RetryableChannelException(ChannelFailureCode failureCode, Duration retryAfter) {
        this(failureCode, Optional.of(retryAfter));
    }

    private RetryableChannelException(ChannelFailureCode failureCode, Optional<Duration> retryAfter) {
        super(failureCode.name());
        if (retryAfter.filter(Duration::isNegative).isPresent()) {
            throw new IllegalArgumentException("Retry-After는 음수일 수 없습니다.");
        }
        this.failureCode = failureCode;
        this.retryAfter = retryAfter;
    }
}
