package com.notification.adapter.in.web.dto;

import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

@Schema(description = "알림 발송 요청")
public record NotificationRequest(

        @Schema(description = "수신자 ID", example = "42")
        @NotNull
        Long receiverId,

        @Schema(description = "알림 유형", example = "PAYMENT_CONFIRMED")
        @NotNull
        NotificationType notificationType,

        @Schema(description = "발송 채널", example = "EMAIL")
        @NotNull
        NotificationChannel channel,

        @Schema(description = "채널 대상 (EMAIL=이메일 주소, IN_APP=null)", example = "user@example.com")
        String channelTarget,

        @Schema(description = "이벤트 ID (멱등성 키 생성에 사용)", example = "order-9999")
        @NotBlank
        String eventId,

        @Schema(description = "참조 도메인 ID", example = "101")
        Long referenceId,

        @Schema(description = "참조 도메인 타입", example = "ENROLLMENT")
        String referenceType,

        @Schema(description = "알림 본문 JSON", example = "{\"courseName\":\"Spring Boot 완성\"}")
        String contentData,

        @Schema(description = "예약 발송 시각 (null이면 즉시)", example = "2025-06-01T09:00:00")
        LocalDateTime scheduledAt
) {}
