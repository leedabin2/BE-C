package com.notification.infrastructure.adapter.out.channel;
// PRD: F2-2, F2-3 → docs/prd/F2.md, docs/DECISIONS.md D-017

import com.notification.application.exception.ChannelFailureCode;
import com.notification.application.exception.NonRetryableChannelException;
import com.notification.application.exception.RetryableChannelException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * 외부 발송 실패를 내부 코드로 분류한다.
 *
 * <p>⚠️ <b>SMTP는 HTTP와 반대다.</b> RFC 5321에서 4yz가 일시(재시도), 5yz가 영구(재시도 금지)다.
 * "5xx면 재시도"라는 HTTP 습관을 그대로 가져오면 영구 실패를 3번 반복하게 된다.
 *
 * <p>JDK 예외만 참조한다. 특정 메일 라이브러리에 묶이지 않아 어댑터가 바뀌어도 이 규칙은 남는다.
 */
public final class SmtpFailureClassifier {

    private SmtpFailureClassifier() {}

    /** 네트워크 계층 예외 → 내부 코드. 분류 못 하면 CHANNEL_UNKNOWN(버그 은폐 방지). */
    public static RuntimeException classify(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            // 순서 주의: SocketTimeoutException은 ConnectException보다 먼저 본다
            if (c instanceof SocketTimeoutException) return retryable(ChannelFailureCode.CHANNEL_TIMEOUT);
            if (c instanceof ConnectException
             || c instanceof UnknownHostException
             || c instanceof NoRouteToHostException) return retryable(ChannelFailureCode.CHANNEL_UNAVAILABLE);
        }
        return retryable(ChannelFailureCode.CHANNEL_UNKNOWN);
    }

    /**
     * SMTP 응답 코드 → 내부 코드. (RFC 5321 §4.2.1)
     *
     * <pre>
     * 4yz 일시  421 서비스 불가 · 450 사서함 사용중 · 451 로컬 오류 · 452 저장공간 부족
     *           454 일시 인증 실패(RFC 4954 §6)                                     → 재시도
     * 5yz 영구  550/553 사서함 없음 · 530/535 인증 실패 · 554 트랜잭션 실패          → 재시도 금지
     * </pre>
     *
     * <p><b>rate limit은 이 3자리 코드만으로 판정하지 않는다.</b> {@code 454 4.7.0}은 quota 초과가 아니라
     * 서버가 지금 인증 처리를 못 한다는 일시 오류이고, {@code 471}은 표준 코드가 아니다.
     * quota로 오분류하면 인증 장애가 "발송량 초과" 지표로 둔갑해 대응이 엇나간다.
     * {@link ChannelFailureCode#CHANNEL_RATE_LIMITED}는 제공자가 한도를 명시한 경우
     * (HTTP 429 + Retry-After 등)에만 쓴다. 상세 인증/한도 코드 구분은 어댑터가
     * enhanced status code(예: 4.7.0)까지 읽게 될 때 확장한다.
     *
     * <p>영구 거절도 원인을 뭉개지 않는다. 550은 "주소 없음"으로 단정할 수 없지만
     * 수신자 거절 계열이므로 INVALID_TARGET, 인증 계열은 AUTH_FAILED로 남긴다 — 운영 대응이
     * 주소 정정과 키 교체로 서로 다르기 때문이다. (RFC 4954 §6)
     */
    public static RuntimeException classifyReplyCode(int replyCode) {
        return switch (replyCode) {
            case 421, 450, 451, 452 -> retryable(ChannelFailureCode.CHANNEL_UNAVAILABLE);
            case 454, 471           -> retryable(ChannelFailureCode.CHANNEL_UNAVAILABLE);
            case 535, 530           -> nonRetryable(ChannelFailureCode.CHANNEL_AUTH_FAILED);
            case 550, 551, 553      -> nonRetryable(ChannelFailureCode.CHANNEL_INVALID_TARGET);
            case 552, 554, 555      -> nonRetryable(ChannelFailureCode.CHANNEL_INVALID_REQUEST);
            default -> replyCode >= 400 && replyCode < 500
                    ? retryable(ChannelFailureCode.CHANNEL_UNAVAILABLE)      // 미지의 4yz는 일시로 본다
                    : nonRetryable(ChannelFailureCode.CHANNEL_INVALID_REQUEST);
        };
    }

    private static RuntimeException retryable(ChannelFailureCode code) {
        return new RetryableChannelException(code);
    }

    private static RuntimeException nonRetryable(ChannelFailureCode code) {
        return new NonRetryableChannelException(code);
    }
}
