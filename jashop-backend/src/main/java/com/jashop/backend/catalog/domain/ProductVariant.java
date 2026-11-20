package com.jashop.backend.catalog.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import com.jashop.backend.global.error.BusinessException;
import com.jashop.backend.global.error.ErrorCode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "product_variants", uniqueConstraints = @UniqueConstraint(name = "uk_product_variants_sku", columnNames = "sku"))
public class ProductVariant {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "product_id", nullable = false, updatable = false) private Long productId;
    @Column(nullable = false, length = 100, updatable = false) private String sku;
    @Column(length = 50) private String size;
    @Column(length = 80) private String color;
    @Column(nullable = false) private boolean active;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private Instant createdAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "updated_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant updatedAt;

    protected ProductVariant() {}
    public static ProductVariant create(Long productId, String sku, String size, String color, Instant now) {
        if (productId == null || productId <= 0) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        ProductVariant variant = new ProductVariant();
        variant.productId = productId;
        variant.sku = CatalogText.required(CatalogText.normalizeSku(sku), 100);
        variant.createdAt = now.truncatedTo(ChronoUnit.MICROS);
        variant.change(size, color, true, now);
        return variant;
    }
    public void change(String size, String color, boolean active, Instant now) {
        String validSize = CatalogText.bounded(CatalogText.optional(size), 50);
        String validColor = CatalogText.bounded(CatalogText.optional(color), 80);
        this.size = validSize;
        this.color = validColor;
        this.active = active;
        this.updatedAt = now.truncatedTo(ChronoUnit.MICROS);
    }
    public boolean effectiveActive(Product product) { return product.isActive() && active; }
    public Long getId() { return id; }
    public Long getProductId() { return productId; }
    public String getSku() { return sku; }
    public String getSize() { return size; }
    public String getColor() { return color; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
