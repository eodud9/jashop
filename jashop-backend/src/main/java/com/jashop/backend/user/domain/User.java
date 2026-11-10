package com.jashop.backend.user.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(name = "uk_users_email", columnNames = "email"))
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 254, updatable = false)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 24)
    private UserStatus status;

    @Column(name = "home_store_id")
    private Long homeStoreId;

    @Column(name = "approved_by")
    private Long approvedBy;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "approved_at", columnDefinition = "datetime(6)")
    private Instant approvedAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private Instant createdAt;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "updated_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant updatedAt;

    protected User() {
    }

    private User(String email, String passwordHash, String name, Instant now) {
        this.email = normalizeEmail(email);
        this.passwordHash = requireText(passwordHash, 255, "passwordHash");
        this.name = requireText(name, 100, "name");
        this.role = UserRole.STAFF;
        this.status = UserStatus.PENDING;
        this.createdAt = Objects.requireNonNull(now, "now");
        this.updatedAt = now;
    }

    // passwordHash는 호출 서비스에서 안전한 단방향 해시로 만들어 전달한다.
    // 역할·소속·승인 정보는 가입자가 지정할 수 없다.
    public static User createPending(String email, String passwordHash, String name, Instant now) {
        return new User(email, passwordHash, name, now);
    }

    /** 운영자 전용 부트스트랩 서비스에서만 호출한다. 일반 가입/승인에서 사용하지 않는다. */
    public static User createInitialManager(String email, String passwordHash, String name, Instant now) {
        User user = new User(email, passwordHash, name, now);
        user.role = UserRole.MANAGER;
        user.status = UserStatus.ACTIVE;
        // 최초 운영자에게 승인자는 없다. 생성 근거는 별도 bootstrap 기록으로 보존한다.
        return user;
    }

    public static String normalizeEmail(String email) {
        String value = Objects.requireNonNull(email, "email").strip().toLowerCase(Locale.ROOT);
        return requireText(value, 254, "email");
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

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getName() {
        return name;
    }

    public UserRole getRole() {
        return role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public Long getHomeStoreId() {
        return homeStoreId;
    }

    public Long getApprovedBy() {
        return approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
