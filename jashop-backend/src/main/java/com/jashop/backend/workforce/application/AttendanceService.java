package com.jashop.backend.workforce.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import com.jashop.backend.global.error.BusinessException;
import com.jashop.backend.global.error.ErrorCode;
import com.jashop.backend.store.infrastructure.StoreRepository;
import com.jashop.backend.user.application.UserAuthorizationService;
import com.jashop.backend.user.infrastructure.UserRepository;
import com.jashop.backend.workforce.api.dto.*;
import com.jashop.backend.workforce.domain.Attendance;
import com.jashop.backend.workforce.infrastructure.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional(readOnly = true)
public class AttendanceService {
    private final AttendanceRepository attendances;
    private final WorkScheduleRepository schedules;
    private final UserRepository users;
    private final StoreRepository stores;
    private final CheckoutRequestRepository requests;
    private final UserAuthorizationService authorization;
    private final Clock clock;
    private final JsonMapper json;

    public AttendanceService(AttendanceRepository attendances, WorkScheduleRepository schedules, UserRepository users,
            StoreRepository stores, CheckoutRequestRepository requests, UserAuthorizationService authorization,
            Clock clock, JsonMapper json) {
        this.attendances = attendances;
        this.schedules = schedules;
        this.users = users;
        this.stores = stores;
        this.requests = requests;
        this.authorization = authorization;
        this.clock = clock;
        this.json = json;
    }

    @Transactional
    public AttendanceResponse checkIn(Long actor, CheckInRequest request) {
        lockActiveUser(actor);
        if (request == null) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        var schedule = schedules.findByIdForUpdate(validId(request.workScheduleId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.WORK_SCHEDULE_NOT_FOUND));
        requireOwner(actor, schedule.getUserId());
        var store = stores.findByIdForUpdate(schedule.getStoreId()).orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));
        if (!store.isActive()) throw new BusinessException(ErrorCode.STORE_INACTIVE);
        if (attendances.findByScheduleIdForUpdate(schedule.getId()).isPresent() || !attendances.findActiveForUpdate(actor).isEmpty()) {
            throw new BusinessException(ErrorCode.ALREADY_CHECKED_IN);
        }
        return AttendanceResponse.from(attendances.saveAndFlush(Attendance.checkIn(schedule, Instant.now(clock))));
    }

    @Transactional
    public AttendanceResponse checkOut(Long actor, Long attendanceId, String key) {
        lockActiveUser(actor);
        validId(attendanceId);
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        var completed = requests.findForUpdate(actor, key);
        if (completed.isPresent()) {
            if (!completed.get().attendanceId().equals(attendanceId)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
            }
            return json.readValue(completed.get().responseJson(), AttendanceResponse.class);
        }

        Long scheduleId = attendances.findScheduleId(attendanceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ATTENDANCE_NOT_FOUND));
        var schedule = schedules.findByIdForUpdate(scheduleId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WORK_SCHEDULE_NOT_FOUND));
        requireOwner(actor, schedule.getUserId());
        var attendance = attendances.findByIdForUpdate(attendanceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ATTENDANCE_NOT_FOUND));
        if (!attendance.isClosed()) {
            var active = attendances.findActiveForUpdate(actor);
            if (active.size() != 1 || !active.getFirst().getId().equals(attendanceId)) {
                throw new BusinessException(ErrorCode.ATTENDANCE_INVALID_STATE);
            }
            // 지원 배정 기능은 아직 없다. 해당 기능 도입 시 같은 User 잠금 안에서 검증한다.
            attendance.checkOut(Instant.now(clock));
            attendances.flush();
        }
        // 다른 키/키 기록 삭제 후에도 지정된 과거 기록만 반환한다. 현재 근무로 대체하지 않는다.
        var response = AttendanceResponse.from(attendance);
        requests.save(actor, key, attendanceId, json.writeValueAsString(response), Instant.now(clock));
        return response;
    }

    public AttendanceResponse current(Long actor) {
        authorization.requireActive(actor);
        var active = attendances.findActive(actor);
        if (active.size() > 1) throw new BusinessException(ErrorCode.ATTENDANCE_INVALID_STATE);
        return active.isEmpty() ? null : AttendanceResponse.from(active.getFirst());
    }

    private void lockActiveUser(Long actor) {
        if (actor == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        users.findByIdForUpdate(actor).orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        authorization.requireActive(actor);
    }

    private void requireOwner(Long actor, Long owner) {
        if (!Objects.equals(actor, owner)) throw new BusinessException(ErrorCode.ACCESS_DENIED);
    }

    private Long validId(Long id) {
        if (id == null || id <= 0) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return id;
    }
}
