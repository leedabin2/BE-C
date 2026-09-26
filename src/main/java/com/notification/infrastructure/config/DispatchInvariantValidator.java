package com.notification.infrastructure.config;
// PRD: F3-1(중복 발송 금지), F5-1(Stuck 복구) → docs/DECISIONS.md D-014, D-017

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 발송 불변식을 <b>기동 시점에</b> 검증한다. 깨진 설정으로는 뜨지 않는다.
 *
 * <pre>
 * 발송 최악 소요 &lt; Stuck 임계
 * </pre>
 *
 * 이걸 어기면 Stuck 복구가 <b>아직 발송 중인 건</b>을 PENDING으로 되돌리고,
 * 다른 워커가 같은 알림을 다시 발송한다 → 중복. 로그로 알아채려면 이미 메일이 두 번 나간 뒤다.
 * 그래서 런타임이 아니라 기동 시점에 막는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DispatchInvariantValidator {

    /** 최악 소요와 임계 사이에 요구하는 최소 배수. 재시도·GC·스케줄러 지연 몫이다. */
    private static final int MIN_SAFETY_FACTOR = 2;

    private final ChannelTimeoutProperties timeout;

    @Value("${notification.scheduler.stuck.threshold-minutes:10}")
    private int stuckThresholdMinutes;

    @Value("${notification.dispatch.min-remaining-lease-seconds:220}")
    private int minRemainingLeaseSeconds;

    @PostConstruct
    void verify() {
        int worstCase = timeout.worstCaseSeconds();
        int threshold = stuckThresholdMinutes * 60;

        if (worstCase * MIN_SAFETY_FACTOR > threshold) {
            throw new IllegalStateException("""
                    발송 불변식 위반: 발송 최악 소요(%d초) × 안전계수 %d > Stuck 임계(%d초).
                    Stuck 복구가 발송 중인 건을 되돌려 중복 발송이 발생한다.
                    조치: 타임아웃을 줄이거나 notification.scheduler.stuck.threshold-minutes를 늘린다."""
                    .formatted(worstCase, MIN_SAFETY_FACTOR, threshold));
        }
        // 호출 직전 방어가 요구하는 잔여 lease 예산도 같은 축에 있어야 의미가 있다.
        // 최악 소요보다 작으면 "끝낼 수 없는 호출"을 그대로 통과시키고,
        // lease 전체보다 크거나 같으면 갓 선점한 작업조차 매번 반환돼 아무것도 못 보낸다.
        if (minRemainingLeaseSeconds < worstCase) {
            throw new IllegalStateException("""
                    발송 방어 불변식 위반: 필요 잔여 lease(%d초) < 발송 최악 소요(%d초).
                    lease 안에 끝낼 수 없는 외부 호출을 시작하게 된다.
                    조치: notification.dispatch.min-remaining-lease-seconds를 최악 소요 이상으로 올린다."""
                    .formatted(minRemainingLeaseSeconds, worstCase));
        }
        if (minRemainingLeaseSeconds >= threshold) {
            throw new IllegalStateException("""
                    발송 방어 불변식 위반: 필요 잔여 lease(%d초) >= lease 길이(%d초).
                    선점 직후에도 예산을 못 채워 모든 발송이 반환된다.
                    조치: 예산을 줄이거나 notification.scheduler.stuck.threshold-minutes를 늘린다."""
                    .formatted(minRemainingLeaseSeconds, threshold));
        }

        log.info("발송 불변식 확인 — 최악 소요 {}초 ≤ 호출 전 필요 잔여 {}초 < lease {}초 (여유 {}배)",
                worstCase, minRemainingLeaseSeconds, threshold, threshold / Math.max(worstCase, 1));
    }
}
