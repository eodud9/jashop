package com.jashop.backend.store.application;

import java.sql.SQLException;
import java.time.Clock;

import com.jashop.backend.global.error.BusinessException;
import com.jashop.backend.global.error.ErrorCode;
import com.jashop.backend.store.api.dto.CreateStoreRequest;
import com.jashop.backend.store.infrastructure.StoreRepository;
import com.jashop.backend.user.application.UserAuthorizationService;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StoreServiceTests {
    @Mock StoreRepository stores;
    @Mock UserAuthorizationService authorization;
    ValidatorFactory validation;
    StoreService service;

    @BeforeEach
    void setUp() {
        validation = Validation.buildDefaultValidatorFactory();
        service = new StoreService(stores, authorization, validation.getValidator(), Clock.systemUTC());
    }

    @AfterEach
    void closeValidator() {
        validation.close();
    }

    @Test
    void translatesUniqueViolationEvenWhenPrecheckPassed() {
        var cause = new ConstraintViolationException("duplicate", new SQLException("duplicate", "23000", 1062),
                "insert", "stores.uk_stores_code");
        when(stores.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate", cause));
        denied(() -> service.create(1L, new CreateStoreRequest("CODE", "매장")), ErrorCode.DUPLICATE_STORE_CODE);
    }

    @Test
    void doesNotMisclassifyOtherIntegrityViolations() {
        var failure = new DataIntegrityViolationException("other", new ConstraintViolationException(
                "other", new SQLException("other"), "insert", "other_constraint"));
        when(stores.saveAndFlush(any())).thenThrow(failure);
        assertThatThrownBy(() -> service.create(1L, new CreateStoreRequest("CODE", "매장"))).isSameAs(failure);
    }

    @Test
    void serviceAuthorizationPrecedesStoreLookupAndMutation() {
        when(authorization.requireManager(2L)).thenThrow(new BusinessException(ErrorCode.ACCESS_DENIED));
        denied(() -> service.create(2L, new CreateStoreRequest("CODE", "매장")), ErrorCode.ACCESS_DENIED);
        denied(() -> service.rename(2L, 1L, null), ErrorCode.ACCESS_DENIED);
        denied(() -> service.deactivate(2L, 1L), ErrorCode.ACCESS_DENIED);
        verifyNoInteractions(stores);
    }

    @Test
    void directServiceCallsValidateInputsAndJpaOffsetLimit() {
        denied(() -> service.create(1L, new CreateStoreRequest("", "매장")), ErrorCode.VALIDATION_FAILED);
        denied(() -> service.rename(1L, 1L, null), ErrorCode.VALIDATION_FAILED);
        denied(() -> service.get(1L, 0L), ErrorCode.VALIDATION_FAILED);
        denied(() -> service.list(1L, Integer.MAX_VALUE, 100, true), ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(stores);
    }

    private void denied(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ErrorCode code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }
}
