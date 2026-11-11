package com.jashop.backend.auth.api.dto;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.jashop.backend.auth.api.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 72) @MaxUtf8Bytes(72) String password
) {
    public LoginRequest {
        if (email != null) email = email.strip().toLowerCase(Locale.ROOT);
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("허용하지 않는 로그인 필드입니다.");
    }

    @Override
    public String toString() {
        return "LoginRequest[redacted]";
    }
}
