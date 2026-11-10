package com.jashop.backend.store.application;

import java.time.Clock;
import java.time.Instant;

import com.jashop.backend.global.error.BusinessException;
import com.jashop.backend.global.error.ErrorCode;
import com.jashop.backend.store.api.dto.CreateStoreRequest;
import com.jashop.backend.store.api.dto.StorePageResponse;
import com.jashop.backend.store.api.dto.StoreResponse;
import com.jashop.backend.store.api.dto.UpdateStoreRequest;
import com.jashop.backend.store.domain.Store;
import com.jashop.backend.store.infrastructure.StoreRepository;
import com.jashop.backend.user.application.UserAuthorizationService;
import jakarta.validation.Validator;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class StoreService {
    private final StoreRepository stores;
    private final UserAuthorizationService authorization;
    private final Validator validator;
    private final Clock clock;

    public StoreService(StoreRepository stores, UserAuthorizationService authorization, Validator validator, Clock clock) {
        this.stores = stores;
        this.authorization = authorization;
        this.validator = validator;
        this.clock = clock;
    }

    public StorePageResponse list(Long actorId, int page, int size, boolean active) {
        authorization.requireActive(actorId);
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return StorePageResponse.from(stores.findByActive(active,
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "id"))));
    }

    public StoreResponse get(Long actorId, Long id) {
        authorization.requireActive(actorId);
        return StoreResponse.from(stores.findById(validId(id))
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND)));
    }

    @Transactional
    public StoreResponse create(Long actorId, CreateStoreRequest request) {
        authorization.requireManager(actorId);
        validate(request);
        if (stores.existsByCode(request.code())) {
            throw new BusinessException(ErrorCode.DUPLICATE_STORE_CODE);
        }
        try {
            return StoreResponse.from(stores.saveAndFlush(Store.create(request.code(), request.name(), Instant.now(clock))));
        } catch (DataIntegrityViolationException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation) {
                    String name = violation.getConstraintName();
                    if ("uk_stores_code".equals(name) || (name != null && name.endsWith(".uk_stores_code"))) {
                        throw new BusinessException(ErrorCode.DUPLICATE_STORE_CODE);
                    }
                }
            }
            throw exception;
        }
    }

    @Transactional
    public StoreResponse rename(Long actorId, Long id, UpdateStoreRequest request) {
        authorization.requireManager(actorId);
        validate(request);
        Store store = lock(id);
        store.rename(request.name(), Instant.now(clock));
        return StoreResponse.from(store);
    }

    @Transactional
    public StoreResponse deactivate(Long actorId, Long id) {
        authorization.requireManager(actorId);
        Store store = lock(id);
        store.deactivate(Instant.now(clock));
        return StoreResponse.from(store);
    }

    private Store lock(Long id) {
        return stores.findByIdForUpdate(validId(id))
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));
    }

    private Long validId(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return id;
    }

    private void validate(Object request) {
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
