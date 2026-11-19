package com.jashop.backend.workforce.api;

import java.time.LocalDate;
import com.jashop.backend.global.error.BusinessException;
import com.jashop.backend.global.error.ErrorCode;
import com.jashop.backend.global.security.UserPrincipal;
import com.jashop.backend.workforce.api.dto.*;
import com.jashop.backend.workforce.application.CheckoutNoticeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1/checkout-notices", produces = MediaType.APPLICATION_JSON_VALUE)
public class CheckoutNoticeController {
    private final CheckoutNoticeService notices;
    public CheckoutNoticeController(CheckoutNoticeService notices) { this.notices = notices; }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CheckoutNoticeBatchResponse> create(Authentication auth, @Valid @RequestBody CreateCheckoutNoticesRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(notices.create(UserPrincipal.requireUserId(auth), request));
    }

    @GetMapping("/me")
    public ResponseEntity<CheckoutNoticePageResponse> mine(Authentication auth,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                notices.mine(UserPrincipal.requireUserId(auth), fromDate, toDate, page, size));
    }

    @GetMapping
    public ResponseEntity<CheckoutNoticePageResponse> list(Authentication auth, @RequestParam @Positive Long storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                notices.list(UserPrincipal.requireUserId(auth), storeId, fromDate, toDate, page, size));
    }

    @PostMapping("/{id}/acknowledge")
    public ResponseEntity<CheckoutNoticeResponse> acknowledge(Authentication auth, @PathVariable @Positive Long id,
            @RequestBody(required = false) byte[] body) {
        if (body != null && body.length > 0) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(notices.acknowledge(UserPrincipal.requireUserId(auth), id));
    }
}
