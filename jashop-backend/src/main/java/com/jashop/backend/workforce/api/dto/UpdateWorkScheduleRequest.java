package com.jashop.backend.workforce.api.dto;

import java.time.OffsetDateTime;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** 대상 직원은 변경하지 않는다. 매장/시작/종료 시각을 함께 교체한다. */
public record UpdateWorkScheduleRequest(@NotNull @Positive Long storeId, @NotNull OffsetDateTime startAt,
        OffsetDateTime endAt) {
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("허용하지 않는 일정 필드입니다.");
    }
}
