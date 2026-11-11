package com.jashop.backend.support;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 실제/고정 JWT 키를 저장소에 넣지 않고 테스트 프로세스마다 별도의 키를 생성한다. */
public abstract class JwtTestSupport {
    public static final String AUTH_ORIGIN = "http://localhost:5173";
    protected static final String JWT_SECRET;
    static {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        JWT_SECRET = Base64.getEncoder().encodeToString(key);
    }

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry properties) {
        properties.add("jashop.jwt.secret-base64", () -> JWT_SECRET);
    }

    /** 실제 CookieCsrfTokenRepository/XOR 검사에 사용할 쿠키와 헤더를 생성한다. */
    public static org.springframework.test.web.servlet.request.RequestPostProcessor cookieAuthCsrf() {
        return request -> {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            var cookie = new jakarta.servlet.http.Cookie("XSRF-TOKEN", raw);
            var existing = request.getCookies();
            var cookies = new java.util.ArrayList<jakarta.servlet.http.Cookie>();
            if (existing != null) cookies.addAll(java.util.List.of(existing));
            cookies.add(cookie);
            request.setCookies(cookies.toArray(jakarta.servlet.http.Cookie[]::new));
            var handler = new org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler();
            handler.handle(request, new org.springframework.mock.web.MockHttpServletResponse(),
                    () -> new org.springframework.security.web.csrf.DefaultCsrfToken("X-XSRF-TOKEN", "_csrf", raw));
            var csrf = (org.springframework.security.web.csrf.CsrfToken) request.getAttribute(
                    org.springframework.security.web.csrf.CsrfToken.class.getName());
            request.addHeader(csrf.getHeaderName(), csrf.getToken());
            request.addHeader("Origin", AUTH_ORIGIN);
            return request;
        };
    }
}
