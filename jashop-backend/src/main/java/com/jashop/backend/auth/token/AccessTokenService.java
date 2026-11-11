package com.jashop.backend.auth.token;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AccessTokenService {
    private final JwtEncoder encoder;
    private final JwtSettings settings;
    private final Clock clock;

    public AccessTokenService(JwtEncoder encoder, JwtSettings settings, Clock clock) {
        this.encoder = encoder;
        this.settings = settings;
        this.clock = clock;
    }

    public String issue(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("사용자 ID는 양수여야 합니다.");
        }
        Instant issuedAt = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(settings.issuer()).audience(List.of(settings.audience()))
                .subject(userId.toString()).issuedAt(issuedAt).expiresAt(issuedAt.plusSeconds(settings.accessTtlSeconds()))
                .id(UUID.randomUUID().toString()).claim("token_use", "access").build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims))
                .getTokenValue();
    }

    public long expiresIn() {
        return settings.accessTtlSeconds();
    }
}
