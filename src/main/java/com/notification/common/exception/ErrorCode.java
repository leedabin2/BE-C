package com.notification.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 공통
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "C001", "잘못된 입력값입니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "C002", "서버 내부 오류입니다."),

    // 알림
    DUPLICATE_NOTIFICATION(HttpStatus.CONFLICT, "N001", "이미 처리된 알림 요청입니다."),
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "N002", "알림을 찾을 수 없습니다."),
    NOTIFICATION_NOT_RETRYABLE(HttpStatus.BAD_REQUEST, "N003", "재시도할 수 없는 상태의 알림입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
