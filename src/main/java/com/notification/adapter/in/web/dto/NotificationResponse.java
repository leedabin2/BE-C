package com.notification.adapter.in.web.dto;

import com.notification.application.port.in.result.RegisterNotificationResult;
import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationStatus;
import com.notification.domain.NotificationType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "알림 발송 응답")
public record NotificationResponse(

        @Schema(description = "알림 ID", example = "1")
        Long id,

        @Schema(description = "수신자 ID", example = "42")
        Long receiverId,

        @Schema(description = "알림 유형", example = "PAYMENT_CONFIRMED")
        NotificationType notificationType,

        @Schema(description = "발송 채널", example = "EMAIL")
        NotificationChannel channel,

        @Schema(description = "처리 상태", example = "PENDING")
        NotificationStatus status,

        @Schema(description = "멱등성 키")
        String idempotencyKey,

        @Schema(description = "생성 일시")
        LocalDateTime createdAt
) {
    public static NotificationResponse from(RegisterNotificationResult result) {
        return new NotificationResponse(
                result.id(),
                result.receiverId(),
                result.notificationType(),
                result.channel(),
                result.status(),
                result.idempotencyKey(),
                result.createdAt()
        );
    }
}
