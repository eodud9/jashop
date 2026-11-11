package com.jashop.backend.auth.api.dto;

public record LoginResponse(String accessToken, String tokenType, long expiresIn, AuthenticatedUserResponse user) {
    @Override
    public String toString() {
        return "LoginResponse[redacted]";
    }
}
