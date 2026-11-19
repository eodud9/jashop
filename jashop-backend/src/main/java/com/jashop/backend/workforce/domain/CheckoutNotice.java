package com.jashop.backend.workforce.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import com.jashop.backend.global.error.BusinessException;
import com.jashop.backend.global.error.ErrorCode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "checkout_notices")
public class CheckoutNotice {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "work_schedule_id", nullable = false, updatable = false)
    private Long workScheduleId;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "planned_check_out_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private Instant plannedCheckOutAt;
    @Column(name = "sent_by", nullable = false, updatable = false)
    private Long sentBy;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "sent_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private Instant sentAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "acknowledged_at", columnDefinition = "datetime(6)")
    private Instant acknowledgedAt;

    protected CheckoutNotice() {}

    public static CheckoutNotice create(WorkSchedule schedule, Attendance attendance, Long actor, Instant planned, Instant now) {
        if (planned == null || actor == null || actor <= 0) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        Instant sent = now.truncatedTo(ChronoUnit.MICROS);
        var local = planned.atZone(WorkSchedule.SEOUL);
        if (local.getMinute() % 30 != 0 || local.getSecond() != 0 || local.getNano() != 0) {
            throw new BusinessException(ErrorCode.INVALID_NOTICE_TIME_UNIT);
        }
        if (!planned.isAfter(sent) || !local.toLocalDate().equals(schedule.getWorkDate())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (schedule.getStatus() != WorkScheduleStatus.SCHEDULED
                || !sent.atZone(WorkSchedule.SEOUL).toLocalDate().equals(schedule.getWorkDate())
                || attendance == null || !Objects.equals(attendance.getWorkScheduleId(), schedule.getId())
                || attendance.isClosed() || attendance.getCheckInAt().isAfter(sent)) {
            throw new BusinessException(ErrorCode.CHECKOUT_NOTICE_TARGET_INVALID);
        }
        CheckoutNotice notice = new CheckoutNotice();
        notice.workScheduleId = schedule.getId();
        notice.plannedCheckOutAt = planned;
        notice.sentBy = actor;
        notice.sentAt = sent;
        return notice;
    }

    public void acknowledge(Instant now) {
        if (acknowledgedAt != null) return;
        Instant actual = now.truncatedTo(ChronoUnit.MICROS);
        if (actual.isBefore(sentAt)) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        acknowledgedAt = actual;
    }

    public Long getId() { return id; }
    public Long getWorkScheduleId() { return workScheduleId; }
    public Instant getPlannedCheckOutAt() { return plannedCheckOutAt; }
    public Long getSentBy() { return sentBy; }
    public Instant getSentAt() { return sentAt; }
    public Instant getAcknowledgedAt() { return acknowledgedAt; }
}
