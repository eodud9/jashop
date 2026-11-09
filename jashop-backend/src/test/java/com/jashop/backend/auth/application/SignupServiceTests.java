package com.jashop.backend.auth.application;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.jashop.backend.auth.api.dto.SignupRequest;
import com.jashop.backend.global.error.BusinessException;
import com.jashop.backend.global.error.ErrorCode;
import com.jashop.backend.user.domain.User;
import com.jashop.backend.user.infrastructure.UserRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SignupServiceTests {
    @Mock UserRepository users;
    @Mock PasswordEncoder encoder;
    private SignupService service;
    private final SignupRequest request = new SignupRequest("직원", " Staff@Example.com ", "plain-password");
    private static final Instant NOW = Instant.parse("2026-11-09T01:02:03.123456Z");

    @BeforeEach
    void setUp() {
        service = new SignupService(users, encoder, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void savesNormalizedEmailEncodedPasswordAndInjectedTime() {
        when(encoder.encode("plain-password")).thenReturn("encoded-password");
        when(users.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.signup(request);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).existsByEmail("staff@example.com");
        verify(users).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("encoded-password");
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(NOW);
        assertThat(response.email()).isEqualTo("staff@example.com");
        assertThat(request.toString()).doesNotContain(request.password(), request.email());
    }

    @Test
    void rejectsKnownDuplicateBeforeHashingOrSaving() {
        when(users.existsByEmail("staff@example.com")).thenReturn(true);
        assertThatThrownBy(() -> service.signup(request)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_EMAIL));
        verifyNoInteractions(encoder);
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void translatesEmailUniqueViolationAfterConcurrentPrechecks() {
        when(encoder.encode(any())).thenReturn("encoded-password");
        var cause = new ConstraintViolationException("duplicate", new SQLException("duplicate", "23000", 1062),
                "insert", "users.uk_users_email");
        when(users.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate", cause));

        assertThatThrownBy(() -> service.signup(request)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_EMAIL));
    }

    @Test
    void doesNotMislabelOtherIntegrityFailuresAsDuplicateEmail() {
        when(encoder.encode(any())).thenReturn("encoded-password");
        var cause = new ConstraintViolationException("invalid", new SQLException("invalid"), "insert", "other_constraint");
        var failure = new DataIntegrityViolationException("invalid", cause);
        when(users.saveAndFlush(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.signup(request)).isSameAs(failure);
    }
}
