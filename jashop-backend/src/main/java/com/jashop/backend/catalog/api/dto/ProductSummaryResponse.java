package com.jashop.backend.catalog.api.dto;

import com.jashop.backend.catalog.domain.Product;

public record ProductSummaryResponse(Long id, String name, String category, boolean active) {
    public static ProductSummaryResponse from(Product p) { return new ProductSummaryResponse(p.getId(), p.getName(), p.getCategory(), p.isActive()); }
}
