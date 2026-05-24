package com.notification.adapter.in.web.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.notification.adapter.in.web.validation.ValidChannelTarget;
import com.notification.domain.NotificationChannel;
import com.notification.domain.NotificationType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

@Schema(description = "알림 발송 요청")
@ValidChannelTarget
public record NotificationRequest(

        @Schema(description = "수신자 ID (1 이상)", example = "1")
        @NotNull
        @Min(value = 1, message = "receiverId는 1 이상이어야 합니다.")
        Long receiverId,

        @Schema(description = "알림 유형", example = "PAYMENT_CONFIRMED")
        @NotNull
        NotificationType notificationType,

        @Schema(description = "발송 채널", example = "EMAIL")
        @NotNull
        NotificationChannel channel,

        @Schema(description = "채널 대상 (EMAIL=이메일 주소, IN_APP=null)", example = "user@example.com")
        @Size(max = 320, message = "channelTarget은 320자를 초과할 수 없습니다.")
        String channelTarget,

        @Schema(description = "이벤트 ID (멱등성 키 생성에 사용)", example = "order-9999")
        @NotBlank
        String eventId,

        @Schema(description = "참조 도메인 ID", example = "101")
        Long referenceId,

        @Schema(description = "참조 도메인 타입", example = "ENROLLMENT")
        String referenceType,

        @Schema(description = "알림 본문 JSON (문자열 또는 오브젝트 모두 허용)", example = "{\"courseName\":\"Spring Boot 완성\"}")
        @JsonDeserialize(using = JsonStringDeserializer.class)
        @Size(max = 65535, message = "contentData는 65535자를 초과할 수 없습니다.")
        String contentData,

        @Schema(description = "예약 발송 시각 (null이면 즉시)", example = "2025-06-01T09:00:00")
        LocalDateTime scheduledAt
) {}
