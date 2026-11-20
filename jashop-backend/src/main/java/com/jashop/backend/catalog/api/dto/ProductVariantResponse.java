package com.jashop.backend.catalog.api.dto;

import java.time.Instant;
import com.jashop.backend.catalog.domain.Product;
import com.jashop.backend.catalog.domain.ProductVariant;

public record ProductVariantResponse(Long id, Long productId, String productName, String sku, String size, String color,
        boolean active, boolean effectiveActive, Instant createdAt, Instant updatedAt) {
    public static ProductVariantResponse from(ProductVariant v, Product p) {
        return new ProductVariantResponse(v.getId(), v.getProductId(), p.getName(), v.getSku(), v.getSize(), v.getColor(),
                v.isActive(), v.effectiveActive(p), v.getCreatedAt(), v.getUpdatedAt());
    }
}
