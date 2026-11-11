package com.jashop.backend.global.security;

import java.io.IOException;
import java.util.List;

import com.jashop.backend.global.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/** 인증 쿠키를 발급/소비하는 POST는 명시적으로 허용된 Origin을 요구한다. */
public class AuthOriginFilter extends OncePerRequestFilter {
    private final List<String> origins;
    private final ApiSecurityErrorHandler errors;

    public AuthOriginFilter(List<String> origins, ApiSecurityErrorHandler errors) {
        this.origins = origins;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("POST".equals(request.getMethod()) && !origins.contains(request.getHeader("Origin"))) {
            errors.write(response, ErrorCode.ACCESS_DENIED);
            return;
        }
        chain.doFilter(request, response);
    }
}
