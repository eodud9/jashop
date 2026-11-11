package com.jashop.backend.user.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.jashop.backend.user.domain.UserRole;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ApproveUserRequest(@NotNull UserRole role, @NotNull @Positive Long homeStoreId) {
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("허용하지 않는 가입 승인 필드입니다.");
    }
}
