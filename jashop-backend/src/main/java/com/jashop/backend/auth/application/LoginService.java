package com.jashop.backend.auth.application;

import com.jashop.backend.auth.api.dto.AuthenticatedUserResponse;
import com.jashop.backend.auth.api.dto.LoginRequest;
import com.jashop.backend.auth.api.dto.LoginResponse;
import com.jashop.backend.auth.token.AccessTokenService;
import com.jashop.backend.global.error.BusinessException;
import com.jashop.backend.global.error.ErrorCode;
import com.jashop.backend.user.application.UserAuthorizationService;
import com.jashop.backend.user.infrastructure.UserRepository;
import jakarta.validation.Validator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class LoginService {
    private final UserRepository users;
    private final UserAuthorizationService authorization;
    private final PasswordEncoder encoder;
    private final AccessTokenService tokens;
    private final Validator validator;
    private final String dummyHash;

    public LoginService(UserRepository users, UserAuthorizationService authorization, PasswordEncoder encoder,
            AccessTokenService tokens, Validator validator) {
        this.users = users;
        this.authorization = authorization;
        this.encoder = encoder;
        this.tokens = tokens;
        this.validator = validator;
        // 없는 이메일도 BCrypt 검증을 수행한다. 이 값은 로그인 가능한 사용자 비밀번호가 아니다.
        this.dummyHash = encoder.encode("dummy-login-comparison");
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        var user = users.findByEmail(request.email()).orElse(null);
        boolean matches;
        try {
            matches = encoder.matches(request.password(), user == null ? dummyHash : user.getPasswordHash());
        } catch (IllegalArgumentException exception) {
            matches = false;
        }
        if (!matches || user == null) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        user = authorization.requireActive(user.getId());
        return new LoginResponse(tokens.issue(user.getId()), "Bearer", tokens.expiresIn(), AuthenticatedUserResponse.from(user));
    }
}
