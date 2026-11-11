package com.jashop.backend.auth.session.application;

import java.time.Instant;

import com.jashop.backend.auth.api.dto.LoginResponse;
import com.jashop.backend.global.error.ErrorCode;

/** HTTP JSON로 직렬화하지 않는다. Refresh 원문은 Controller가 HttpOnly 쿠키에만 기록한다. */
public record SessionTokens(LoginResponse response, String refreshToken, String familyId, Instant expiresAt, ErrorCode error) {
    public static SessionTokens failure(ErrorCode code) {
        return new SessionTokens(null, null, null, null, code);
    }

    @Override
    public String toString() { return "SessionTokens[redacted]"; }
}
