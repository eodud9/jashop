package com.jashop.backend.auth.session.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import com.jashop.backend.auth.api.dto.AuthenticatedUserResponse;
import com.jashop.backend.auth.api.dto.LoginResponse;
import com.jashop.backend.auth.session.domain.AuthSession;
import com.jashop.backend.auth.session.infrastructure.AuthSessionRepository;
import com.jashop.backend.auth.token.AccessTokenService;
import com.jashop.backend.global.error.ErrorCode;
import com.jashop.backend.user.application.UserAuthorizationService;
import com.jashop.backend.user.domain.User;
import com.jashop.backend.user.infrastructure.UserRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuthSessionService {
    public static final long REFRESH_TTL_SECONDS = 7 * 24 * 60 * 60;
    private final AuthSessionRepository sessions;
    private final UserRepository users;
    private final UserAuthorizationService authorization;
    private final AccessTokenService access;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AuthSessionService(AuthSessionRepository sessions, UserRepository users, UserAuthorizationService authorization,
            AccessTokenService access, Clock clock) {
        this.sessions = sessions;
        this.users = users;
        this.authorization = authorization;
        this.access = access;
        this.clock = clock;
    }

    @Transactional
    public SessionTokens open(Long userId) {
        User user = authorization.requireActive(userId);
        Instant now = Instant.now(clock);
        String raw = randomToken();
        AuthSession root = sessions.saveAndFlush(AuthSession.create(userId, UUID.randomUUID().toString(), hash(raw),
                now, now.plusSeconds(REFRESH_TTL_SECONDS)));
        return tokens(root, user, raw);
    }

    @Transactional
    public SessionTokens refresh(String raw) {
        String hash = validHash(raw);
        if (hash == null) return SessionTokens.failure(ErrorCode.REFRESH_TOKEN_INVALID);
        var rootId = sessions.findRootIdByTokenHash(hash);
        if (rootId.isEmpty()) return SessionTokens.failure(ErrorCode.REFRESH_TOKEN_INVALID);
        // 모든 회전/로그아웃/재사용 처리가 동일한 불변 루트 행을 먼저 잠근다.
        AuthSession root = sessions.findByIdForUpdate(rootId.get()).orElseThrow();
        Instant now = Instant.now(clock);
        if (root.getFamilyRevokedAt() != null) return SessionTokens.failure(ErrorCode.REFRESH_TOKEN_INVALID);
        if (!root.getExpiresAt().isAfter(now)) {
            revoke(root, now);
            return SessionTokens.failure(ErrorCode.REFRESH_TOKEN_EXPIRED);
        }
        AuthSession current = sessions.findByTokenHash(hash).orElseThrow();
        if (current.getRevokedAt() != null) {
            revoke(root, now);
            // 실패를 반환해 트랜잭션을 먼저 커밋한다. 이 안에서 예외를 던지면 폐기 기록도 롤백된다.
            return SessionTokens.failure(ErrorCode.REFRESH_TOKEN_REUSED);
        }
        User user = users.findByIdForUpdate(root.getUserId()).orElseThrow();
        ErrorCode stateError = switch (user.getStatus()) {
            case PENDING -> ErrorCode.ACCOUNT_PENDING;
            case REJECTED -> ErrorCode.ACCOUNT_REJECTED;
            case INACTIVE -> ErrorCode.ACCOUNT_INACTIVE;
            case ACTIVE -> null;
        };
        if (stateError != null) {
            revoke(root, now);
            return SessionTokens.failure(stateError);
        }
        String nextRaw = randomToken();
        AuthSession next = sessions.saveAndFlush(AuthSession.create(user.getId(), root.getFamilyId(), hash(nextRaw),
                now, root.getExpiresAt())); // 최초 로그인 만료일을 연장하지 않는다.
        current.rotateTo(next.getId(), now);
        return tokens(root, user, nextRaw);
    }

    @Transactional
    public void logout(String raw) {
        String hash = validHash(raw);
        if (hash == null) return;
        sessions.findRootIdByTokenHash(hash).ifPresent(rootId -> {
            AuthSession root = sessions.findByIdForUpdate(rootId).orElseThrow();
            revoke(root, Instant.now(clock)); // 회전된 이전 쿠키도 같은 계열을 폐기할 수 있다.
        });
    }

    @Transactional(readOnly = true)
    public void requireActiveFamily(Long userId, String familyId) {
        if (!sessions.isFamilyActive(familyId, userId, Instant.now(clock))) {
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_token", "Login session is not active", null));
        }
    }

    private void revoke(AuthSession root, Instant now) {
        root.revokeFamily(now);
        sessions.revokeTokens(root.getFamilyId(), now);
    }

    private SessionTokens tokens(AuthSession root, User user, String raw) {
        var response = new LoginResponse(access.issue(user.getId(), root.getFamilyId()), "Bearer", access.expiresIn(),
                AuthenticatedUserResponse.from(user));
        return new SessionTokens(response, raw, root.getFamilyId(), root.getExpiresAt(), null);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String validHash(String raw) {
        return raw != null && raw.matches("[A-Za-z0-9_-]{43}") ? hash(raw) : null;
    }

    private String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.");
        }
    }
}
