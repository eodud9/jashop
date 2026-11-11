package com.jashop.backend.auth.session.infrastructure;

import java.time.Instant;
import java.util.Optional;

import com.jashop.backend.auth.session.domain.AuthSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface AuthSessionRepository extends JpaRepository<AuthSession, Long> {
    // 루트 ID만 먼저 읽어 잠금 전의 엔티티를 영속성 컨텍스트에 남기지 않는다.
    @Query("select min(r.id) from AuthSession r where r.familyId = (select s.familyId from AuthSession s where s.tokenHash = :hash)")
    Optional<Long> findRootIdByTokenHash(@Param("hash") String hash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AuthSession s where s.id = :id")
    Optional<AuthSession> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AuthSession> findByTokenHash(String tokenHash);

    @Modifying(flushAutomatically = true)
    @Query("update AuthSession s set s.revokedAt = :now where s.familyId = :family and s.revokedAt is null")
    int revokeTokens(@Param("family") String familyId, @Param("now") Instant now);

    @Query("""
            select case when count(s) > 0 then true else false end from AuthSession s
            where s.familyId = :family and s.userId = :userId
              and s.familyRevokedAt is null and s.expiresAt > :now
              and s.id = (select min(r.id) from AuthSession r where r.familyId = :family)
            """)
    boolean isFamilyActive(@Param("family") String familyId, @Param("userId") Long userId, @Param("now") Instant now);
}
