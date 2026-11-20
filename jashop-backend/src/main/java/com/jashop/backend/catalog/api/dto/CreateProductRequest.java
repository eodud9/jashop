package com.jashop.backend.catalog.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.jashop.backend.catalog.domain.CatalogText;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record CreateProductRequest(@NotBlank @Size(max = 200) String name, @Size(max = 100) String category,
        @Size(max = 10000) String description, @NotNull @Valid CreateVariantRequest firstVariant) {
    public CreateProductRequest {
        name = CatalogText.strip(name);
        category = CatalogText.optional(category);
        description = CatalogText.optional(description);
    }
    @JsonAnySetter public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("허용하지 않는 상품 생성 필드입니다.");
    }
}
