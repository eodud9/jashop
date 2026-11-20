package com.jashop.backend.catalog.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import com.jashop.backend.global.error.BusinessException;
import com.jashop.backend.global.error.ErrorCode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "products")
public class Product {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 200) private String name;
    @Column(length = 100) private String category;
    @JdbcTypeCode(SqlTypes.LONGVARCHAR) @Column(columnDefinition = "text") private String description;
    @Column(nullable = false) private boolean active;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private Instant createdAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP) @Column(name = "updated_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant updatedAt;

    protected Product() {}
    public static Product create(String name, String category, String description, Instant now) {
        Product product = new Product();
        product.createdAt = now.truncatedTo(ChronoUnit.MICROS);
        product.change(name, category, description, true, now);
        return product;
    }
    public void change(String name, String category, String description, boolean active, Instant now) {
        String validName = CatalogText.required(name, 200);
        String validCategory = CatalogText.bounded(CatalogText.optional(category), 100);
        String validDescription = CatalogText.bounded(CatalogText.optional(description), 10000);
        this.name = validName;
        this.category = validCategory;
        this.description = validDescription;
        this.active = active;
        this.updatedAt = now.truncatedTo(ChronoUnit.MICROS);
    }
    public void requireActive() {
        if (!active) throw new BusinessException(ErrorCode.PRODUCT_INACTIVE);
    }
    public Long getId() { return id; }
    public String getName() { return name; }
    public String getCategory() { return category; }
    public String getDescription() { return description; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
