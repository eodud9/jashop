package com.storeflow.backend.store.domain;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "stores", uniqueConstraints = @UniqueConstraint(name = "uk_stores_code", columnNames = "code"))
public class Store {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40, updatable = false)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false)
    private boolean active;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "updated_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant updatedAt;

    protected Store() {
    }

    private Store(String code, String name, Instant now) {
        this.code = requireText(code, 40, "code");
        this.name = requireText(name, 120, "name");
        this.active = true;
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    // 호출 측에서 Instant.now(clock)을 전달한다. 엔티티가 시스템 시계에 직접 의존하지 않는다.
    public static Store create(String code, String name, Instant now) {
        return new Store(code, name, now);
    }

    public void rename(String name, Instant now) {
        String validName = requireText(name, 120, "name");
        Instant changedAt = Objects.requireNonNull(now, "now");
        this.name = validName;
        this.updatedAt = changedAt;
    }

    public void deactivate(Instant now) {
        Instant changedAt = Objects.requireNonNull(now, "now");
        if (active) {
            this.active = false;
            this.updatedAt = changedAt;
        }
    }

    private static String requireText(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > maxLength) {
            throw new IllegalArgumentException(field + " must be non-blank and at most " + maxLength + " characters");
        }
        return value;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
