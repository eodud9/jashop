package com.jashop.backend.workforce.api.dto;

import java.util.List;

public record AttendancePageResponse(List<AttendanceHistoryResponse> content, int page, int size, long totalElements, int totalPages) {
    public AttendancePageResponse { content = List.copyOf(content); }
}
