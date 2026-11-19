package com.jashop.backend.workforce.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.stream.Collectors;
import com.jashop.backend.global.error.BusinessException;
import com.jashop.backend.global.error.ErrorCode;
import com.jashop.backend.store.infrastructure.StoreRepository;
import com.jashop.backend.user.application.UserAuthorizationService;
import com.jashop.backend.user.domain.UserRole;
import com.jashop.backend.user.infrastructure.UserRepository;
import com.jashop.backend.workforce.api.dto.*;
import com.jashop.backend.workforce.domain.AttendanceApprovalStatus;
import com.jashop.backend.workforce.infrastructure.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AttendanceManagementService {
    private final AttendanceRepository attendances;
    private final WorkScheduleRepository schedules;
    private final UserRepository users;
    private final StoreRepository stores;
    private final UserAuthorizationService authorization;
    private final Clock clock;

    public AttendanceManagementService(AttendanceRepository attendances, WorkScheduleRepository schedules,
            UserRepository users, StoreRepository stores, UserAuthorizationService authorization, Clock clock) {
        this.attendances = attendances;
        this.schedules = schedules;
        this.users = users;
        this.stores = stores;
        this.authorization = authorization;
        this.clock = clock;
    }

    public AttendancePageResponse mine(Long actor, LocalDate from, LocalDate to, AttendanceApprovalStatus status, int page, int size) {
        authorization.requireActive(actor);
        return history(actor, null, from, to, status, page, size);
    }

    public AttendancePageResponse list(Long actor, Long storeId, LocalDate from, LocalDate to, AttendanceApprovalStatus status, int page, int size) {
        var user = authorization.requireActive(actor);
        validId(storeId);
        if (user.getRole() != UserRole.MANAGER) authorization.requireStoreAssistantManager(actor, storeId);
        if (!stores.existsById(storeId)) throw new BusinessException(ErrorCode.STORE_NOT_FOUND);
        return history(null, storeId, from, to, status, page, size);
    }

    private AttendancePageResponse history(Long userId, Long storeId, LocalDate from, LocalDate to,
            AttendanceApprovalStatus status, int page, int size) {
        if (from == null || to == null || from.isAfter(to) || from.getYear() < 1000 || to.getYear() > 9999
                || page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        var result = attendances.findHistory(userId, storeId, from, to, status, PageRequest.of(page, size, Sort.by("checkInAt", "id")));
        // 한 페이지의 일정은 일괄 조회해 행마다 추가 쿼리를 실행하지 않는다.
        var related = schedules.findAllById(result.getContent().stream().map(a -> a.getWorkScheduleId()).toList())
                .stream().collect(Collectors.toMap(s -> s.getId(), s -> s));
        return new AttendancePageResponse(result.getContent().stream()
                .map(a -> AttendanceHistoryResponse.from(a, related.get(a.getWorkScheduleId()))).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional
    public AttendanceResponse approve(Long actor, Long attendanceId, ApproveAttendanceRequest request) {
        if (actor == null || actor <= 0) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        if (request == null || request.recordedCheckOutAt() == null) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        Long scheduleId = attendances.findScheduleId(validId(attendanceId))
                .orElseThrow(() -> new BusinessException(ErrorCode.ATTENDANCE_NOT_FOUND));
        Long owner = schedules.findOwnerId(scheduleId).orElseThrow(() -> new BusinessException(ErrorCode.WORK_SCHEDULE_NOT_FOUND));
        // 서로의 기록을 승인해도 FK 승인자 잠금과 충돌하지 않게 두 User를 항상 ID 오름차순으로 잠근다.
        java.util.stream.Stream.of(actor, owner).distinct().sorted().forEach(userId ->
                users.findByIdForUpdate(userId).orElseThrow(() -> new BusinessException(
                        userId.equals(actor) ? ErrorCode.UNAUTHENTICATED : ErrorCode.USER_NOT_FOUND)));
        var schedule = schedules.findByIdForUpdate(scheduleId).orElseThrow(() -> new BusinessException(ErrorCode.WORK_SCHEDULE_NOT_FOUND));
        authorization.requireStoreAssistantManager(actor, schedule.getStoreId());
        var attendance = attendances.findByIdForUpdate(attendanceId).orElseThrow(() -> new BusinessException(ErrorCode.ATTENDANCE_NOT_FOUND));
        attendance.approve(request.recordedCheckOutAt().toInstant(), actor, Instant.now(clock), schedule.getWorkDate());
        return AttendanceResponse.from(attendances.saveAndFlush(attendance));
    }

    private Long validId(Long id) {
        if (id == null || id <= 0) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return id;
    }
}
