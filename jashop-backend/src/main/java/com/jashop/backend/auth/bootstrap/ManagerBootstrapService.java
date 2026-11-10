package com.jashop.backend.auth.bootstrap;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import com.jashop.backend.auth.api.dto.SignupRequest;
import com.jashop.backend.global.error.BusinessException;
import com.jashop.backend.global.error.ErrorCode;
import com.jashop.backend.user.domain.User;
import com.jashop.backend.user.domain.UserRole;
import com.jashop.backend.user.infrastructure.UserRepository;
import jakarta.validation.Validator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ManagerBootstrapService {
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final Validator validator;
    private final Clock clock;

    public ManagerBootstrapService(UserRepository users, JdbcTemplate jdbc, PasswordEncoder encoder,
            Validator validator, Clock clock) {
        this.users = users;
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.validator = validator;
        this.clock = clock;
    }

    @Transactional
    public void create(SignupRequest request) {
        // 검증 예외에 비밀번호 invalidValue가 포함되지 않도록 고정 메시지만 사용한다.
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new IllegalArgumentException("초기 관리자 입력값이 올바르지 않습니다.");
        }
        Long completedUser = jdbc.queryForObject(
                "SELECT user_id FROM manager_bootstrap WHERE id = 1 FOR UPDATE", Long.class);
        if (completedUser != null || users.existsByRole(UserRole.MANAGER)) {
            throw new BusinessException(ErrorCode.BOOTSTRAP_NOT_ALLOWED);
        }
        if (users.existsByEmail(request.email())) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }
        Instant now = Instant.now(clock);
        User manager = users.saveAndFlush(User.createInitialManager(request.email(),
                encoder.encode(request.password()), request.name(), now));
        jdbc.update("UPDATE manager_bootstrap SET user_id = ?, completed_at = ? WHERE id = 1",
                manager.getId(), LocalDateTime.ofInstant(now, ZoneOffset.UTC));
    }
}
