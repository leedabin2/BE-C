package com.notification.adapter.in.web.dto;

import com.notification.application.port.in.result.NotificationDetailResult;
import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationStatus;
import com.notification.domain.NotificationType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "알림 상세 응답")
public record NotificationDetailResponse(

        @Schema(description = "알림 ID", example = "1")
        Long id,

        @Schema(description = "수신자 ID", example = "42")
        Long receiverId,

        @Schema(description = "알림 유형", example = "PAYMENT_CONFIRMED")
        NotificationType notificationType,

        @Schema(description = "발송 채널", example = "EMAIL")
        NotificationChannel channel,

        @Schema(description = "처리 상태", example = "SENT")
        NotificationStatus status,

        @Schema(description = "멱등성 키")
        String idempotencyKey,

        @Schema(description = "실패 사유 (실패 시에만 존재)")
        String failureReason,

        @Schema(description = "재시도 횟수", example = "0")
        int retryCount,

        @Schema(description = "읽음 여부", example = "false")
        boolean isRead,

        @Schema(description = "예약 발송 시각 (즉시 발송이면 null)")
        LocalDateTime scheduledAt,

        @Schema(description = "생성 일시")
        LocalDateTime createdAt,

        @Schema(description = "최종 수정 일시")
        LocalDateTime updatedAt
) {
    public static NotificationDetailResponse from(NotificationDetailResult result) {
        return new NotificationDetailResponse(
                result.id(),
                result.receiverId(),
                result.notificationType(),
                result.channel(),
                result.status(),
                result.idempotencyKey(),
                result.failureReason(),
                result.retryCount(),
                result.isRead(),
                result.scheduledAt(),
                result.createdAt(),
                result.updatedAt()
        );
    }
}
