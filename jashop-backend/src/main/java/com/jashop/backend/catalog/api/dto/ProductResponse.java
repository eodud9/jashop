package com.jashop.backend.catalog.api.dto;

import java.time.Instant;
import com.jashop.backend.catalog.domain.Product;

public record ProductResponse(Long id, String name, String category, String description, boolean active, Instant createdAt, Instant updatedAt) {
    public static ProductResponse from(Product p) {
        return new ProductResponse(p.getId(), p.getName(), p.getCategory(), p.getDescription(), p.isActive(), p.getCreatedAt(), p.getUpdatedAt());
    }
}
