package com.jashop.backend.auth.token;

import java.time.Clock;
import java.time.Instant;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/** 서명/알고리즘은 Decoder가 검증하고, 이 클래스는 Access 토큰의 필수 claim을 검증한다. */
public class AccessJwtValidator implements OAuth2TokenValidator<Jwt> {
    private final JwtSettings settings;
    private final Clock clock;

    public AccessJwtValidator(JwtSettings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        Instant now = Instant.now(clock);
        try {
            String subject = jwt.getSubject();
            long userId = Long.parseLong(subject);
            if (userId <= 0 || !Long.toString(userId).equals(subject)
                    || !settings.issuer().equals(jwt.getClaimAsString("iss"))
                    || jwt.getAudience() == null || !jwt.getAudience().contains(settings.audience())
                    || !"access".equals(jwt.getClaimAsString("token_use"))
                    || jwt.getIssuedAt() == null || jwt.getExpiresAt() == null
                    || jwt.getIssuedAt().isAfter(now) || !jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                    || (jwt.getNotBefore() != null && jwt.getNotBefore().isAfter(now))) {
                return invalid();
            }
            if (!jwt.getExpiresAt().isAfter(now)) {
                return OAuth2TokenValidatorResult.failure(new OAuth2Error("token_expired", "Access token expired", null));
            }
            return OAuth2TokenValidatorResult.success();
        } catch (RuntimeException exception) {
            return invalid();
        }
    }

    private OAuth2TokenValidatorResult invalid() {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid access token", null));
    }
}
