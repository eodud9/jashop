package com.jashop.backend.store.api.dto;

import java.time.Instant;

import com.jashop.backend.store.domain.Store;

public record StoreResponse(Long id, String code, String name, boolean active, Instant createdAt, Instant updatedAt) {
    public static StoreResponse from(Store store) {
        return new StoreResponse(store.getId(), store.getCode(), store.getName(), store.isActive(),
                store.getCreatedAt(), store.getUpdatedAt());
    }
}
