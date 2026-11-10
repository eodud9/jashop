package com.jashop.backend.user.application;

import java.util.Optional;

import com.jashop.backend.global.error.BusinessException;
import com.jashop.backend.global.error.ErrorCode;
import com.jashop.backend.user.domain.User;
import com.jashop.backend.user.domain.UserRole;
import com.jashop.backend.user.domain.UserStatus;
import com.jashop.backend.user.infrastructure.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserAuthorizationServiceTests {
    @Mock UserRepository users;
    @Mock User user;
    UserAuthorizationService authorization;

    @BeforeEach
    void setUp() {
        authorization = new UserAuthorizationService(users);
    }

    @Test
    void missingIdentityIsUnauthenticated() {
        denied(() -> authorization.requireActive(null), ErrorCode.UNAUTHENTICATED);
        verifyNoInteractions(users);
        denied(() -> authorization.requireActive(42L), ErrorCode.UNAUTHENTICATED);
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"PENDING", "REJECTED", "INACTIVE"})
    void rejectsNonActiveAccountsEvenWhenManager(UserStatus status) {
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(user.getStatus()).thenReturn(status);
        ErrorCode code = ErrorCode.valueOf("ACCOUNT_" + status.name());
        denied(() -> authorization.requireManager(1L), code);
        denied(() -> authorization.requireStoreAssistantManager(1L, 10L), code);
    }

    @ParameterizedTest
    @EnumSource(UserRole.class)
    void managerCommandsRequireManagerRole(UserRole role) {
        active(role);
        if (role == UserRole.MANAGER) {
            assertThat(authorization.requireManager(1L)).isSameAs(user);
        } else {
            denied(() -> authorization.requireManager(1L), ErrorCode.ACCESS_DENIED);
        }
    }

    @Test
    void assistantManagerCanOnlyManageHomeStoreAndNullNeverMatches() {
        active(UserRole.ASSISTANT_MANAGER);
        when(user.getHomeStoreId()).thenReturn(10L);
        assertThat(authorization.requireStoreAssistantManager(1L, 10L)).isSameAs(user);
        denied(() -> authorization.requireStoreAssistantManager(1L, 20L), ErrorCode.STORE_ACCESS_DENIED);
        denied(() -> authorization.requireStoreAssistantManager(1L, null), ErrorCode.STORE_ACCESS_DENIED);
        when(user.getHomeStoreId()).thenReturn(null);
        denied(() -> authorization.requireStoreAssistantManager(1L, 10L), ErrorCode.STORE_ACCESS_DENIED);
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"STAFF", "MANAGER"})
    void otherRolesCannotBypassAssistantManagerCommands(UserRole role) {
        active(role);
        denied(() -> authorization.requireStoreAssistantManager(1L, 10L), ErrorCode.STORE_ACCESS_DENIED);
    }

    @Test
    void rereadsAccountOnEveryInvocation() {
        active(UserRole.MANAGER);
        authorization.requireManager(1L);
        when(user.getStatus()).thenReturn(UserStatus.INACTIVE);
        denied(() -> authorization.requireManager(1L), ErrorCode.ACCOUNT_INACTIVE);
        verify(users, times(2)).findById(1L);
    }

    private void active(UserRole role) {
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(user.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(user.getRole()).thenReturn(role);
    }

    private void denied(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, ErrorCode code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }
}
