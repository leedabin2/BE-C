package com.notification.application.service;

import com.notification.application.port.in.result.NotificationDetailResult;
import com.notification.application.port.out.NotificationRepositoryPort;
import com.notification.common.exception.ErrorCode;
import com.notification.common.exception.NotificationException;
import com.notification.domain.Notification;
import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class GetNotificationServiceTest {

    @Mock NotificationRepositoryPort notificationRepositoryPort;

    @InjectMocks GetNotificationService getNotificationService;

    private Notification notification;

    @BeforeEach
    void setUp() {
        notification = Notification.builder()
                .receiverId(42L)
                .notificationType(NotificationType.PAYMENT_CONFIRMED)
                .channel(NotificationChannel.EMAIL)
                .eventId("evt-001")
                .idempotencyKey("test-key")
                .build();
    }

    // ── getById ───────────────────────────────────────────────────────────────

    // [시나리오] 정상 케이스 — receiverId와 requesterId가 일치할 때 조회 성공 여부 검증
    @Test
    @DisplayName("정상 조회: 본인 알림이면 NotificationDetailResult 반환")
    void getById_ownNotification_returnsResult() {
        given(notificationRepositoryPort.findById(1L)).willReturn(Optional.of(notification));

        NotificationDetailResult result = getNotificationService.getById(1L, 42L);

        assertThat(result).isNotNull();
        assertThat(result.receiverId()).isEqualTo(42L);
    }

    // [시나리오] 삭제됐거나 잘못된 ID 요청 → NPE·500 대신 명확한 404 응답이 내려가야 함
    // → NOTIFICATION_NOT_FOUND 에러코드로 NotificationException이 던져지는지 검증
    @Test
    @DisplayName("존재하지 않는 id: NOTIFICATION_NOT_FOUND 예외")
    void getById_notFound_throwsException() {
        given(notificationRepositoryPort.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> getNotificationService.getById(99L, 42L))
                .isInstanceOf(NotificationException.class)
                .satisfies(e -> assertThat(((NotificationException) e).getErrorCode())
                        .isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND));
    }

    // [시나리오] 공격자가 순차 ID로 타인 알림을 열람 시도 → 403이면 존재 여부가 노출됨
    // → 403 대신 404(NOTIFICATION_NOT_FOUND)를 반환해 알림 존재 자체를 숨기는지 검증
    @Test
    @DisplayName("IDOR 방지: 타인 알림 접근 시 404 (존재 자체를 숨김)")
    void getById_otherUsersNotification_throwsNotFoundException() {
        given(notificationRepositoryPort.findById(1L)).willReturn(Optional.of(notification));

        // receiverId=42인 알림을 requesterId=99가 조회 시도
        assertThatThrownBy(() -> getNotificationService.getById(1L, 99L))
                .isInstanceOf(NotificationException.class)
                .satisfies(e -> assertThat(((NotificationException) e).getErrorCode())
                        .isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND));
    }

    // ── getByReceiver ─────────────────────────────────────────────────────────

    // [시나리오] receiverId를 쿼리 파라미터로 받으면 타인 ID를 넣어 목록 탈취 가능
    // → requesterId를 receiverId로 강제 사용해 타인 목록 조회가 원천 차단되는지 검증
    @Test
    @DisplayName("목록 조회: requesterId를 receiverId로 사용해 조회한다")
    void getByReceiver_usesRequesterIdAsReceiverId() {
        PageRequest pageable = PageRequest.of(0, 20);
        given(notificationRepositoryPort.findByReceiver(eq(42L), isNull(), eq(pageable)))
                .willReturn(new PageImpl<>(List.of(notification)));

        Page<NotificationDetailResult> result = getNotificationService.getByReceiver(42L, null, pageable);

        assertThat(result.getContent()).hasSize(1);
        verify(notificationRepositoryPort).findByReceiver(42L, null, pageable);
    }

    // [시나리오] isRead 필터가 서비스에서 무시되면 미읽음 알림만 보려는 요청이 전체 목록을 반환함
    // → isRead 값이 repository 호출 시 그대로 전달되는지 검증
    @Test
    @DisplayName("목록 조회: isRead 필터가 파라미터로 전달된다")
    void getByReceiver_passesIsReadFilter() {
        PageRequest pageable = PageRequest.of(0, 20);
        given(notificationRepositoryPort.findByReceiver(eq(42L), eq(false), eq(pageable)))
                .willReturn(new PageImpl<>(List.of(notification)));

        getNotificationService.getByReceiver(42L, false, pageable);

        verify(notificationRepositoryPort).findByReceiver(42L, false, pageable);
    }
}
