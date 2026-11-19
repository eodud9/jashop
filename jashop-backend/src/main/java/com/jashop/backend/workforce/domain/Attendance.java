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
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "closed_at", columnDefinition = "datetime(6)")
    private Instant closedAt;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(name = "closure_type", length = 32)
    private AttendanceClosureType closureType;

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
        if (isClosed()) throw new BusinessException(ErrorCode.ALREADY_CHECKED_OUT);
        Instant actual = now.truncatedTo(ChronoUnit.MICROS);
        if (actual.isBefore(checkInAt)) throw new BusinessException(ErrorCode.ATTENDANCE_INVALID_STATE);
        checkOutAt = actual;
        closedAt = actual;
        closureType = AttendanceClosureType.EMPLOYEE_CHECKOUT;
    }

    public void approve(Instant recorded, Long actor, Instant now, java.time.LocalDate workDate) {
        if (approvalStatus != AttendanceApprovalStatus.PENDING) {
            throw new BusinessException(ErrorCode.ATTENDANCE_ALREADY_APPROVED);
        }
        if (recorded == null || actor == null || actor <= 0) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        var local = recorded.atZone(WorkSchedule.SEOUL);
        if (local.getMinute() % 30 != 0 || local.getSecond() != 0 || local.getNano() != 0) {
            throw new BusinessException(ErrorCode.INVALID_CHECKOUT_TIME_UNIT);
        }
        Instant approved = now.truncatedTo(ChronoUnit.MICROS);
        if (!local.toLocalDate().equals(workDate) || recorded.isBefore(recordedCheckInAt)
                || recorded.isAfter(approved) || approved.isBefore(checkInAt)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        recordedCheckOutAt = recorded;
        approvedBy = actor;
        approvedAt = approved;
        approvalStatus = AttendanceApprovalStatus.APPROVED;
        if (!isClosed()) {
            closedAt = approved;
            closureType = AttendanceClosureType.ASSISTANT_APPROVAL;
        }
    }

    public boolean isClosed() { return closedAt != null; }

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
    public Instant getClosedAt() { return closedAt; }
    public AttendanceClosureType getClosureType() { return closureType; }
}
