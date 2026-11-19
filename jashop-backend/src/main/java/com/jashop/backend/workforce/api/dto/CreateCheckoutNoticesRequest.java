package com.jashop.backend.workforce.api.dto;

import java.time.OffsetDateTime;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;

public record CreateCheckoutNoticesRequest(@NotEmpty @Size(max = 100) List<@NotNull @Positive Long> workScheduleIds,
        @NotNull OffsetDateTime plannedCheckOutAt) {
    public CreateCheckoutNoticesRequest {
        if (workScheduleIds != null) workScheduleIds = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(workScheduleIds));
    }
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("허용하지 않는 퇴근 안내 필드입니다.");
    }
}
