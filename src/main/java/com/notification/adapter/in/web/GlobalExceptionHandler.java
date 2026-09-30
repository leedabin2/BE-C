package com.notification.adapter.in.web;
// PRD: F1 (오류 응답), F3-2 (503 재시도 유도) → docs/prd/F1.md

import com.notification.common.exception.ErrorCode;
import com.notification.common.exception.NotificationException;
import com.notification.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.http.HttpHeaders;
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
     * 503 응답에 붙이는 재시도 대기 시간(초). RFC 9110 Retry-After.
     *
     * 이 값이 계약이다. 저장이 실패하면 알림은 DB에 없고, 우리 스케줄러는
     * "이미 저장된 것"만 회수하므로 <b>이 구간의 복구 책임은 호출자에게 있다.</b>
     * 재시도는 멱등성 키가 있어 안전하다. → docs/DECISIONS.md D-009
     */
    private static final String RETRY_AFTER_SECONDS = "5";

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

    /** 동시 중복 등록 경합 후 기존 알림 조회 실패 시 503. 재시도로 멱등하게 처리 가능. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalStateException(IllegalStateException e) {
        log.error("내부 상태 오류: {}", e.getMessage());
        return retryable(ErrorCode.DB_SAVE_FAILED);
    }

    /**
     * DB 저장 실패. 알림이 DB에 남지 않았으므로 스케줄러가 회수할 수 없다.
     * 503 + Retry-After로 호출자에게 재시도 책임을 명시적으로 넘긴다.
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataAccessException(DataAccessException e) {
        log.error("DB 저장 실패. 호출자 재시도 필요", e);
        return retryable(ErrorCode.DB_SAVE_FAILED);
    }

    /** 재시도 가능한 실패에 Retry-After를 붙인다. 호출자가 언제 다시 보낼지 추측하지 않게 한다. */
    private ResponseEntity<ApiResponse<Void>> retryable(ErrorCode errorCode) {
        return ResponseEntity.status(errorCode.getHttpStatus())
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(ApiResponse.error(errorCode));
    }

    /** 처리되지 않은 모든 예외. 상세 내용은 로그에만 기록하고 클라이언트에는 노출하지 않는다. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.internalServerError()
                .body(ApiResponse.error(ErrorCode.INTERNAL_SERVER_ERROR));
    }
}
