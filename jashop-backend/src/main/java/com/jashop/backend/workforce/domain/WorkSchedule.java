package com.jashop.backend.workforce.domain;

import java.time.*;
import java.time.temporal.ChronoUnit;

import com.jashop.backend.global.error.BusinessException;
import com.jashop.backend.global.error.ErrorCode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "work_schedules")
public class WorkSchedule {
    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;
    @Column(name = "store_id", nullable = false)
    private Long storeId;
    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "start_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant startAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "end_at", columnDefinition = "datetime(6)")
    private Instant endAt;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 24)
    private WorkScheduleStatus status;
    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;
    @Column(name = "updated_by", nullable = false)
    private Long updatedBy;
    @Column(name = "cancelled_by")
    private Long cancelledBy;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "cancelled_at", columnDefinition = "datetime(6)")
    private Instant cancelledAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private Instant createdAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "updated_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant updatedAt;

    protected WorkSchedule() {}

    public static WorkSchedule create(Long userId, Long storeId, Instant startAt, Instant endAt, Long actor, Instant now) {
        WorkSchedule schedule = new WorkSchedule();
        schedule.userId = positive(userId);
        schedule.status = WorkScheduleStatus.SCHEDULED;
        schedule.createdBy = positive(actor);
        schedule.createdAt = now.truncatedTo(ChronoUnit.MICROS);
        schedule.change(storeId, startAt, endAt, actor, now);
        return schedule;
    }

    public void change(Long storeId, Instant start, Instant end, Long actor, Instant now) {
        requireScheduled();
        if (start == null) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        Instant first = start.truncatedTo(ChronoUnit.MICROS);
        Instant last = end == null ? null : end.truncatedTo(ChronoUnit.MICROS);
        LocalDate date = first.atZone(SEOUL).toLocalDate();
        // 저장 정밀도와 서울 달력 날짜 기준으로 검증한다. 종료 미정은 허용한다.
        if (date.getYear() < 1000 || date.getYear() > 9999 || first.atOffset(ZoneOffset.UTC).getYear() < 1000
                || (last != null && (!last.isAfter(first) || !last.atZone(SEOUL).toLocalDate().equals(date)))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        this.storeId = positive(storeId);
        this.startAt = first;
        this.endAt = last;
        this.workDate = date;
        this.updatedBy = positive(actor);
        this.updatedAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public void cancel(Long actor, Instant now) {
        requireScheduled();
        this.cancelledBy = positive(actor);
        this.cancelledAt = now.truncatedTo(ChronoUnit.MICROS);
        this.updatedBy = actor;
        this.updatedAt = cancelledAt;
        this.status = WorkScheduleStatus.CANCELLED;
    }

    private void requireScheduled() {
        if (status != WorkScheduleStatus.SCHEDULED) throw new BusinessException(ErrorCode.WORK_SCHEDULE_CONFLICT);
    }

    private static Long positive(Long id) {
        if (id == null || id <= 0) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return id;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getStoreId() { return storeId; }
    public LocalDate getWorkDate() { return workDate; }
    public Instant getStartAt() { return startAt; }
    public Instant getEndAt() { return endAt; }
    public WorkScheduleStatus getStatus() { return status; }
    public Long getCreatedBy() { return createdBy; }
    public Long getUpdatedBy() { return updatedBy; }
    public Long getCancelledBy() { return cancelledBy; }
    public Instant getCancelledAt() { return cancelledAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
