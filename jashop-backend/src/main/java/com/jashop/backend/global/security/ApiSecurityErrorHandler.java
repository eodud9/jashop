package com.jashop.backend.global.security;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.jashop.backend.global.error.ApiError;
import com.jashop.backend.global.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import tools.jackson.databind.json.JsonMapper;

/** 업무 API 필터 단계 오류도 MVC와 같은 응답 형식을 사용한다. */
public class ApiSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final JsonMapper json;
    private final Clock clock;

    public ApiSecurityErrorHandler(JsonMapper json, Clock clock) {
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof JwtValidationException invalid
                    && invalid.getErrors().stream().anyMatch(error -> "token_expired".equals(error.getErrorCode()))) {
                write(response, ErrorCode.TOKEN_EXPIRED);
                return;
            }
        }
        write(response, ErrorCode.UNAUTHENTICATED);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException exception) throws IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean missingIdentity = authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof UserPrincipal);
        write(response, !(exception instanceof CsrfException) && missingIdentity
                ? ErrorCode.UNAUTHENTICATED : ErrorCode.ACCESS_DENIED);
    }

    void write(HttpServletResponse response, ErrorCode code) throws IOException {
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        response.setStatus(code.getStatus().value());
        if (code.getStatus().value() == 401) response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(response.getOutputStream(), new ApiError(code.name(), code.getMessage(),
                Instant.now(clock), traceId, List.of()));
    }
}
