package com.jashop.backend.workforce.api.dto;

import java.time.OffsetDateTime;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateWorkScheduleRequest(@NotNull @Positive Long userId, @NotNull @Positive Long storeId,
        @NotNull OffsetDateTime startAt, OffsetDateTime endAt) {
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("허용하지 않는 일정 필드입니다.");
    }
}
