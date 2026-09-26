package com.notification.application.service;

import com.notification.domain.DispatchFailureReason;
import com.notification.domain.Notification;
import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationStatus;
import com.notification.domain.NotificationType;
import com.notification.domain.PolicyVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-a. 타입별 재시도 예산·기한 판정. (재시도 정책 §5.5~§5.6)
 *
 * <p>판정 순서 자체가 계약이다: <b>만료 → 예산 소진 → 창 초과 → 재시도</b>.
 * 순서가 바뀌면 같은 상황이 다른 사유로 기록돼 운영 대응이 달라진다.
 * 예컨대 기한이 지난 건을 "예산 소진"으로 남기면 예산만 늘리면 될 문제로 오해한다.
 */
@DisplayName("재시도 종료 판정")
class RetryDecisionPolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 25, 10, 0);
    private static final int JITTER = 20;

    /** 지터를 20초로 고정한다. 경계 판정을 난수에 맡기면 테스트가 계약을 못 지킨다. */
    private final RetryDecisionPolicy policy = new RetryDecisionPolicy(new RetrySchedulePolicy(() -> JITTER));

    private Notification notification(PolicyVersion version, NotificationType type, LocalDateTime expiresAt) {
        return Notification.builder()
                .receiverId(1L).notificationType(type).channel(NotificationChannel.EMAIL)
                .eventId("evt").idempotencyKey("key")
                .policyVersion(version).eligibleAt(NOW).expiresAt(expiresAt)
                .build();
    }

    /**
     * 이번 결과 이전까지 누적된 실패가 {@code failures}회인 <b>새</b> 알림을 만든다.
     * 같은 인스턴스를 재사용하면 호출마다 횟수가 누적돼 경계 검증이 어긋난다.
     */
    private Notification afterFailures(PolicyVersion version, NotificationType type,
                                       LocalDateTime expiresAt, int failures) {
        Notification n = notification(version, type, expiresAt);
        for (int i = 0; i < failures; i++) {
            n.applyRetryableFailure(NotificationStatus.RETRYING, null, "CHANNEL_UNAVAILABLE", NOW.plusMinutes(1));
        }
        return n;
    }

    @Nested
    @DisplayName("LEGACY_V0 (기존 행)")
    class Legacy {

        @Test
        @DisplayName("기한이 없고 1분·5분 뒤 재시도, 3번째 실패에서 종료한다 — 기존 의미 그대로")
        void 기존_의미를_보존한다() {
            Notification n = notification(PolicyVersion.LEGACY_V0, NotificationType.PAYMENT_CONFIRMED, null);

            var first = policy.onRetryableFailure(n, NOW, Optional.empty());
            assertThat(first.status()).isEqualTo(NotificationStatus.RETRYING);
            assertThat(first.nextEligibleAt()).isEqualTo(NOW.plusMinutes(1).plusSeconds(JITTER));

            var second = policy.onRetryableFailure(
                    afterFailures(PolicyVersion.LEGACY_V0, NotificationType.PAYMENT_CONFIRMED, null, 1),
                    NOW, Optional.empty());
            assertThat(second.nextEligibleAt()).isEqualTo(NOW.plusMinutes(5).plusSeconds(JITTER));

            var third = policy.onRetryableFailure(
                    afterFailures(PolicyVersion.LEGACY_V0, NotificationType.PAYMENT_CONFIRMED, null, 2),
                    NOW, Optional.empty());
            assertThat(third.status()).isEqualTo(NotificationStatus.FAILED);
            assertThat(third.reason()).isEqualTo(DispatchFailureReason.RETRY_EXHAUSTED.name());
            assertThat(third.nextEligibleAt()).isNull();
        }

        @Test
        @DisplayName("기한이 없으므로 EXPIRED·RETRY_WINDOW_EXCEEDED가 나오지 않는다")
        void 기한_판정을_소급하지_않는다() {
            Notification n = notification(PolicyVersion.LEGACY_V0, NotificationType.LECTURE_START_REMINDER, null);

            var decision = policy.onRetryableFailure(n, NOW, Optional.of(Duration.ofHours(10)));

            assertThat(decision.status()).isEqualTo(NotificationStatus.RETRYING);
            assertThat(decision.nextEligibleAt()).isEqualTo(NOW.plusHours(10).plusSeconds(JITTER));
        }
    }

    @Nested
    @DisplayName("TYPE_V1 타입별 예산")
    class TypeV1Budget {

        @ParameterizedTest(name = "{0}: {1}번째 실패에서 종료")
        @CsvSource({
                "PAYMENT_CONFIRMED, 7",       // 1·5·15분·1·3·6시간 → 최초 포함 7회
                "ENROLLMENT_COMPLETED, 5",    // 1·5·15분·1시간
                "ENROLLMENT_CANCELLED, 5",
                "LECTURE_START_REMINDER, 4"   // 1·5·15분
        })
        @DisplayName("타입마다 실패 예산이 다르고, 그 전까지는 계속 재시도한다")
        void 타입별_예산_경계(NotificationType type, int terminalFailure) {
            // 기한은 넉넉히 둬서 예산만 검증한다
            LocalDateTime farFuture = NOW.plusDays(30);

            for (int failure = 1; failure < terminalFailure; failure++) {
                var decision = policy.onRetryableFailure(
                        afterFailures(PolicyVersion.TYPE_V1, type, farFuture, failure - 1), NOW, Optional.empty());
                assertThat(decision.status()).as("%d번째 실패", failure).isEqualTo(NotificationStatus.RETRYING);
            }

            var last = policy.onRetryableFailure(
                    afterFailures(PolicyVersion.TYPE_V1, type, farFuture, terminalFailure - 1),
                    NOW, Optional.empty());
            assertThat(last.status()).isEqualTo(NotificationStatus.FAILED);
            assertThat(last.reason()).isEqualTo(DispatchFailureReason.RETRY_EXHAUSTED.name());
        }

        @Test
        @DisplayName("결제 알림의 지연은 실패 시각 기준 1·5·15분·1·3·6시간이다 (최초 발송부터의 누적이 아니다)")
        void 결제_지연_목록() {
            Duration[] expected = {Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
                    Duration.ofHours(1), Duration.ofHours(3), Duration.ofHours(6)};

            for (int i = 0; i < expected.length; i++) {
                var decision = policy.onRetryableFailure(
                        afterFailures(PolicyVersion.TYPE_V1, NotificationType.PAYMENT_CONFIRMED, NOW.plusDays(30), i),
                        NOW, Optional.empty());
                assertThat(decision.nextEligibleAt())
                        .as("%d번째 실패 후 지연", i + 1)
                        .isEqualTo(NOW.plus(expected[i]).plusSeconds(JITTER));
            }
        }
    }

    @Nested
    @DisplayName("TYPE_V1 기한 판정")
    class TypeV1Ttl {

        private Notification payment(LocalDateTime expiresAt) {
            return notification(PolicyVersion.TYPE_V1, NotificationType.PAYMENT_CONFIRMED, expiresAt);
        }

        @Test
        @DisplayName("기한이 이미 지났으면 예산이 남아도 EXPIRED — 지금 보내도 의미가 없다")
        void 만료가_예산보다_먼저다() {
            var decision = policy.onRetryableFailure(payment(NOW.minusSeconds(1)), NOW, Optional.empty());

            assertThat(decision.status()).isEqualTo(NotificationStatus.FAILED);
            assertThat(decision.reason()).isEqualTo(DispatchFailureReason.EXPIRED.name());
        }

        @Test
        @DisplayName("기한과 같은 시각도 만료다 — 발송 '시작' 허용 기한이므로 경계는 닫힌다")
        void 기한_동일_시각은_만료다() {
            var decision = policy.onRetryableFailure(payment(NOW), NOW, Optional.empty());

            assertThat(decision.reason()).isEqualTo(DispatchFailureReason.EXPIRED.name());
        }

        @Test
        @DisplayName("아직 기한 전이지만 다음 재시도가 기한 밖이면 RETRY_WINDOW_EXCEEDED — 기다릴 이유가 없다")
        void 다음_시도가_기한을_넘으면_종료한다() {
            // 다음 재시도는 NOW+1분+20초인데 기한은 NOW+1분
            var decision = policy.onRetryableFailure(payment(NOW.plusMinutes(1)), NOW, Optional.empty());

            assertThat(decision.status()).isEqualTo(NotificationStatus.FAILED);
            assertThat(decision.reason()).isEqualTo(DispatchFailureReason.RETRY_WINDOW_EXCEEDED.name());
            assertThat(decision.nextEligibleAt()).isNull();
        }

        @Test
        @DisplayName("다음 재시도가 기한과 정확히 같으면 보내지 않는다 (next >= expiresAt)")
        void 다음_시도가_기한과_같으면_종료한다() {
            var decision = policy.onRetryableFailure(payment(NOW.plusMinutes(1).plusSeconds(JITTER)), NOW, Optional.empty());

            assertThat(decision.reason()).isEqualTo(DispatchFailureReason.RETRY_WINDOW_EXCEEDED.name());
        }

        @Test
        @DisplayName("다음 재시도가 기한 1초 전이면 재시도한다")
        void 기한_안쪽은_재시도한다() {
            var decision = policy.onRetryableFailure(
                    payment(NOW.plusMinutes(1).plusSeconds(JITTER + 1)), NOW, Optional.empty());

            assertThat(decision.status()).isEqualTo(NotificationStatus.RETRYING);
            assertThat(decision.nextEligibleAt()).isEqualTo(NOW.plusMinutes(1).plusSeconds(JITTER));
        }

        @Test
        @DisplayName("예산 소진과 기한 초과가 겹치면 EXPIRED가 우선한다")
        void 만료가_예산소진보다_우선한다() {
            Notification n = afterFailures(PolicyVersion.TYPE_V1,
                    NotificationType.PAYMENT_CONFIRMED, NOW.minusMinutes(1), 6);

            var decision = policy.onRetryableFailure(n, NOW, Optional.empty());

            assertThat(decision.reason()).isEqualTo(DispatchFailureReason.EXPIRED.name());
        }
    }

    @Nested
    @DisplayName("Retry-After")
    class RetryAfter {

        @Test
        @DisplayName("제공자 지시가 기본 지연보다 길면 그쪽을 따른다 — 짧게 잘라 다시 두드리지 않는다")
        void 더_긴_지시를_따른다() {
            Notification n = notification(PolicyVersion.TYPE_V1, NotificationType.PAYMENT_CONFIRMED, NOW.plusDays(1));

            var decision = policy.onRetryableFailure(n, NOW, Optional.of(Duration.ofMinutes(30)));

            assertThat(decision.nextEligibleAt()).isEqualTo(NOW.plusMinutes(30).plusSeconds(JITTER));
        }

        @Test
        @DisplayName("제공자 지시가 기본 지연보다 짧으면 기본 지연을 쓴다")
        void 더_짧은_지시는_무시한다() {
            Notification n = notification(PolicyVersion.TYPE_V1, NotificationType.PAYMENT_CONFIRMED, NOW.plusDays(1));

            var decision = policy.onRetryableFailure(n, NOW, Optional.of(Duration.ofSeconds(5)));

            assertThat(decision.nextEligibleAt()).isEqualTo(NOW.plusMinutes(1).plusSeconds(JITTER));
        }

        @Test
        @DisplayName("긴 Retry-After가 남은 기한을 넘기면 기다리지 않고 RETRY_WINDOW_EXCEEDED로 끝낸다")
        void 지시가_기한을_넘으면_종료한다() {
            // 강의 시작 15:00, 지금 14:59, 제공자는 120초 뒤에 오라고 한다
            Notification n = notification(PolicyVersion.TYPE_V1,
                    NotificationType.LECTURE_START_REMINDER, NOW.plusMinutes(1));

            var decision = policy.onRetryableFailure(n, NOW, Optional.of(Duration.ofSeconds(120)));

            assertThat(decision.status()).isEqualTo(NotificationStatus.FAILED);
            assertThat(decision.reason()).isEqualTo(DispatchFailureReason.RETRY_WINDOW_EXCEEDED.name());
        }
    }

    @Nested
    @DisplayName("lease 회수는 실패 예산과 분리된다 (§5.6)")
    class Recovery {

        @Test
        @DisplayName("회수는 1분 뒤 재시도로 돌려놓고, 실패 예산은 건드리지 않는다")
        void 회수는_실패_예산을_쓰지_않는다() {
            // 실패 예산은 이미 다 썼지만, 회수는 그 예산과 무관해야 한다
            Notification n = afterFailures(PolicyVersion.TYPE_V1,
                    NotificationType.PAYMENT_CONFIRMED, NOW.plusDays(1), 6);

            var decision = policy.onLeaseRecovery(n, NOW);

            assertThat(decision.status()).as("회수는 발송 실패를 관측한 것이 아니다")
                    .isEqualTo(NotificationStatus.RETRYING);
            assertThat(decision.nextEligibleAt()).isEqualTo(NOW.plusMinutes(1).plusSeconds(JITTER));
        }

        @Test
        @DisplayName("세 번째 회수에서 RECOVERY_EXHAUSTED로 끝낸다 — 고장 난 worker의 무한 선점을 막는다")
        void 회수_상한에서_종료한다() {
            Notification n = notification(PolicyVersion.TYPE_V1, NotificationType.PAYMENT_CONFIRMED, NOW.plusDays(1));

            assertThat(policy.onLeaseRecovery(n, NOW).status()).isEqualTo(NotificationStatus.RETRYING);
            n.applyLeaseRecovery(NotificationStatus.RETRYING, null, NOW.plusMinutes(1));
            assertThat(policy.onLeaseRecovery(n, NOW).status()).isEqualTo(NotificationStatus.RETRYING);
            n.applyLeaseRecovery(NotificationStatus.RETRYING, null, NOW.plusMinutes(1));

            var third = policy.onLeaseRecovery(n, NOW);

            assertThat(third.status()).isEqualTo(NotificationStatus.FAILED);
            assertThat(third.reason()).isEqualTo(DispatchFailureReason.RECOVERY_EXHAUSTED.name());
            assertThat(n.cycleRetryFailureCount()).as("회수 세 번 동안 실패 예산은 그대로").isZero();
        }

        @Test
        @DisplayName("회수 시점에 기한이 지났으면 EXPIRED로 끝낸다")
        void 회수_중_만료는_EXPIRED다() {
            Notification n = notification(PolicyVersion.TYPE_V1, NotificationType.PAYMENT_CONFIRMED, NOW.minusSeconds(1));

            var decision = policy.onLeaseRecovery(n, NOW);

            assertThat(decision.reason()).isEqualTo(DispatchFailureReason.EXPIRED.name());
        }

        @Test
        @DisplayName("LEGACY_V0의 회수는 기존대로 실패 1회로 세고 3회에서 FAILED가 된다")
        void 기존_행의_회수는_예전_의미를_유지한다() {
            Notification n = afterFailures(PolicyVersion.LEGACY_V0,
                    NotificationType.PAYMENT_CONFIRMED, null, 2);

            var decision = policy.onLeaseRecovery(n, NOW);

            assertThat(decision.status()).isEqualTo(NotificationStatus.FAILED);
            assertThat(decision.reason()).isEqualTo("PROCESSING_STUCK");
        }
    }
}
