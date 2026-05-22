package com.notification.common.response;

import com.notification.common.exception.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 모든 API 응답에 사용되는 공통 래퍼.
 *
 * <pre>
 * {
 *   "success": true,
 *   "code": "S000",
 *   "message": "요청이 성공적으로 처리되었습니다.",
 *   "data": { ... }
 * }
 * </pre>
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "공통 응답 구조")
public class ApiResponse<T> {

    @Schema(description = "성공 여부", example = "true")
    private final boolean success;

    @Schema(description = "응답 코드", example = "S000")
    private final String code;

    @Schema(description = "응답 메시지", example = "요청이 성공적으로 처리되었습니다.")
    private final String message;

    @Schema(description = "응답 데이터")
    private final T data;

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, "S000", "요청이 성공적으로 처리되었습니다.", data);
    }

    public static ApiResponse<Void> error(ErrorCode errorCode) {
        return new ApiResponse<>(false, errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static ApiResponse<Void> error(ErrorCode errorCode, String detail) {
        return new ApiResponse<>(false, errorCode.getCode(), detail, null);
    }
}
