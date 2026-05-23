package com.notification.application.exception;

/**
 * 채널 발송 실패 내부 코드.
 * 외부 서비스의 오류 메시지를 그대로 저장하지 않고 내부 코드로 대체한다 (보안).
 */
public enum ChannelFailureCode {

    // Retryable: 일시적 장애 - 잠시 후 재시도하면 성공 가능
    CHANNEL_UNAVAILABLE,    // 네트워크 오류, 외부 서비스 5xx
    CHANNEL_RATE_LIMITED,   // 외부 서비스 레이트 리밋 (429)

    // Non-retryable: 영구 오류 - 재시도해도 동일한 결과
    CHANNEL_INVALID_TARGET, // 잘못된 수신자 (이메일 형식 오류, 존재하지 않는 사용자)
    CHANNEL_AUTH_FAILED,    // 인증 실패 (API 키 만료/오류)
    CHANNEL_INVALID_REQUEST // 잘못된 요청 파라미터 (4xx)
}
