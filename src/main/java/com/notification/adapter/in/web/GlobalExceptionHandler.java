package com.notification.adapter.in.web;

import com.notification.common.exception.ErrorCode;
import com.notification.common.exception.NotificationException;
import com.notification.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 전역 예외 처리기.
 *
 * 컨트롤러에서 발생한 예외를 잡아 {@link ApiResponse} 형태로 변환한다.
 * 예외가 여기서 처리되므로 서비스가 중단되지 않는다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 비즈니스 규칙 위반 예외 처리.
     * {@link ErrorCode}에 정의된 HTTP 상태와 코드를 그대로 반환한다.
     */
    @ExceptionHandler(NotificationException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotificationException(NotificationException e) {
        ErrorCode errorCode = e.getErrorCode();
        log.warn("NotificationException: code={}, message={}", errorCode.getCode(), e.getMessage());
        return ResponseEntity.status(errorCode.getHttpStatus())
                .body(ApiResponse.error(errorCode, e.getMessage()));
    }

    /** X-User-Id 헤더 형식 오류 및 도메인 규칙 위반 처리. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgumentException(IllegalArgumentException e) {
        log.warn("잘못된 요청: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT, e.getMessage()));
    }

    /** {@code @Valid} 유효성 검사 실패 처리. 모든 필드 오류 메시지를 합쳐서 반환한다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("Validation failed: {}", detail);
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT, detail));
    }

    /** 잘못된 정렬 파라미터 등 API 사용 오류 처리. */
    @ExceptionHandler(InvalidDataAccessApiUsageException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidDataAccessApiUsageException(InvalidDataAccessApiUsageException e) {
        log.warn("잘못된 쿼리 파라미터: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT, "정렬 파라미터가 올바르지 않습니다."));
    }

    /** 동시 중복 등록 경합 후 기존 알림 조회 실패 시 503 응답. 클라이언트 재시도로 멱등하게 처리 가능. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalStateException(IllegalStateException e) {
        log.error("내부 상태 오류: {}", e.getMessage());
        return ResponseEntity.status(ErrorCode.DB_SAVE_FAILED.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.DB_SAVE_FAILED));
    }

    /**
     * DB 저장 실패 처리.
     * 503으로 응답해 호출자가 재시도하도록 유도한다.
     * 재시도 시 멱등성 키가 동일하면 중복 저장되지 않는다.
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataAccessException(DataAccessException e) {
        log.error("DB 저장 실패. 호출자 재시도 필요", e);
        return ResponseEntity.status(ErrorCode.DB_SAVE_FAILED.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.DB_SAVE_FAILED));
    }

    /** 처리되지 않은 모든 예외. 상세 내용은 로그에만 기록하고 클라이언트에는 노출하지 않는다. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.internalServerError()
                .body(ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR));
    }
}
