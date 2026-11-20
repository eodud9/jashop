package com.jashop.backend.catalog.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.jashop.backend.catalog.domain.CatalogText;
import jakarta.validation.constraints.*;

public record CreateVariantRequest(@NotBlank @Size(max = 100) String sku, @Size(max = 50) String size,
        @Size(max = 80) String color) {
    public CreateVariantRequest {
        sku = CatalogText.normalizeSku(sku);
        size = CatalogText.optional(size);
        color = CatalogText.optional(color);
    }
    @JsonAnySetter public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("허용하지 않는 옵션 생성 필드입니다.");
    }
}
