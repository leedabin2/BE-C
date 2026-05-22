package com.notification.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 서비스 전체에서 사용하는 에러 코드 정의.
 *
 * <p>HTTP 상태 코드, 내부 에러 코드, 메시지를 한 곳에서 관리한다.
 * 접두사 규칙: C(Common 공통), N(Notification 알림)</p>
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 공통
    /** 요청 파라미터 유효성 검사 실패. */
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "C001", "잘못된 입력값입니다."),

    /** 처리되지 않은 서버 내부 오류. */
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "C002", "서버 내부 오류입니다."),

    // 알림
    /** 동일 멱등성 키를 가진 알림이 이미 존재함. 중복 발송 방지. */
    DUPLICATE_NOTIFICATION(HttpStatus.CONFLICT, "N001", "이미 처리된 알림 요청입니다."),

    /** 요청한 ID의 알림을 찾을 수 없음. */
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "N002", "알림을 찾을 수 없습니다."),

    /** FAILED 상태가 아닌 알림에 수동 재시도를 요청한 경우. */
    NOTIFICATION_NOT_RETRYABLE(HttpStatus.BAD_REQUEST, "N003", "재시도할 수 없는 상태의 알림입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
