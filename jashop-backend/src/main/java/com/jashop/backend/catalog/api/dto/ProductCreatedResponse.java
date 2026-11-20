package com.jashop.backend.catalog.api.dto;

public record ProductCreatedResponse(ProductResponse product, ProductVariantResponse firstVariant) {}
