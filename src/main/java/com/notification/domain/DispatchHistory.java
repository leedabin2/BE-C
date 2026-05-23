package com.notification.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 알림 발송 시도 이력.
 *
 * dispatch() 호출 한 번 = 레코드 한 건.
 * notification.retryCount와 별개로, 각 시도의 결과를 독립적으로 보존한다.
 * notification의 failureReason이 '마지막 실패 사유'라면,
 * 이 테이블은 '모든 시도의 전체 이력'이다.
 */
@Entity
@Table(name = "dispatch_history",
        indexes = @Index(name = "idx_dispatch_notification", columnList = "notification_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DispatchHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long notificationId;

    /** 1부터 시작. retryCount + 1 기준. */
    @Column(nullable = false)
    private int attemptNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DispatchStatus status;

    /** 실패 사유. ChannelFailureCode 이름 저장. 성공 시 NULL. */
    @Column(length = 100)
    private String errorMessage;

    @Column(nullable = false)
    private LocalDateTime dispatchedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private DispatchHistory(Long notificationId, int attemptNumber,
                            DispatchStatus status, String errorMessage,
                            LocalDateTime dispatchedAt) {
        this.notificationId = notificationId;
        this.attemptNumber = attemptNumber;
        this.status = status;
        this.errorMessage = errorMessage;
        this.dispatchedAt = dispatchedAt;
        this.createdAt = LocalDateTime.now();
    }

    public static DispatchHistory success(Long notificationId, int attemptNumber) {
        return DispatchHistory.builder()
                .notificationId(notificationId)
                .attemptNumber(attemptNumber)
                .status(DispatchStatus.SENT)
                .dispatchedAt(LocalDateTime.now())
                .build();
    }

    public static DispatchHistory failure(Long notificationId, int attemptNumber, String errorMessage) {
        return DispatchHistory.builder()
                .notificationId(notificationId)
                .attemptNumber(attemptNumber)
                .status(DispatchStatus.FAILED)
                .errorMessage(errorMessage)
                .dispatchedAt(LocalDateTime.now())
                .build();
    }
}
