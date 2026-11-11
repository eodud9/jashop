package com.jashop.backend.auth.session.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "auth_sessions")
public class AuthSession {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "family_id", nullable = false, updatable = false, length = 36)
    private String familyId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", nullable = false, updatable = false, length = 64)
    private String tokenHash;
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "issued_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private Instant issuedAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "expires_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private Instant expiresAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "revoked_at", columnDefinition = "datetime(6)")
    private Instant revokedAt;
    @Column(name = "replaced_by_id")
    private Long replacedById;
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "family_revoked_at", columnDefinition = "datetime(6)")
    private Instant familyRevokedAt;
    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime(6)")
    private Instant createdAt;

    protected AuthSession() {}

    public static AuthSession create(Long userId, String familyId, String tokenHash, Instant now, Instant expiresAt) {
        if (userId == null || userId <= 0 || familyId == null || !UUID.fromString(familyId).toString().equals(familyId)
                || tokenHash == null || !tokenHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("세션 식별 정보가 올바르지 않습니다.");
        }
        if (!Objects.requireNonNull(expiresAt).isAfter(Objects.requireNonNull(now))) {
            throw new IllegalArgumentException("세션 만료 시각이 올바르지 않습니다.");
        }
        var session = new AuthSession();
        session.userId = userId;
        session.familyId = familyId;
        session.tokenHash = tokenHash;
        session.issuedAt = now;
        session.createdAt = now;
        session.expiresAt = expiresAt;
        return session;
    }

    public void rotateTo(Long nextId, Instant now) {
        if (revokedAt != null || nextId == null || nextId <= 0 || !expiresAt.isAfter(now)) {
            throw new IllegalStateException("현재 세션은 회전할 수 없습니다.");
        }
        revokedAt = Objects.requireNonNull(now);
        replacedById = nextId;
    }

    /** 계열에서 ID가 가장 작은 루트 행에만 호출한다. 회전 폐기와 계열 폐기는 별개다. */
    public void revokeFamily(Instant now) {
        Objects.requireNonNull(now);
        if (familyRevokedAt == null) familyRevokedAt = now;
        if (revokedAt == null) revokedAt = now;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getFamilyId() { return familyId; }
    public String getTokenHash() { return tokenHash; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public Long getReplacedById() { return replacedById; }
    public Instant getFamilyRevokedAt() { return familyRevokedAt; }
}
