package com.jashop.backend.auth.token;

public record JwtSettings(String issuer, String audience, long accessTtlSeconds) {
    public JwtSettings {
        if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank()
                || accessTtlSeconds < 1 || accessTtlSeconds > 86400) {
            throw new IllegalStateException("JWT issuer/audience와 1~86400초의 Access 유효기간을 설정해야 합니다.");
        }
    }
}
