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
     * 4yz 일시  421 서비스 불가 · 450 사서함 사용중 · 451 로컬 오류 · 452 저장공간 부족  → 재시도
     * 5yz 영구  550/553 사서함 없음 · 535 인증 실패 · 554 트랜잭션 실패               → 재시도 금지
     * </pre>
     */
    public static RuntimeException classifyReplyCode(int replyCode) {
        return switch (replyCode) {
            case 421, 450, 451, 452 -> retryable(ChannelFailureCode.CHANNEL_UNAVAILABLE);
            case 454, 471           -> retryable(ChannelFailureCode.CHANNEL_RATE_LIMITED);
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
