package com.jashop.backend.workforce.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import com.jashop.backend.workforce.domain.*;

public record AttendanceHistoryResponse(Long id, Long workScheduleId, Long userId, Long storeId, LocalDate workDate,
        Instant checkInAt, Instant recordedCheckInAt, Instant checkOutAt, Instant recordedCheckOutAt,
        AttendanceApprovalStatus approvalStatus, Long approvedBy, Instant approvedAt, Instant closedAt,
        AttendanceClosureType closureType, boolean selfApproved) {
    public static AttendanceHistoryResponse from(Attendance a, WorkSchedule s) {
        return new AttendanceHistoryResponse(a.getId(), a.getWorkScheduleId(), s.getUserId(), s.getStoreId(), s.getWorkDate(),
                a.getCheckInAt(), a.getRecordedCheckInAt(), a.getCheckOutAt(), a.getRecordedCheckOutAt(), a.getApprovalStatus(),
                a.getApprovedBy(), a.getApprovedAt(), a.getClosedAt(), a.getClosureType(), Objects.equals(a.getApprovedBy(), s.getUserId()));
    }
}
