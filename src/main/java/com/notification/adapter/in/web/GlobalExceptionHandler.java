package com.notification.adapter.in.web;

import com.notification.common.exception.ErrorCode;
import com.notification.common.exception.NotificationException;
import com.notification.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
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

    /**
     * 동시 중복 등록 경합 처리.
     * check-then-act 사이에 동시 요청이 성공한 경우 DB unique constraint가 잡아낸다.
     * 이미 같은 요청이 처리됐으므로 호출자에게 성공으로 응답한다.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolationException(DataIntegrityViolationException e) {
        log.warn("동시 중복 등록 감지 (unique constraint). 성공으로 처리", e);
        return ResponseEntity.ok(ApiResponse.success(null));
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
