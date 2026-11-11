package com.jashop.backend.auth.token;

import java.time.Clock;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class JwtConfig {
    @Bean
    public JwtSettings jwtSettings(Environment environment) {
        return new JwtSettings(environment.getProperty("jashop.jwt.issuer", "https://jashop.local"),
                environment.getProperty("jashop.jwt.audience", "jashop-api"),
                environment.getProperty("jashop.jwt.access-ttl-seconds", Long.class, 900L));
    }

    @Bean
    public SecretKey jwtSecretKey(Environment environment) {
        // ConfigurationProperties 바인딩 오류에 비밀값이 포함되지 않도록 고정 메시지로 검증한다.
        String encoded = environment.getProperty("jashop.jwt.secret-base64");
        byte[] bytes;
        try {
            bytes = encoded == null ? new byte[0] : Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("JWT 서명 키는 32바이트 이상의 Base64 값이어야 합니다.");
        }
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT 서명 키는 32바이트 이상의 Base64 값이어야 합니다.");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSecretKey, JwtSettings settings, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new AccessJwtValidator(settings, clock));
        return decoder;
    }
}
