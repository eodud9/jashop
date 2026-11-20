package com.jashop.backend.catalog.api.dto;

public record ProductDetailResponse(ProductResponse product, VariantPageResponse variants) {}
