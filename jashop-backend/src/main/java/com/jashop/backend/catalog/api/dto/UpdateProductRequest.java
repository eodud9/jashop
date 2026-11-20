package com.jashop.backend.catalog.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.jashop.backend.catalog.domain.CatalogText;
import jakarta.validation.constraints.*;

public record UpdateProductRequest(@NotBlank @Size(max = 200) String name, @Size(max = 100) String category,
        @Size(max = 10000) String description, @NotNull Boolean active) {
    public UpdateProductRequest {
        name = CatalogText.strip(name);
        category = CatalogText.optional(category);
        description = CatalogText.optional(description);
    }
    @JsonAnySetter public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("허용하지 않는 상품 수정 필드입니다.");
    }
}
