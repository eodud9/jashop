package com.jashop.backend.auth.api.dto;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.jashop.backend.auth.api.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank(message = "이름은 필수입니다.")
        @Size(max = 100, message = "이름은 100자 이하여야 합니다.")
        String name,
        @NotBlank(message = "이메일은 필수입니다.")
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        @Size(max = 254, message = "이메일은 254자 이하여야 합니다.")
        String email,
        @NotBlank(message = "비밀번호는 필수입니다.")
        @Size(min = 8, max = 72, message = "비밀번호는 8~72자여야 합니다.")
        @MaxUtf8Bytes(72)
        String password
) {
    public SignupRequest {
        // 형식 검증 전에 정규화한다. null/빈 값은 Bean Validation에서 처리한다.
        if (email != null) {
            email = email.strip().toLowerCase(Locale.ROOT);
        }
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("허용하지 않는 회원가입 필드입니다.");
    }

    @Override
    public String toString() {
        return "SignupRequest[redacted]";
    }
}
