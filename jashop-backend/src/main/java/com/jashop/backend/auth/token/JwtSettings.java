package com.jashop.backend.auth.token;

public record JwtSettings(String issuer, String audience, long accessTtlSeconds) {
    public JwtSettings {
        if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank()
                || accessTtlSeconds != 900) {
            throw new IllegalStateException("JWT issuer/audience와 확정된 Access 유효기간 900초를 설정해야 합니다.");
        }
    }
}
