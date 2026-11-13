package com.jashop.backend.workforce.api.dto;

import java.util.List;
import com.jashop.backend.workforce.domain.WorkSchedule;
import org.springframework.data.domain.Page;

public record WorkSchedulePageResponse(List<WorkScheduleResponse> content, int page, int size,
        long totalElements, int totalPages) {
    public WorkSchedulePageResponse { content = List.copyOf(content); }
    public static WorkSchedulePageResponse from(Page<WorkSchedule> schedules) {
        return new WorkSchedulePageResponse(schedules.map(WorkScheduleResponse::from).getContent(),
                schedules.getNumber(), schedules.getSize(), schedules.getTotalElements(), schedules.getTotalPages());
    }
}
