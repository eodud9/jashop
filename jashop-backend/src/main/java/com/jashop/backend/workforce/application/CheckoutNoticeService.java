package com.jashop.backend.workforce.application;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import com.jashop.backend.global.error.BusinessException;
import com.jashop.backend.global.error.ErrorCode;
import com.jashop.backend.store.infrastructure.StoreRepository;
import com.jashop.backend.user.application.UserAuthorizationService;
import com.jashop.backend.user.domain.UserRole;
import com.jashop.backend.user.infrastructure.UserRepository;
import com.jashop.backend.workforce.api.dto.*;
import com.jashop.backend.workforce.domain.*;
import com.jashop.backend.workforce.infrastructure.*;
import jakarta.validation.Validator;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CheckoutNoticeService {
    private final CheckoutNoticeRepository notices;
    private final WorkScheduleRepository schedules;
    private final AttendanceRepository attendances;
    private final UserRepository users;
    private final StoreRepository stores;
    private final UserAuthorizationService authorization;
    private final Validator validator;
    private final Clock clock;

    public CheckoutNoticeService(CheckoutNoticeRepository notices, WorkScheduleRepository schedules,
            AttendanceRepository attendances, UserRepository users, StoreRepository stores,
            UserAuthorizationService authorization, Validator validator, Clock clock) {
        this.notices = notices;
        this.schedules = schedules;
        this.attendances = attendances;
        this.users = users;
        this.stores = stores;
        this.authorization = authorization;
        this.validator = validator;
        this.clock = clock;
    }

    @Transactional
    public CheckoutNoticeBatchResponse create(Long actor, CreateCheckoutNoticesRequest request) {
        if (actor == null || actor <= 0) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        if (request == null || !validator.validate(request).isEmpty()
                || new HashSet<>(request.workScheduleIds()).size() != request.workScheduleIds().size()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        var ids = request.workScheduleIds().stream().sorted().toList();
        var participants = new TreeSet<Long>();
        participants.add(actor);
        // 소유자 ID만 읽어 잠금 대기 전의 오래된 엔티티를 재사용하지 않는다.
        for (Long id : ids) participants.add(schedules.findOwnerId(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.WORK_SCHEDULE_NOT_FOUND)));
        for (Long id : participants) users.findByIdForUpdate(id).orElseThrow(() -> new BusinessException(
                id.equals(actor) ? ErrorCode.UNAUTHENTICATED : ErrorCode.USER_NOT_FOUND));
        authorization.requireActive(actor);
        Map<Long, WorkSchedule> locked = new HashMap<>();
        for (Long id : ids) {
            var schedule = schedules.findByIdForUpdate(id).orElseThrow(() -> new BusinessException(ErrorCode.WORK_SCHEDULE_NOT_FOUND));
            authorization.requireStoreAssistantManager(actor, schedule.getStoreId());
            locked.put(id, schedule);
        }
        Map<Long, Attendance> active = new HashMap<>();
        for (Long id : ids) active.put(id, attendances.findByScheduleIdForUpdate(id).orElse(null));
        // 모든 잠금 획득 후 현재 시각으로 검증한다. 대기 중 지난 안내 시각/자정도 반영한다.
        Instant now = Instant.now(clock);
        var created = request.workScheduleIds().stream().map(id -> CheckoutNotice.create(locked.get(id), active.get(id),
                actor, request.plannedCheckOutAt().toInstant(), now)).toList();
        notices.saveAllAndFlush(created);
        return new CheckoutNoticeBatchResponse(created.stream()
                .map(n -> CheckoutNoticeResponse.from(n, locked.get(n.getWorkScheduleId()))).toList());
    }

    @Transactional
    public CheckoutNoticeResponse acknowledge(Long actor, Long id) {
        if (actor == null) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        users.findByIdForUpdate(actor).orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        authorization.requireActive(actor);
        Long scheduleId = notices.findScheduleId(validId(id)).orElseThrow(() -> new BusinessException(ErrorCode.CHECKOUT_NOTICE_NOT_FOUND));
        var schedule = schedules.findByIdForUpdate(scheduleId).orElseThrow(() -> new BusinessException(ErrorCode.WORK_SCHEDULE_NOT_FOUND));
        if (!Objects.equals(actor, schedule.getUserId())) throw new BusinessException(ErrorCode.ACCESS_DENIED);
        var notice = notices.findByIdForUpdate(id).orElseThrow(() -> new BusinessException(ErrorCode.CHECKOUT_NOTICE_NOT_FOUND));
        notice.acknowledge(Instant.now(clock));
        notices.flush();
        return CheckoutNoticeResponse.from(notice, schedule);
    }

    public CheckoutNoticePageResponse mine(Long actor, LocalDate fromDate, LocalDate toDate, int page, int size) {
        authorization.requireActive(actor);
        return history(actor, null, fromDate, toDate, page, size);
    }

    public CheckoutNoticePageResponse list(Long actor, Long storeId, LocalDate fromDate, LocalDate toDate, int page, int size) {
        var user = authorization.requireActive(actor);
        validId(storeId);
        if (user.getRole() != UserRole.MANAGER) authorization.requireStoreAssistantManager(actor, storeId);
        if (!stores.existsById(storeId)) throw new BusinessException(ErrorCode.STORE_NOT_FOUND);
        return history(null, storeId, fromDate, toDate, page, size);
    }

    private CheckoutNoticePageResponse history(Long userId, Long storeId, LocalDate from, LocalDate to, int page, int size) {
        if (from == null || to == null || from.isAfter(to) || from.getYear() < 1000 || to.getYear() >= 9999
                || page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        var result = notices.findHistory(userId, storeId, from.atStartOfDay(WorkSchedule.SEOUL).toInstant(),
                to.plusDays(1).atStartOfDay(WorkSchedule.SEOUL).toInstant(),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "sentAt", "id")));
        var related = schedules.findAllById(result.getContent().stream().map(n -> n.getWorkScheduleId()).toList())
                .stream().collect(Collectors.toMap(s -> s.getId(), s -> s));
        return new CheckoutNoticePageResponse(result.getContent().stream()
                .map(n -> CheckoutNoticeResponse.from(n, related.get(n.getWorkScheduleId()))).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    private Long validId(Long id) {
        if (id == null || id <= 0) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return id;
    }
}
