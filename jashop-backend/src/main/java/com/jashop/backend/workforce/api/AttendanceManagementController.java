package com.jashop.backend.workforce.api;

import java.time.LocalDate;
import com.jashop.backend.global.security.UserPrincipal;
import com.jashop.backend.workforce.api.dto.*;
import com.jashop.backend.workforce.application.AttendanceManagementService;
import com.jashop.backend.workforce.domain.AttendanceApprovalStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1/attendances", produces = MediaType.APPLICATION_JSON_VALUE)
public class AttendanceManagementController {
    private final AttendanceManagementService management;
    public AttendanceManagementController(AttendanceManagementService management) { this.management = management; }

    @GetMapping("/me")
    public ResponseEntity<AttendancePageResponse> mine(Authentication auth,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) AttendanceApprovalStatus approvalStatus,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                management.mine(UserPrincipal.requireUserId(auth), fromDate, toDate, approvalStatus, page, size));
    }

    @GetMapping
    public ResponseEntity<AttendancePageResponse> list(Authentication auth, @RequestParam @Positive Long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) AttendanceApprovalStatus approvalStatus,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                management.list(UserPrincipal.requireUserId(auth), storeId, fromDate, toDate, approvalStatus, page, size));
    }

    @PostMapping(value = "/{id}/approve", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AttendanceResponse> approve(Authentication auth, @PathVariable @Positive Long id,
            @Valid @RequestBody ApproveAttendanceRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                management.approve(UserPrincipal.requireUserId(auth), id, request));
    }
}
