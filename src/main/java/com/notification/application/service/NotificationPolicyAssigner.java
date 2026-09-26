package com.notification.application.service;

import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationType;
import com.notification.domain.PolicyVersion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 등록 시점에 이 알림의 <b>시간창과 정책 버전</b>을 확정한다. (재시도 정책 §5.5)
 *
 * <p>한 번 정한 값은 재시도·재시작·같은 키 재등록으로 바뀌지 않는다. 기한이 연장되면
 * "언제까지 보낼 가치가 있는가"라는 업무 판단이 장애 상황에 따라 흔들린다.
 *
 * <p><b>활성화는 플래그로 막는다.</b> 구버전 worker가 TYPE_V1 행을 선점할 수 있는 동안에는 켜지 않는다.
 * 꺼져 있으면 신규 행도 LEGACY_V0로 등록돼 기존 의미 그대로 동작한다. (재시도 정책 §5.7)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationPolicyAssigner {

    /** 등록 시 확정하는 값. eligibleAt은 조회 기준, expiresAt은 발송 시작 허용 기한이다. */
    public record AssignedPolicy(PolicyVersion policyVersion, LocalDateTime eligibleAt, LocalDateTime expiresAt) {
    }

    private final RetryDecisionPolicy retryDecisionPolicy;

    @Value("${notification.policy.type-v1-enabled:false}")
    private boolean typeV1Enabled;

    /**
     * @param now         DB UTC 등록 시각. 앱 시계를 쓰지 않는다
     * @param scheduledAt 업무 예약 시각. 있으면 그때부터가 발송 가능 시작점이다
     * @param requestedExpiresAt 생산자가 준 절대 기한. 기본 기한보다 길게 늘리는 용도로는 쓰지 않는다
     * @throws IllegalArgumentException 발송 가능한 시간창이 아예 없을 때 (400)
     */
    public AssignedPolicy assign(NotificationType type, NotificationChannel channel,
                                 LocalDateTime now, LocalDateTime scheduledAt,
                                 LocalDateTime requestedExpiresAt) {
        // 예약이 과거여도 등록 시각보다 이르게 잡지 않는다. eligibleAt은 "가장 이른 발송 가능 시각"이다.
        LocalDateTime eligibleAt = scheduledAt != null && scheduledAt.isAfter(now) ? scheduledAt : now;

        if (!appliesTypeV1(channel)) {
            // 기존 의미 보존: 기한 없음. 과거 업무의 기한을 임의로 만들어내지 않는다.
            return new AssignedPolicy(PolicyVersion.LEGACY_V0, eligibleAt, null);
        }

        LocalDateTime expiresAt = resolveExpiresAt(type, eligibleAt, requestedExpiresAt);
        if (!expiresAt.isAfter(eligibleAt)) {
            throw new IllegalArgumentException(
                    "expiresAt은 발송 가능 시각(%s)보다 뒤여야 합니다. 보낼 수 있는 시간창이 없습니다.".formatted(eligibleAt));
        }
        return new AssignedPolicy(PolicyVersion.TYPE_V1, eligibleAt, expiresAt);
    }

    /** IN_APP 알림함 저장은 메일 업체 호출이 아니므로 이번 단계의 기한 정책 대상이 아니다. */
    private boolean appliesTypeV1(NotificationChannel channel) {
        return typeV1Enabled && channel == NotificationChannel.EMAIL;
    }

    /**
     * 기본 기한은 <b>등록 시각이 아니라 eligibleAt 기준</b>이다. 그래야 다음 달 예약이 오늘 만료되지 않는다.
     * 생산자 입력이 있으면 더 짧은 쪽을 택한다 — 입력으로 기본 기한을 늘릴 수 있으면 정책이 무의미해진다.
     */
    private LocalDateTime resolveExpiresAt(NotificationType type, LocalDateTime eligibleAt,
                                           LocalDateTime requestedExpiresAt) {
        Optional<Duration> defaultTtl = retryDecisionPolicy.defaultTtl(type);
        if (defaultTtl.isEmpty()) {
            if (requestedExpiresAt == null) {
                throw new IllegalArgumentException(
                        "%s 알림은 업무 기한(expiresAt)을 생산자가 지정해야 합니다.".formatted(type));
            }
            return requestedExpiresAt;
        }

        LocalDateTime defaultExpiresAt = eligibleAt.plus(defaultTtl.get());
        return requestedExpiresAt == null || defaultExpiresAt.isBefore(requestedExpiresAt)
                ? defaultExpiresAt
                : requestedExpiresAt;
    }
}
