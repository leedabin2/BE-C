package com.notification.adapter.in.web;

import com.notification.adapter.in.web.dto.NotificationDetailResponse;
import com.notification.adapter.in.web.dto.NotificationRequest;
import com.notification.adapter.in.web.dto.NotificationResponse;
import com.notification.adapter.in.web.support.CurrentUserId;
import com.notification.application.port.in.GetNotificationUseCase;
import com.notification.application.port.in.RegisterNotificationUseCase;
import com.notification.application.port.in.command.RegisterNotificationCommand;
import com.notification.application.port.in.result.NotificationDetailResult;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@Tag(name = "Notification", description = "알림 API")
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final RegisterNotificationUseCase registerNotificationUseCase;
    private final GetNotificationUseCase getNotificationUseCase;

    @Operation(summary = "알림 발송 요청", description = "알림을 등록하고 비동기로 발송합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "요청 접수 완료 (중복 요청 시 기존 알림 반환)",
                    content = @Content(schema = @Schema(implementation = NotificationResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 요청")
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

        try {
            RegisterNotificationResult result = registerNotificationUseCase.register(command);
            return ResponseEntity.ok(ApiResponse.success(NotificationResponse.from(result)));
        } catch (DataIntegrityViolationException e) {
            // check-then-insert 경합: 다른 스레드가 동일 idempotency key로 먼저 저장 완료
            // 롤백된 트랜잭션과 별도로 기존 알림을 조회해 정상 응답 반환
            log.warn("동시 중복 등록 감지. 기존 알림 반환.");
            RegisterNotificationResult existing = registerNotificationUseCase.findExistingByCommand(command);
            return ResponseEntity.ok(ApiResponse.success(NotificationResponse.from(existing)));
        }
    }

    @Operation(summary = "알림 상태 조회", description = "알림 ID로 단건 알림의 상태를 조회합니다. 본인 알림만 조회 가능합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(schema = @Schema(implementation = NotificationDetailResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "알림 없음 또는 접근 권한 없음")
    })
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<NotificationDetailResponse>> getById(
            @Parameter(hidden = true) @CurrentUserId Long requesterId,
            @PathVariable Long id
    ) {
        NotificationDetailResult result = getNotificationUseCase.getById(id, requesterId);
        return ResponseEntity.ok(ApiResponse.success(NotificationDetailResponse.from(result)));
    }

    @Operation(summary = "내 알림 목록 조회", description = "인증된 사용자의 알림 목록을 조회합니다. isRead 필터는 IN_APP 채널에만 유효합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 요청")
    })
    @GetMapping
    public ResponseEntity<ApiResponse<Page<NotificationDetailResponse>>> getByReceiver(
            @Parameter(hidden = true) @CurrentUserId Long requesterId,
            @RequestParam(required = false) Boolean isRead,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Page<NotificationDetailResult> results = getNotificationUseCase.getByReceiver(requesterId, isRead, pageable);
        return ResponseEntity.ok(ApiResponse.success(results.map(NotificationDetailResponse::from)));
    }
}
