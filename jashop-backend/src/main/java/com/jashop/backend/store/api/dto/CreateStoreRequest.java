package com.jashop.backend.store.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateStoreRequest(
        @NotBlank @Size(max = 40) String code,
        @NotBlank @Size(max = 120) String name
) {
    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("허용하지 않는 매장 생성 필드입니다.");
    }
}
