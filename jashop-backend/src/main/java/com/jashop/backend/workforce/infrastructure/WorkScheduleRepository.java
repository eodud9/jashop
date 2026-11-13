package com.jashop.backend.workforce.infrastructure;

import java.time.Instant;
import java.util.Optional;
import com.jashop.backend.workforce.domain.WorkSchedule;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface WorkScheduleRepository extends JpaRepository<WorkSchedule, Long> {
    Page<WorkSchedule> findByUserIdAndStartAtGreaterThanEqualAndStartAtLessThan(Long userId, Instant from, Instant until, Pageable page);
    Page<WorkSchedule> findByStoreIdAndStartAtGreaterThanEqualAndStartAtLessThan(Long storeId, Instant from, Instant until, Pageable page);

    // 먼저 소유자만 조회한다. 잠금 전에 엔티티를 로드해 오래된 상태를 재사용하지 않는다.
    @Query("select s.userId from WorkSchedule s where s.id = :id")
    Optional<Long> findOwnerId(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from WorkSchedule s where s.id = :id")
    Optional<WorkSchedule> findByIdForUpdate(@Param("id") Long id);
}
