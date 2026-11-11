package com.jashop.backend.user.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.Size;

public record RejectUserRequest(@Size(max = 500) String reason) {
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("허용하지 않는 가입 거절 필드입니다.");
    }

    @Override
    public String toString() {
        return "RejectUserRequest[redacted]";
    }
}
