package com.jashop.backend.workforce.api.dto;

import java.time.OffsetDateTime;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;

public record ApproveAttendanceRequest(@NotNull OffsetDateTime recordedCheckOutAt) {
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("허용하지 않는 승인 필드입니다.");
    }
}
