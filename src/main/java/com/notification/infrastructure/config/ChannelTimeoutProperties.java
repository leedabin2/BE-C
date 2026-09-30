package com.notification.infrastructure.config;
// PRD: F3-1, F4-1, F5-1 → docs/DECISIONS.md D-017

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 외부 채널 발송 타임아웃.
 *
 * <p>⚠️ <b>Jakarta Mail의 세 타임아웃 기본값은 전부 infinite다.</b> 명시하지 않으면
 * 응답 없는 서버 하나가 워커 스레드를 영구히 붙잡는다. 워커가 다 묶이면 발송이 멈춘다.
 *
 * <p>최악 소요 = connect + (읽기 왕복 수 × read). SMTP는 EHLO·STARTTLS·AUTH·MAIL·RCPT·DATA·QUIT로
 * 왕복이 여러 번이라 read 타임아웃이 한 번만 걸리지 않는다. 그래서 왕복 수를 곱해 상한을 잡는다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "notification.channel.timeout")
public class ChannelTimeoutProperties {

    /** TCP 연결. 서버가 살아 있으면 수십 ms다. 5초면 충분하고, 길면 장애 감지가 늦어진다. */
    private int connectMs = 5_000;

    /** 소켓 읽기(응답 대기). DATA 전송 후 서버 처리가 느릴 수 있어 30초. */
    private int readMs = 30_000;

    /** 소켓 쓰기(본문 전송). 첨부가 크면 느려진다. */
    private int writeMs = 30_000;

    /** SMTP 한 통을 보내는 데 필요한 읽기 왕복 수. EHLO·STARTTLS·AUTH·MAIL·RCPT·DATA·QUIT. */
    private int roundTrips = 7;

    /** 발송 한 건의 최악 소요 시간(초). 이 값이 Stuck 임계보다 충분히 작아야 한다. */
    public int worstCaseSeconds() {
        long worstMs = connectMs + (long) roundTrips * Math.max(readMs, writeMs);
        return (int) Math.min(worstMs / 1000, Integer.MAX_VALUE);
    }
}
