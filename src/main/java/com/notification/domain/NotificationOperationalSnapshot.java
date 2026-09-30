package com.notification.domain;

/**
 * 한 시점의 전역 알림 운영 지표. DB 집계 조회 결과라서 정확한 원장/감사 값이 아니라 관측용 근사치다.
 * 모든 시각은 세션 시간대와 무관한 DB {@code UTC_TIMESTAMP(6)}를 기준으로 계산한다.
 */
public record NotificationOperationalSnapshot(
        long dueBacklog,
        long oldestDueAgeSeconds,
        long processingCount,
        long expiredLeaseCount,
        long retryingCount,
        long failedCount
) {
}
