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
import java.util.List;

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
            "454, CHANNEL_UNAVAILABLE",     // 4.7.0 Temporary authentication failure (RFC 4954 §6)
            "471, CHANNEL_UNAVAILABLE",     // 비표준 코드. 제공자 문맥 없이는 일반 일시 오류로 둔다
            "530, CHANNEL_AUTH_FAILED",     // Authentication required
            "535, CHANNEL_AUTH_FAILED",     // Authentication credentials invalid
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

    /**
     * P0-a. {@code 454 4.7.0}은 서버가 인증 처리를 지금 못 한다는 <b>일시</b> 오류다(RFC 4954 §6).
     * 이걸 RATE_LIMITED로 세면 제공자 quota 지표와 경보가 인증 장애를 발송량 초과로 오해한다.
     */
    @Test
    @DisplayName("454 일시 인증 실패는 quota(RATE_LIMITED)로 분류하지 않는다")
    void 일시_인증실패는_레이트리밋이_아니다() {
        RuntimeException e = SmtpFailureClassifier.classifyReplyCode(454);

        assertThat(e).isInstanceOf(RetryableChannelException.class);
        ChannelFailureCode code = ((RetryableChannelException) e).getFailureCode();
        assertThat(code).isNotEqualTo(ChannelFailureCode.CHANNEL_RATE_LIMITED);
        assertThat(code).isEqualTo(ChannelFailureCode.CHANNEL_UNAVAILABLE);
        // 인증 핸드셰이크 단계에서 거절됐으므로 메일은 확실히 나가지 않았다
        assertThat(code.isDeliveryUnknown()).isFalse();
    }

    /** P0-a. 숫자 코드만으로는 제공자별 rate limit을 단정할 수 없다. 일반 일시 오류로 둔다. */
    @Test
    @DisplayName("제공자 문맥 없는 비표준 471도 rate limit으로 단정하지 않는다")
    void 비표준코드는_레이트리밋으로_단정하지_않는다() {
        ChannelFailureCode code = ((RetryableChannelException) SmtpFailureClassifier.classifyReplyCode(471))
                .getFailureCode();
        assertThat(code).isEqualTo(ChannelFailureCode.CHANNEL_UNAVAILABLE);
    }

    /**
     * P0-a. 같은 "인증"이라도 454(일시)와 535(영구)는 재시도 여부가 반대다.
     * 수신자 오류(550)와도 섞이면 안 된다 — 키 교체와 주소 정정은 다른 운영 대응이다.
     */
    @Test
    @DisplayName("일시 인증 / 영구 인증 / 수신자 오류는 서로 다른 코드로 남는다")
    void 인증오류와_수신자오류를_구별한다() {
        ChannelFailureCode temporaryAuth = ((RetryableChannelException) SmtpFailureClassifier.classifyReplyCode(454))
                .getFailureCode();
        ChannelFailureCode permanentAuth = ((NonRetryableChannelException) SmtpFailureClassifier.classifyReplyCode(535))
                .getFailureCode();
        ChannelFailureCode recipient = ((NonRetryableChannelException) SmtpFailureClassifier.classifyReplyCode(550))
                .getFailureCode();

        assertThat(temporaryAuth.isRetryable()).isTrue();
        assertThat(permanentAuth).isEqualTo(ChannelFailureCode.CHANNEL_AUTH_FAILED);
        assertThat(permanentAuth.isRetryable()).isFalse();
        assertThat(recipient).isEqualTo(ChannelFailureCode.CHANNEL_INVALID_TARGET);
        assertThat(List.of(temporaryAuth, permanentAuth, recipient)).doesNotHaveDuplicates();
    }
}
