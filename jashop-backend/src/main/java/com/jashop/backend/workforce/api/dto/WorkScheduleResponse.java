package com.jashop.backend.workforce.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import com.jashop.backend.workforce.domain.WorkSchedule;
import com.jashop.backend.workforce.domain.WorkScheduleStatus;

public record WorkScheduleResponse(Long id, Long userId, Long storeId, LocalDate workDate, Instant startAt,
        Instant endAt, WorkScheduleStatus status, Long createdBy, Long updatedBy, Long cancelledBy,
        Instant cancelledAt, Instant createdAt, Instant updatedAt) {
    public static WorkScheduleResponse from(WorkSchedule s) {
        return new WorkScheduleResponse(s.getId(), s.getUserId(), s.getStoreId(), s.getWorkDate(), s.getStartAt(),
                s.getEndAt(), s.getStatus(), s.getCreatedBy(), s.getUpdatedBy(), s.getCancelledBy(),
                s.getCancelledAt(), s.getCreatedAt(), s.getUpdatedAt());
    }
}
