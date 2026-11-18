package com.jashop.backend.workforce.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import com.jashop.backend.global.error.BusinessException;
import com.jashop.backend.global.error.ErrorCode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "attendances")
public class Attendance {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "work_schedule_id", nullable = false, updatable = false, unique = true)
    private Long workScheduleId;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "check_in_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private Instant checkInAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "recorded_check_in_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private Instant recordedCheckInAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "check_out_at", columnDefinition = "datetime(6)")
    private Instant checkOutAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "recorded_check_out_at", columnDefinition = "datetime(6)")
    private Instant recordedCheckOutAt;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(name = "approval_status", nullable = false, length = 24)
    private AttendanceApprovalStatus approvalStatus;
    @Column(name = "approved_by") private Long approvedBy;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "approved_at", columnDefinition = "datetime(6)")
    private Instant approvedAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private Instant createdAt;

    protected Attendance() {}

    public static Attendance checkIn(WorkSchedule schedule, Instant now) {
        Instant actual = now.truncatedTo(ChronoUnit.MICROS);
        if (schedule.getStatus() != WorkScheduleStatus.SCHEDULED) {
            throw new BusinessException(ErrorCode.WORK_SCHEDULE_CONFLICT);
        }
        if (!actual.atZone(WorkSchedule.SEOUL).toLocalDate().equals(schedule.getWorkDate())) {
            throw new BusinessException(ErrorCode.ATTENDANCE_CHECK_IN_DATE);
        }
        Attendance attendance = new Attendance();
        attendance.workScheduleId = schedule.getId();
        attendance.checkInAt = actual;
        attendance.recordedCheckInAt = actual.isBefore(schedule.getStartAt()) ? schedule.getStartAt() : actual;
        attendance.createdAt = actual;
        attendance.approvalStatus = AttendanceApprovalStatus.PENDING;
        return attendance;
    }

    public void checkOut(Instant now) {
        if (checkOutAt != null) throw new BusinessException(ErrorCode.ALREADY_CHECKED_OUT);
        Instant actual = now.truncatedTo(ChronoUnit.MICROS);
        if (actual.isBefore(checkInAt)) throw new BusinessException(ErrorCode.ATTENDANCE_INVALID_STATE);
        checkOutAt = actual;
    }

    public Long getId() { return id; }
    public Long getWorkScheduleId() { return workScheduleId; }
    public Instant getCheckInAt() { return checkInAt; }
    public Instant getRecordedCheckInAt() { return recordedCheckInAt; }
    public Instant getCheckOutAt() { return checkOutAt; }
    public Instant getRecordedCheckOutAt() { return recordedCheckOutAt; }
    public AttendanceApprovalStatus getApprovalStatus() { return approvalStatus; }
    public Long getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
