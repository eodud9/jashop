package com.jashop.backend.catalog.api.dto;

import java.util.List;
import com.jashop.backend.catalog.domain.Product;
import org.springframework.data.domain.Page;

public record ProductPageResponse(List<ProductSummaryResponse> content, int page, int size, long totalElements, int totalPages) {
    public ProductPageResponse { content = List.copyOf(content); }
    public static ProductPageResponse from(Page<Product> page) {
        return new ProductPageResponse(page.map(ProductSummaryResponse::from).getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
