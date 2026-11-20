package com.jashop.backend.catalog.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.jashop.backend.catalog.domain.CatalogText;
import jakarta.validation.constraints.*;

public record UpdateVariantRequest(@Size(max = 50) String size, @Size(max = 80) String color, @NotNull Boolean active) {
    public UpdateVariantRequest { size = CatalogText.optional(size); color = CatalogText.optional(color); }
    @JsonAnySetter public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("SKU와 상품 연결은 변경할 수 없습니다.");
    }
}
