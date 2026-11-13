package com.jashop.backend.workforce.application;

import java.time.*;
import java.util.Objects;

import com.jashop.backend.global.error.BusinessException;
import com.jashop.backend.global.error.ErrorCode;
import com.jashop.backend.store.infrastructure.StoreRepository;
import com.jashop.backend.user.application.UserAuthorizationService;
import com.jashop.backend.user.domain.User;
import com.jashop.backend.user.domain.UserRole;
import com.jashop.backend.user.infrastructure.UserRepository;
import com.jashop.backend.workforce.api.dto.*;
import com.jashop.backend.workforce.domain.WorkSchedule;
import com.jashop.backend.workforce.infrastructure.WorkScheduleRepository;
import jakarta.validation.Validator;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class WorkScheduleService {
    private final WorkScheduleRepository schedules;
    private final UserRepository users;
    private final StoreRepository stores;
    private final UserAuthorizationService authorization;
    private final Validator validator;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public WorkScheduleService(WorkScheduleRepository schedules, UserRepository users, StoreRepository stores,
            UserAuthorizationService authorization, Validator validator, JdbcTemplate jdbc, Clock clock) {
        this.schedules = schedules;
        this.users = users;
        this.stores = stores;
        this.authorization = authorization;
        this.validator = validator;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public WorkSchedulePageResponse mine(Long actor, LocalDate fromDate, LocalDate toDate, int page, int size) {
        authorization.requireActive(actor);
        var range = range(fromDate, toDate);
        return WorkSchedulePageResponse.from(schedules.findByUserIdAndStartAtGreaterThanEqualAndStartAtLessThan(
                actor, range[0], range[1], page(page, size)));
    }

    public WorkSchedulePageResponse list(Long actor, Long storeId, LocalDate fromDate, LocalDate toDate, int page, int size) {
        requireStoreReader(actor, validId(storeId));
        if (!stores.existsById(storeId)) throw new BusinessException(ErrorCode.STORE_NOT_FOUND);
        var range = range(fromDate, toDate);
        return WorkSchedulePageResponse.from(schedules.findByStoreIdAndStartAtGreaterThanEqualAndStartAtLessThan(
                storeId, range[0], range[1], page(page, size)));
    }

    public WorkScheduleResponse get(Long actor, Long id) {
        User user = authorization.requireActive(actor);
        var schedule = schedules.findById(validId(id)).orElseThrow(() -> new BusinessException(ErrorCode.WORK_SCHEDULE_NOT_FOUND));
        if (!Objects.equals(user.getId(), schedule.getUserId())) requireStoreReader(actor, schedule.getStoreId());
        return WorkScheduleResponse.from(schedule);
    }

    @Transactional
    public WorkScheduleResponse create(Long actor, CreateWorkScheduleRequest request) {
        authorization.requireManager(actor);
        validate(request);
        lockEmployee(request.userId());
        lockActiveStore(request.storeId());
        var schedule = WorkSchedule.create(request.userId(), request.storeId(), request.startAt().toInstant(),
                instant(request.endAt()), actor, Instant.now(clock));
        return save(schedule);
    }

    @Transactional
    public WorkScheduleResponse update(Long actor, Long id, UpdateWorkScheduleRequest request) {
        authorization.requireManager(actor);
        validate(request);
        WorkSchedule schedule = lockEditable(id);
        lockActiveStore(request.storeId());
        schedule.change(request.storeId(), request.startAt().toInstant(), instant(request.endAt()), actor, Instant.now(clock));
        return save(schedule);
    }

    @Transactional
    public WorkScheduleResponse cancel(Long actor, Long id) {
        authorization.requireManager(actor);
        WorkSchedule schedule = lockEditable(id);
        schedule.cancel(actor, Instant.now(clock));
        return save(schedule);
    }

    private WorkSchedule lockEditable(Long id) {
        Long owner = schedules.findOwnerId(validId(id)).orElseThrow(() -> new BusinessException(ErrorCode.WORK_SCHEDULE_NOT_FOUND));
        // 일정 변경/취소/후속 체크인은 모두 User → WorkSchedule → Store 순서로 잠근다.
        users.findByIdForUpdate(owner).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        WorkSchedule schedule = schedules.findByIdForUpdate(id).orElseThrow(() -> new BusinessException(ErrorCode.WORK_SCHEDULE_NOT_FOUND));
        // REPEATABLE READ에서도 잠금 대기 후 최신 체크인 기록을 읽는다. 퇴근 후에도 수정 불가.
        if (!jdbc.queryForList("SELECT id FROM attendances WHERE work_schedule_id = ? FOR UPDATE", Long.class, id).isEmpty()) {
            throw new BusinessException(ErrorCode.WORK_SCHEDULE_CHECKED_IN);
        }
        return schedule;
    }

    private void lockEmployee(Long id) {
        users.findByIdForUpdate(validId(id)).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        authorization.requireActive(id);
    }

    private void lockActiveStore(Long id) {
        var store = stores.findByIdForUpdate(validId(id)).orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));
        if (!store.isActive()) throw new BusinessException(ErrorCode.STORE_INACTIVE);
    }

    private void requireStoreReader(Long actor, Long storeId) {
        if (authorization.requireActive(actor).getRole() != UserRole.MANAGER) {
            authorization.requireStoreAssistantManager(actor, storeId);
        }
    }

    private WorkScheduleResponse save(WorkSchedule schedule) {
        try {
            return WorkScheduleResponse.from(schedules.saveAndFlush(schedule));
        } catch (DataIntegrityViolationException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null
                        && (violation.getConstraintName().equals("uk_work_schedules_user_active_date")
                        || violation.getConstraintName().endsWith(".uk_work_schedules_user_active_date"))) {
                    throw new BusinessException(ErrorCode.WORK_SCHEDULE_CONFLICT);
                }
            }
            throw exception;
        }
    }

    private Instant[] range(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to) || from.getYear() < 1000 || to.getYear() >= 9999) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return new Instant[] { from.atStartOfDay(WorkSchedule.SEOUL).toInstant(), to.plusDays(1).atStartOfDay(WorkSchedule.SEOUL).toInstant() };
    }

    private PageRequest page(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return PageRequest.of(page, size, Sort.by("startAt", "id"));
    }

    private void validate(Object request) {
        if (request == null || !validator.validate(request).isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    private Long validId(Long id) {
        if (id == null || id <= 0) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return id;
    }

    private Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }
}
