package com.jashop.backend.store.api.dto;

import java.util.List;

import com.jashop.backend.store.domain.Store;
import org.springframework.data.domain.Page;

public record StorePageResponse(List<StoreResponse> content, int page, int size, long totalElements, int totalPages) {
    public StorePageResponse {
        content = List.copyOf(content);
    }

    public static StorePageResponse from(Page<Store> stores) {
        return new StorePageResponse(stores.getContent().stream().map(StoreResponse::from).toList(),
                stores.getNumber(), stores.getSize(), stores.getTotalElements(), stores.getTotalPages());
    }
}
