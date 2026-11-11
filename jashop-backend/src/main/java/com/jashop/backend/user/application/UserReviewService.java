package com.jashop.backend.user.application;

import java.time.Clock;
import java.time.Instant;

import com.jashop.backend.global.error.BusinessException;
import com.jashop.backend.global.error.ErrorCode;
import com.jashop.backend.store.domain.Store;
import com.jashop.backend.store.infrastructure.StoreRepository;
import com.jashop.backend.user.api.dto.ApproveUserRequest;
import com.jashop.backend.user.api.dto.PendingUserPageResponse;
import com.jashop.backend.user.api.dto.RejectUserRequest;
import com.jashop.backend.user.api.dto.UserReviewResponse;
import com.jashop.backend.user.domain.User;
import com.jashop.backend.user.domain.UserRole;
import com.jashop.backend.user.domain.UserStatus;
import com.jashop.backend.user.infrastructure.UserRepository;
import jakarta.validation.Validator;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserReviewService {
    private final UserRepository users;
    private final StoreRepository stores;
    private final UserAuthorizationService authorization;
    private final Validator validator;
    private final Clock clock;

    public UserReviewService(UserRepository users, StoreRepository stores, UserAuthorizationService authorization,
            Validator validator, Clock clock) {
        this.users = users;
        this.stores = stores;
        this.authorization = authorization;
        this.validator = validator;
        this.clock = clock;
    }

    public PendingUserPageResponse pending(Long actorId, int page, int size) {
        authorization.requireManager(actorId);
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return PendingUserPageResponse.from(users.findByStatus(UserStatus.PENDING,
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "id"))));
    }

    @Transactional
    public UserReviewResponse approve(Long actorId, Long userId, ApproveUserRequest request) {
        authorization.requireManager(actorId);
        validate(request);
        if (request.role() != UserRole.STAFF && request.role() != UserRole.ASSISTANT_MANAGER) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        // 잠금 순서: 대상 User → Store. 매장 비활성화와 ACTIVE 검증/승인 저장의 경쟁도 직렬화한다.
        User user = lockPending(userId);
        Store store = stores.findByIdForUpdate(request.homeStoreId())
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));
        if (!store.isActive()) {
            throw new BusinessException(ErrorCode.STORE_INACTIVE);
        }
        user.approve(request.role(), store.getId(), actorId, Instant.now(clock));
        return UserReviewResponse.from(user);
    }

    @Transactional
    public UserReviewResponse reject(Long actorId, Long userId, RejectUserRequest request) {
        authorization.requireManager(actorId);
        validate(request);
        User user = lockPending(userId);
        user.reject(request.reason(), Instant.now(clock));
        return UserReviewResponse.from(user);
    }

    private User lockPending(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        User user = users.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        user.requirePending();
        return user;
    }

    private void validate(Object request) {
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
