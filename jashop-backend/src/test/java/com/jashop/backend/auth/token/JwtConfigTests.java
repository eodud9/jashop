package com.jashop.backend.auth.token;

import java.time.Clock;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import static org.assertj.core.api.Assertions.*;

class JwtConfigTests {
    private final WebApplicationContextRunner context = new WebApplicationContextRunner()
            .withUserConfiguration(JwtConfig.class).withBean(Clock.class, Clock::systemUTC);

    @Test
    void missingSecretFailsClosed() {
        context.run(application -> assertThat(application.getStartupFailure())
                .hasRootCauseInstanceOf(IllegalStateException.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid-secret%%%", "c2hvcnQ="})
    void invalidOrShortSecretFailsWithoutIncludingItsValue(String value) {
        context.withPropertyValues("jashop.jwt.secret-base64=" + value).run(application -> {
            Throwable failure = application.getStartupFailure();
            assertThat(failure).hasRootCauseInstanceOf(IllegalStateException.class);
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                assertThat(cause.getMessage()).doesNotContain(value);
            }
        });
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 60, 86401})
    void invalidTtlIsRejected(long ttl) {
        assertThatThrownBy(() -> new JwtSettings("https://jashop.local", "jashop-api", ttl))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validExternalKeyAndCustomSettingsStartWithoutRemoteKeyDiscovery() {
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        context.withPropertyValues("jashop.jwt.secret-base64=" + Base64.getEncoder().encodeToString(key),
                "jashop.jwt.issuer=https://issuer.example", "jashop.jwt.audience=test-api", "jashop.jwt.access-ttl-seconds=900")
                .run(application -> {
                    assertThat(application).hasNotFailed();
                    assertThat(application.getBean(JwtSettings.class))
                            .isEqualTo(new JwtSettings("https://issuer.example", "test-api", 900));
                    assertThat(application).hasSingleBean(org.springframework.security.oauth2.jwt.JwtDecoder.class);
                });
    }
}
