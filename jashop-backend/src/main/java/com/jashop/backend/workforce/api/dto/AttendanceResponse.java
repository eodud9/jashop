package com.jashop.backend.workforce.api.dto;

import java.time.Instant;
import com.jashop.backend.workforce.domain.Attendance;
import com.jashop.backend.workforce.domain.AttendanceApprovalStatus;

public record AttendanceResponse(Long id, Long workScheduleId, Instant checkInAt, Instant recordedCheckInAt,
        Instant checkOutAt, Instant recordedCheckOutAt, AttendanceApprovalStatus approvalStatus,
        Long approvedBy, Instant approvedAt) {
    public static AttendanceResponse from(Attendance a) {
        return new AttendanceResponse(a.getId(), a.getWorkScheduleId(), a.getCheckInAt(), a.getRecordedCheckInAt(),
                a.getCheckOutAt(), a.getRecordedCheckOutAt(), a.getApprovalStatus(), a.getApprovedBy(), a.getApprovedAt());
    }
}
