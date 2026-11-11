package com.jashop.backend.auth.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.jashop.backend.auth.session.application.SessionTokens;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
public class RefreshCookies {
    public static final String NAME = "__Secure-jashop_refresh";
    public static final String PATH = "/api/v1/auth";
    private final Clock clock;

    public RefreshCookies(Clock clock) { this.clock = clock; }

    public void write(HttpServletResponse response, SessionTokens tokens) {
        long remaining = Math.max(0, Duration.between(Instant.now(clock), tokens.expiresAt()).getSeconds());
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(tokens.refreshToken(), remaining).toString());
    }

    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", 0).toString());
    }

    private ResponseCookie cookie(String value, long maxAge) {
        return ResponseCookie.from(NAME, value).path(PATH).httpOnly(true).secure(true)
                .sameSite("Strict").maxAge(maxAge).build();
    }
}
