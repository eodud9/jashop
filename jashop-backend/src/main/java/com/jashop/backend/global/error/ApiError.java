package com.jashop.backend.global.error;

import java.time.Instant;
import java.util.List;

public record ApiError(
        String code,
        String message,
        Instant timestamp,
        String traceId,
        List<FieldError> fieldErrors
) {
    public ApiError {
        fieldErrors = List.copyOf(fieldErrors);
    }

    // 입력값(rejectedValue)은 비밀번호 등 민감 정보 노출을 피하기 위해 포함하지 않는다.
    public record FieldError(String field, String message) {
    }
}
