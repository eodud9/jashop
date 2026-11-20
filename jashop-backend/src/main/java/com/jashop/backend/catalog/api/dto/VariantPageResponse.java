package com.jashop.backend.catalog.api.dto;

import java.util.List;
import com.jashop.backend.catalog.domain.Product;
import com.jashop.backend.catalog.domain.ProductVariant;
import org.springframework.data.domain.Page;

public record VariantPageResponse(List<ProductVariantResponse> content, int page, int size, long totalElements, int totalPages) {
    public VariantPageResponse { content = List.copyOf(content); }
    public static VariantPageResponse from(Page<ProductVariant> page, Product product) {
        return new VariantPageResponse(page.map(v -> ProductVariantResponse.from(v, product)).getContent(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
