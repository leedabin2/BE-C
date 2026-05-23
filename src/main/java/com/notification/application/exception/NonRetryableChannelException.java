package com.notification.application.exception;

import lombok.Getter;

/** 재시도 불가 채널 발송 실패. 영구 오류로 즉시 FAILED 처리한다. */
@Getter
public class NonRetryableChannelException extends RuntimeException {

    private final ChannelFailureCode failureCode;

    public NonRetryableChannelException(ChannelFailureCode failureCode) {
        super(failureCode.name());
        this.failureCode = failureCode;
    }
}
