package com.notification.application.exception;
// PRD: F2-2, F2-3 → docs/prd/F2.md, docs/DECISIONS.md D-017

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 채널 발송 실패 내부 코드. 외부 오류 메시지를 그대로 저장하지 않는다(보안).
 *
 * <p>{@code deliveryUnknown}이 이 enum의 존재 이유다. 실패는 세 가지이지 두 가지가 아니다.
 * <b>확실히 안 감 / 확실히 영구실패 / 갔는지 모름.</b> 마지막이 재시도 시 중복을 만든다.
 */
@Getter
@RequiredArgsConstructor
public enum ChannelFailureCode {

    // ── 재시도 가능 · 확실히 발송되지 않음 ─────────────────────────────
    /** 연결 자체 실패(ConnectException·UnknownHostException) 또는 외부 5xx/SMTP 4yz. */
    CHANNEL_UNAVAILABLE(true, false),
    /** 레이트 리밋(429, SMTP 4.7.x). 서버가 알려준 대기 시간이 있으면 그것을 따른다. */
    CHANNEL_RATE_LIMITED(true, false),

    // ── 재시도 가능 · 발송 여부 불명 ★ ────────────────────────────────
    /**
     * 읽기/쓰기 타임아웃. 요청은 보냈는데 응답을 못 받았다.
     * <b>재시도하면 중복 발송이 될 수 있다.</b> 그래도 재시도하는 이유는 유실이 중복보다 나쁘기 때문이다.
     */
    CHANNEL_TIMEOUT(true, true),

    // ── 재시도 불가 · 영구 오류 ───────────────────────────────────────
    /** 잘못된 수신자(주소 형식 오류, SMTP 550/553). */
    CHANNEL_INVALID_TARGET(false, false),
    /** 인증 실패(401/403, SMTP 535). 재시도해도 같은 결과다. */
    CHANNEL_AUTH_FAILED(false, false),
    /** 잘못된 요청(400/422, SMTP 554). */
    CHANNEL_INVALID_REQUEST(false, false),

    // ── 분류 실패 ─────────────────────────────────────────────────────
    /**
     * 우리가 분류하지 못한 예외. NPE 같은 코드 버그가 여기 들어온다.
     * CHANNEL_UNAVAILABLE로 뭉개면 버그가 외부 장애로 위장돼 영원히 안 고쳐진다.
     */
    CHANNEL_UNKNOWN(true, true);

    /** 재시도 대상인가. */
    private final boolean retryable;
    /** 실제로 발송됐는지 알 수 없는가. true면 재시도가 중복을 만들 수 있다. */
    private final boolean deliveryUnknown;
}
