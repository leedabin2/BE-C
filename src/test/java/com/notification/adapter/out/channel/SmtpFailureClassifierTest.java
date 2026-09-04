package com.notification.adapter.out.channel;

import com.notification.application.exception.ChannelFailureCode;
import com.notification.application.exception.NonRetryableChannelException;
import com.notification.application.exception.RetryableChannelException;
import com.notification.infrastructure.adapter.out.channel.SmtpFailureClassifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실패 분류 규칙. F2-2(재시도 판정)·F2-3(실패 사유 기록)의 근거 표를 코드로 고정한다.
 *
 * ⚠️ SMTP는 HTTP와 반대다 — 4yz가 일시, 5yz가 영구. (RFC 5321 §4.2.1)
 */
@DisplayName("채널 실패 분류")
class SmtpFailureClassifierTest {

    @Test
    @DisplayName("읽기 타임아웃은 재시도 대상이지만 발송 여부는 알 수 없다")
    void 타임아웃은_발송여부_불명이다() {
        RuntimeException e = SmtpFailureClassifier.classify(new SocketTimeoutException("Read timed out"));

        assertThat(e).isInstanceOf(RetryableChannelException.class);
        ChannelFailureCode code = ((RetryableChannelException) e).getFailureCode();
        assertThat(code).isEqualTo(ChannelFailureCode.CHANNEL_TIMEOUT);
        assertThat(code.isRetryable()).isTrue();
        // ★ 재시도하면 중복 발송이 될 수 있다는 사실을 코드가 들고 있어야 추적이 된다
        assertThat(code.isDeliveryUnknown()).isTrue();
    }

    @Test
    @DisplayName("연결 실패는 확실히 발송되지 않았다 — 재시도가 안전하다")
    void 연결실패는_발송되지_않았다() {
        for (Throwable t : new Throwable[]{new ConnectException(), new UnknownHostException("smtp.x")}) {
            RuntimeException e = SmtpFailureClassifier.classify(t);
            ChannelFailureCode code = ((RetryableChannelException) e).getFailureCode();
            assertThat(code).isEqualTo(ChannelFailureCode.CHANNEL_UNAVAILABLE);
            assertThat(code.isDeliveryUnknown()).isFalse();
        }
    }

    @Test
    @DisplayName("원인 예외가 중첩돼 있어도 찾아낸다")
    void 중첩된_원인을_푼다() {
        RuntimeException e = SmtpFailureClassifier.classify(
                new RuntimeException("wrapper", new IllegalStateException("mid", new SocketTimeoutException())));
        assertThat(((RetryableChannelException) e).getFailureCode()).isEqualTo(ChannelFailureCode.CHANNEL_TIMEOUT);
    }

    @Test
    @DisplayName("분류 못 한 예외는 UNAVAILABLE이 아니라 UNKNOWN — 코드 버그가 장애로 위장되면 안 된다")
    void 분류_실패는_UNKNOWN이다() {
        RuntimeException e = SmtpFailureClassifier.classify(new NullPointerException());
        assertThat(((RetryableChannelException) e).getFailureCode()).isEqualTo(ChannelFailureCode.CHANNEL_UNKNOWN);
    }

    @ParameterizedTest(name = "SMTP {0} → {1}")
    @CsvSource({
            "421, CHANNEL_UNAVAILABLE",     // Service not available
            "450, CHANNEL_UNAVAILABLE",     // Mailbox busy
            "451, CHANNEL_UNAVAILABLE",     // Local error
            "452, CHANNEL_UNAVAILABLE",     // Insufficient storage
            "454, CHANNEL_RATE_LIMITED",
            "535, CHANNEL_AUTH_FAILED",     // Authentication failed
            "550, CHANNEL_INVALID_TARGET",  // Mailbox unavailable
            "553, CHANNEL_INVALID_TARGET",  // Invalid address
            "554, CHANNEL_INVALID_REQUEST"  // Transaction failed
    })
    @DisplayName("SMTP 응답 코드 매핑 — 4yz 일시 / 5yz 영구 (HTTP와 반대)")
    void SMTP_응답코드를_분류한다(int replyCode, ChannelFailureCode expected) {
        RuntimeException e = SmtpFailureClassifier.classifyReplyCode(replyCode);

        ChannelFailureCode actual = e instanceof RetryableChannelException r
                ? r.getFailureCode()
                : ((NonRetryableChannelException) e).getFailureCode();
        assertThat(actual).isEqualTo(expected);
        assertThat(e).isInstanceOf(expected.isRetryable()
                ? RetryableChannelException.class : NonRetryableChannelException.class);
    }
}
