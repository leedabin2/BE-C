package com.notification.application.exception;

import lombok.Getter;

/** 재시도 가능한 채널 발송 실패. 일시적 장애로 지수 백오프 후 재시도한다. */
@Getter
public class RetryableChannelException extends RuntimeException {

    private final ChannelFailureCode failureCode;

    public RetryableChannelException(ChannelFailureCode failureCode) {
        super(failureCode.name());
        this.failureCode = failureCode;
    }
}
