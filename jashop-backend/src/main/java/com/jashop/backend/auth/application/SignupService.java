package com.jashop.backend.auth.application;

import java.time.Clock;
import java.time.Instant;

import com.jashop.backend.auth.api.dto.SignupRequest;
import com.jashop.backend.auth.api.dto.SignupResponse;
import com.jashop.backend.global.error.BusinessException;
import com.jashop.backend.global.error.ErrorCode;
import com.jashop.backend.user.domain.User;
import com.jashop.backend.user.infrastructure.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
public class SignupService {
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public SignupService(UserRepository users, PasswordEncoder passwordEncoder, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    @Transactional
    public SignupResponse signup(@NotNull @Valid SignupRequest request) {
        String email = User.normalizeEmail(request.email());
        if (users.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }
        User user = User.createPending(email, passwordEncoder.encode(request.password()),
                request.name(), Instant.now(clock));
        try {
            // 트랜잭션 종료 전 INSERT를 실행해 동시 중복도 이 경계에서 변환한다.
            return SignupResponse.from(users.saveAndFlush(user));
        } catch (DataIntegrityViolationException exception) {
            if (isDuplicateEmail(exception)) {
                throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
            }
            throw exception;
        }
    }

    private boolean isDuplicateEmail(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                String name = violation.getConstraintName();
                return "uk_users_email".equals(name)
                        || (name != null && name.endsWith(".uk_users_email"));
            }
        }
        return false;
    }
}
