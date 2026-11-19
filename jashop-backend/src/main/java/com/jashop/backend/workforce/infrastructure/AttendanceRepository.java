package com.jashop.backend.workforce.infrastructure;

import java.util.List;
import java.util.Optional;
import com.jashop.backend.workforce.domain.Attendance;
import com.jashop.backend.workforce.domain.AttendanceApprovalStatus;
import java.time.LocalDate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    @Query("select a from Attendance a, WorkSchedule s where a.workScheduleId = s.id and s.userId = :userId and a.closedAt is null")
    List<Attendance> findActive(@Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Attendance a, WorkSchedule s where a.workScheduleId = s.id and s.userId = :userId and a.closedAt is null")
    List<Attendance> findActiveForUpdate(@Param("userId") Long userId);

    @Query("""
            select a from Attendance a, WorkSchedule s where a.workScheduleId = s.id
            and (:userId is null or s.userId = :userId) and (:storeId is null or s.storeId = :storeId)
            and s.workDate >= :fromDate and s.workDate <= :toDate
            and (:status is null or a.approvalStatus = :status)
            """)
    Page<Attendance> findHistory(@Param("userId") Long userId, @Param("storeId") Long storeId,
            @Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate,
            @Param("status") AttendanceApprovalStatus status, Pageable pageable);
}
