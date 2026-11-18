package com.jashop.backend.workforce.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CheckInRequest(@NotNull @Positive Long workScheduleId) {
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("허용하지 않는 체크인 필드입니다.");
    }
}
