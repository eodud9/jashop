package com.jashop.backend.workforce.api;

import com.jashop.backend.global.error.BusinessException;
import com.jashop.backend.global.error.ErrorCode;
import com.jashop.backend.global.security.UserPrincipal;
import com.jashop.backend.workforce.api.dto.*;
import com.jashop.backend.workforce.application.AttendanceService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1/attendances/me", produces = MediaType.APPLICATION_JSON_VALUE)
public class AttendanceController {
    private final AttendanceService attendances;
    public AttendanceController(AttendanceService attendances) { this.attendances = attendances; }

    @PostMapping(value = "/check-in", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AttendanceResponse> checkIn(Authentication auth, @Valid @RequestBody CheckInRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(attendances.checkIn(UserPrincipal.requireUserId(auth), request));
    }

    @PostMapping("/check-out")
    public ResponseEntity<AttendanceResponse> checkOut(Authentication auth,
            @RequestHeader("X-Attendance-Id") Long attendanceId, @RequestHeader("Idempotency-Key") String key,
            @RequestBody(required = false) byte[] body) {
        if (body != null && body.length > 0) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(attendances.checkOut(UserPrincipal.requireUserId(auth), attendanceId, key));
    }

    @GetMapping("/current")
    public ResponseEntity<AttendanceResponse> current(Authentication auth) {
        var response = attendances.current(UserPrincipal.requireUserId(auth));
        return response == null ? ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
                : ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
    }
}
