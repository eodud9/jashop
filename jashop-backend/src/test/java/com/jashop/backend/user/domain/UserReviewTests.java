package com.jashop.backend.user.domain;

import java.time.Instant;

import com.jashop.backend.global.error.BusinessException;
import com.jashop.backend.global.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.*;

class UserReviewTests {
    static final Instant CREATED = Instant.parse("2026-11-10T01:00:00Z");
    static final Instant NOW = CREATED.plusSeconds(60);

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"STAFF", "ASSISTANT_MANAGER"})
    void approvalSetsRoleStoreAndServerMetadata(UserRole role) {
        User user = pending();
        user.approve(role, 10L, 1L, NOW);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getRole()).isEqualTo(role);
        assertThat(user.getHomeStoreId()).isEqualTo(10L);
        assertThat(user.getApprovedBy()).isEqualTo(1L);
        assertThat(user.getApprovedAt()).isEqualTo(NOW);
        assertThat(user.getCreatedAt()).isEqualTo(CREATED);
        assertThat(user.getUpdatedAt()).isEqualTo(NOW);
        assertThat(user.getPasswordHash()).isEqualTo("private-hash");
    }

    @Test
    void invalidApprovalDoesNotPartiallyChangePendingUser() {
        User user = pending();
        denied(() -> user.approve(UserRole.MANAGER, 10L, 1L, NOW), ErrorCode.VALIDATION_FAILED);
        denied(() -> user.approve(null, 10L, 1L, NOW), ErrorCode.VALIDATION_FAILED);
        denied(() -> user.approve(UserRole.STAFF, null, 1L, NOW), ErrorCode.VALIDATION_FAILED);
        denied(() -> user.approve(UserRole.STAFF, 0L, 1L, NOW), ErrorCode.VALIDATION_FAILED);
        denied(() -> user.approve(UserRole.STAFF, 10L, null, NOW), ErrorCode.VALIDATION_FAILED);
        assertThatNullPointerException().isThrownBy(() -> user.approve(UserRole.STAFF, 10L, 1L, null));
        assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING);
        assertThat(user.getApprovedBy()).isNull();
        assertThat(user.getHomeStoreId()).isNull();
        assertThat(user.getUpdatedAt()).isEqualTo(CREATED);
    }

    @Test
    void rejectionKeepsApprovalEmptyAndPreservesReasonExactly() {
        User user = pending();
        user.reject("  중복 신청  ", NOW);
        assertThat(user.getStatus()).isEqualTo(UserStatus.REJECTED);
        assertThat(user.getRejectionReason()).isEqualTo("  중복 신청  ");
        assertThat(user.getApprovedBy()).isNull();
        assertThat(user.getApprovedAt()).isNull();
        assertThat(user.getHomeStoreId()).isNull();
        assertThat(user.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void invalidRejectionDoesNotPartiallyChangeUser() {
        User user = pending();
        denied(() -> user.reject("가".repeat(501), NOW), ErrorCode.VALIDATION_FAILED);
        assertThatNullPointerException().isThrownBy(() -> user.reject("사유", null));
        assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING);
        assertThat(user.getRejectionReason()).isNull();
        assertThat(user.getUpdatedAt()).isEqualTo(CREATED);
    }

    @Test
    void approvalAndRejectionAreTerminalForThisWorkflow() {
        User approved = pending();
        approved.approve(UserRole.STAFF, 10L, 1L, NOW);
        denied(() -> approved.approve(UserRole.ASSISTANT_MANAGER, 20L, 2L, NOW), ErrorCode.USER_NOT_PENDING);
        denied(() -> approved.reject("덮어쓰기", NOW), ErrorCode.USER_NOT_PENDING);
        assertThat(approved.getApprovedBy()).isEqualTo(1L);
        assertThat(approved.getHomeStoreId()).isEqualTo(10L);
        User rejected = pending();
        rejected.reject(null, NOW);
        denied(() -> rejected.approve(UserRole.STAFF, 10L, 1L, NOW), ErrorCode.USER_NOT_PENDING);
        denied(() -> rejected.reject("다시 거절", NOW), ErrorCode.USER_NOT_PENDING);
        assertThat(rejected.getRejectionReason()).isNull();
    }

    private User pending() {
        return User.createPending("pending@example.com", "private-hash", "대기 직원", CREATED);
    }

    private void denied(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ErrorCode code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }
}
