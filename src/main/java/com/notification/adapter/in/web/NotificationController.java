package com.notification.adapter.in.web;

import com.notification.adapter.in.web.dto.NotificationRequest;
import com.notification.adapter.in.web.dto.NotificationResponse;
import com.notification.adapter.in.web.support.CurrentUserId;
import com.notification.application.port.in.RegisterNotificationUseCase;
import com.notification.application.port.in.command.RegisterNotificationCommand;
import com.notification.application.port.in.result.RegisterNotificationResult;
import com.notification.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Notification", description = "알림 API")
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final RegisterNotificationUseCase registerNotificationUseCase;

    @Operation(summary = "알림 발송 요청", description = "알림을 등록하고 비동기로 발송합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "요청 접수 완료",
                    content = @Content(schema = @Schema(implementation = NotificationResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 요청"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "중복 요청 (멱등성 키 충돌)")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<NotificationResponse>> register(
            @Parameter(hidden = true) @CurrentUserId Long requesterId,
            @Valid @RequestBody NotificationRequest request
    ) {
        RegisterNotificationCommand command = new RegisterNotificationCommand(
                request.receiverId(),
                request.notificationType(),
                request.channel(),
                request.channelTarget(),
                request.eventId(),
                request.referenceId(),
                request.referenceType(),
                request.contentData(),
                request.scheduledAt()
        );

        RegisterNotificationResult result = registerNotificationUseCase.register(command);
        return ResponseEntity.ok(ApiResponse.success(NotificationResponse.from(result)));
    }
}
