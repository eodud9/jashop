package com.jashop.backend.workforce.infrastructure;

import java.util.List;
import java.util.Optional;
import com.jashop.backend.workforce.domain.Attendance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface AttendanceRepository extends JpaRepository<Attendance, Long> {
    @Query("select a.workScheduleId from Attendance a where a.id = :id")
    Optional<Long> findScheduleId(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Attendance a where a.id = :id")
    Optional<Attendance> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Attendance a where a.workScheduleId = :id")
    Optional<Attendance> findByScheduleIdForUpdate(@Param("id") Long id);

    @Query("select a from Attendance a, WorkSchedule s where a.workScheduleId = s.id and s.userId = :userId and a.checkOutAt is null")
    List<Attendance> findActive(@Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Attendance a, WorkSchedule s where a.workScheduleId = s.id and s.userId = :userId and a.checkOutAt is null")
    List<Attendance> findActiveForUpdate(@Param("userId") Long userId);
}
