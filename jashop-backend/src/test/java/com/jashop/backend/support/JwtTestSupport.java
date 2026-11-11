package com.jashop.backend.support;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 실제/고정 JWT 키를 저장소에 넣지 않고 테스트 프로세스마다 별도의 키를 생성한다. */
public abstract class JwtTestSupport {
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
}
