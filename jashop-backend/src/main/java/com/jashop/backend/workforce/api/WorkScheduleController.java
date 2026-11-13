package com.jashop.backend.workforce.api;

import java.net.URI;
import java.time.LocalDate;
import com.jashop.backend.global.security.UserPrincipal;
import com.jashop.backend.workforce.api.dto.*;
import com.jashop.backend.workforce.application.WorkScheduleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1/work-schedules", produces = MediaType.APPLICATION_JSON_VALUE)
public class WorkScheduleController {
    private final WorkScheduleService schedules;
    public WorkScheduleController(WorkScheduleService schedules) { this.schedules = schedules; }

    @GetMapping("/me")
    public WorkSchedulePageResponse mine(Authentication auth,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return schedules.mine(UserPrincipal.requireUserId(auth), fromDate, toDate, page, size);
    }

    @GetMapping
    public WorkSchedulePageResponse list(Authentication auth, @RequestParam @Positive Long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return schedules.list(UserPrincipal.requireUserId(auth), storeId, fromDate, toDate, page, size);
    }

    @GetMapping("/{id}")
    public WorkScheduleResponse get(Authentication auth, @PathVariable @Positive Long id) {
        return schedules.get(UserPrincipal.requireUserId(auth), id);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WorkScheduleResponse> create(Authentication auth, @Valid @RequestBody CreateWorkScheduleRequest request) {
        var response = schedules.create(UserPrincipal.requireUserId(auth), request);
        return ResponseEntity.created(URI.create("/api/v1/work-schedules/" + response.id())).body(response);
    }

    @PatchMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public WorkScheduleResponse update(Authentication auth, @PathVariable @Positive Long id,
            @Valid @RequestBody UpdateWorkScheduleRequest request) {
        return schedules.update(UserPrincipal.requireUserId(auth), id, request);
    }

    @PostMapping("/{id}/cancel")
    public WorkScheduleResponse cancel(Authentication auth, @PathVariable @Positive Long id) {
        return schedules.cancel(UserPrincipal.requireUserId(auth), id);
    }
}
