package com.jashop.backend.workforce.infrastructure;

import java.time.Instant;
import java.util.Optional;
import com.jashop.backend.workforce.domain.CheckoutNotice;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface CheckoutNoticeRepository extends JpaRepository<CheckoutNotice, Long> {
    @Query("select n.workScheduleId from CheckoutNotice n where n.id = :id")
    Optional<Long> findScheduleId(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select n from CheckoutNotice n where n.id = :id")
    Optional<CheckoutNotice> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select n from CheckoutNotice n, WorkSchedule s where n.workScheduleId = s.id
            and (:userId is null or s.userId = :userId) and (:storeId is null or s.storeId = :storeId)
            and n.sentAt >= :from and n.sentAt < :until
            """)
    Page<CheckoutNotice> findHistory(@Param("userId") Long userId, @Param("storeId") Long storeId,
            @Param("from") Instant from, @Param("until") Instant until, Pageable pageable);
}
