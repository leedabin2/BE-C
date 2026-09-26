package com.notification.application.service;

import com.notification.domain.DispatchFailureReason;
import com.notification.domain.Notification;
import com.notification.domain.NotificationStatus;
import com.notification.domain.NotificationType;
import com.notification.domain.PolicyVersion;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * "이번 결과를 반영하고 나면 이 알림을 어떻게 끝낼 것인가"를 결정한다. (재시도 정책 §5.5~§5.6)
 *
 * <p>순수 계산만 한다. DB도 시계도 만지지 않고 기준 시각(DB UTC)을 인수로 받는다. 상태 기록은
 * {@code Notification}이 맡는다 — 상태 머신이 타입별 지연 목록이나 기한을 알 필요가 없도록 나눴다.
 *
 * <p><b>판정 순서가 곧 계약이다.</b> 만료 → 예산 소진 → 창 초과 → 재시도. 순서가 바뀌면 같은 상황이
 * 다른 사유로 남아 운영 대응이 달라진다(기한이 지난 건을 "예산 소진"으로 남기면 예산만 늘리면 될
 * 문제로 오해한다).
 *
 * <p>정책은 운영 CRUD 테이블이 아니라 <b>코드의 불변 버전</b>이다. 알림은 등록 당시 버전으로 끝까지
 * 판정하므로, 배포 중에 이미 진행 중인 알림의 예산이나 기한이 바뀌지 않는다.
 */
@Component
@RequiredArgsConstructor
public class RetryDecisionPolicy {

    /** 회수 뒤 다시 잡을 때까지의 대기. 회수는 제공자 실패가 아니므로 타입별 지연을 쓰지 않는다. */
    private static final Duration RECOVERY_DELAY = Duration.ofMinutes(1);

    /** lease 회수 상한(초기 시험값). 세 번째 회수는 고장 난 worker의 무한 선점으로 보고 끝낸다. */
    static final int MAX_RECOVERY_COUNT = 3;

    /** LEGACY_V0: P1 이전 행의 의미를 그대로 보존한다. 1분·5분 뒤 재시도, 세 번째 실패에서 종료. */
    private static final List<Duration> LEGACY_DELAYS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5));

    /**
     * TYPE_V1 타입별 지연. 각 값은 <b>실패 결과를 저장한 시각부터</b> 기다릴 간격이지,
     * 최초 발송부터의 누적 시각이 아니다. 최초 발송을 포함한 상한은 "지연 수 + 1"이다.
     */
    private static final Map<NotificationType, List<Duration>> TYPE_V1_DELAYS = Map.of(
            NotificationType.PAYMENT_CONFIRMED, List.of(
                    Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
                    Duration.ofHours(1), Duration.ofHours(3), Duration.ofHours(6)),
            NotificationType.ENROLLMENT_COMPLETED, List.of(
                    Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofHours(1)),
            NotificationType.ENROLLMENT_CANCELLED, List.of(
                    Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofHours(1)),
            NotificationType.LECTURE_START_REMINDER, List.of(
                    Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15)));

    /**
     * TYPE_V1 타입별 기본 기한. <b>초기 시험 정책이지 확정 업무 SLA가 아니다.</b>
     * 강의 시작 안내가 빠진 것은 누락이 아니라 계약이다 — 업무 기한(강의 시작 시각)은 생산자만 알 수 있고,
     * "등록 후 N시간"으로 대신하면 다음 달 강의 안내가 오늘 만료된다.
     */
    private static final Map<NotificationType, Duration> TYPE_V1_DEFAULT_TTL = Map.of(
            NotificationType.PAYMENT_CONFIRMED, Duration.ofHours(24),
            NotificationType.ENROLLMENT_COMPLETED, Duration.ofHours(6),
            NotificationType.ENROLLMENT_CANCELLED, Duration.ofHours(12));

    /** @return 기본 기한. 비어 있으면 생산자가 절대 {@code expiresAt}을 줘야 하는 타입이다 */
    public Optional<Duration> defaultTtl(NotificationType type) {
        return Optional.ofNullable(TYPE_V1_DEFAULT_TTL.get(type));
    }

    /**
     * @param nextEligibleAt RETRYING일 때 다음 발송 가능 시각. 종료 결정이면 null
     * @param reason         종료 사유. RETRYING이면 null
     */
    public record Decision(NotificationStatus status, String reason, LocalDateTime nextEligibleAt) {

        static Decision retry(LocalDateTime nextEligibleAt) {
            return new Decision(NotificationStatus.RETRYING, null, nextEligibleAt);
        }

        static Decision stop(DispatchFailureReason reason) {
            return new Decision(NotificationStatus.FAILED, reason.name(), null);
        }
    }

    private final RetrySchedulePolicy schedulePolicy;

    /**
     * 재시도 가능한 발송 실패의 결과 판정. (재시도 정책 §5.5 3~6번)
     *
     * <p>성공과 영구 오류(1·2번)는 판정할 것이 없으므로 호출자가 먼저 처리한다.
     *
     * @param retryAfter 제공자가 지시한 최소 대기. 우리 기본 지연보다 짧아도 무시되지 않고 더 긴 쪽이 이긴다
     */
    public Decision onRetryableFailure(Notification notification, LocalDateTime now, Optional<Duration> retryAfter) {
        if (isExpired(notification, now)) {
            return Decision.stop(DispatchFailureReason.EXPIRED);
        }

        List<Duration> delays = delaysOf(notification);
        int failureCount = notification.cycleRetryFailureCount() + 1;   // 이번 결과를 포함한 실패 횟수
        if (failureCount > delays.size()) {
            return Decision.stop(DispatchFailureReason.RETRY_EXHAUSTED);
        }

        LocalDateTime next = schedulePolicy.scheduleAfter(delays.get(failureCount - 1), retryAfter, now);
        return withinWindow(notification, next)
                ? Decision.retry(next)
                : Decision.stop(DispatchFailureReason.RETRY_WINDOW_EXCEEDED);
    }

    /**
     * 만료 lease 회수의 결과 판정. (재시도 정책 §5.6)
     *
     * <p>회수는 <b>발송 실패를 관측한 것이 아니다.</b> 소유자가 사라졌을 뿐이라 실패 예산을 쓰지 않고,
     * 대신 별도 회수 상한으로 고장 난 worker의 무한 선점을 막는다.
     *
     * <p>LEGACY_V0는 전환 전 의미를 유지한다 — 회수도 실패 1회로 세고 기존 예산을 따른다.
     * 기존 행의 판정을 바꾸면 이미 진행 중인 알림의 결과가 배포 시점에 달라진다.
     */
    public Decision onLeaseRecovery(Notification notification, LocalDateTime now) {
        if (notification.policyVersion() == PolicyVersion.LEGACY_V0) {
            Decision legacy = onRetryableFailure(notification, now, Optional.empty());
            // 기존 이력과 같은 사유를 남긴다. 제공자 오류가 아니라 회수였음을 구분하기 위해서다.
            return legacy.status() == NotificationStatus.RETRYING
                    ? legacy
                    : new Decision(NotificationStatus.FAILED, "PROCESSING_STUCK", null);
        }

        if (isExpired(notification, now)) {
            return Decision.stop(DispatchFailureReason.EXPIRED);
        }
        if (notification.getCycleRecoveryCount() + 1 >= MAX_RECOVERY_COUNT) {
            return Decision.stop(DispatchFailureReason.RECOVERY_EXHAUSTED);
        }

        LocalDateTime next = schedulePolicy.scheduleAfter(RECOVERY_DELAY, Optional.empty(), now);
        return withinWindow(notification, next)
                ? Decision.retry(next)
                : Decision.stop(DispatchFailureReason.RETRY_WINDOW_EXCEEDED);
    }

    /** 타입별 지연 목록. LEGACY_V0는 버전이 고정이므로 타입을 보지 않는다. */
    private List<Duration> delaysOf(Notification notification) {
        if (notification.policyVersion() == PolicyVersion.LEGACY_V0) {
            return LEGACY_DELAYS;
        }
        return TYPE_V1_DELAYS.getOrDefault(notification.getNotificationType(), LEGACY_DELAYS);
    }

    /** 기한은 발송 <b>시작</b> 허용선이다. 같은 시각이면 이미 닫힌 것으로 본다. */
    private boolean isExpired(Notification notification, LocalDateTime now) {
        LocalDateTime expiresAt = notification.getExpiresAt();
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /** {@code next >= expiresAt}이면 기다려도 보낼 수 없다. 기한이 없으면 항상 통과한다. */
    private boolean withinWindow(Notification notification, LocalDateTime next) {
        LocalDateTime expiresAt = notification.getExpiresAt();
        return expiresAt == null || next.isBefore(expiresAt);
    }
}
