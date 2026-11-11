package com.jashop.backend.user.api;

import com.jashop.backend.global.security.UserPrincipal;
import com.jashop.backend.user.api.dto.ApproveUserRequest;
import com.jashop.backend.user.api.dto.PendingUserPageResponse;
import com.jashop.backend.user.api.dto.RejectUserRequest;
import com.jashop.backend.user.api.dto.UserReviewResponse;
import com.jashop.backend.user.application.UserReviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1/admin/users", produces = MediaType.APPLICATION_JSON_VALUE)
public class UserReviewController {
    private final UserReviewService reviews;

    public UserReviewController(UserReviewService reviews) {
        this.reviews = reviews;
    }

    @GetMapping("/pending")
    public PendingUserPageResponse pending(Authentication authentication,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return reviews.pending(UserPrincipal.requireUserId(authentication), page, size);
    }

    @PostMapping(value = "/{id}/approve", consumes = MediaType.APPLICATION_JSON_VALUE)
    public UserReviewResponse approve(Authentication authentication, @PathVariable @Positive Long id,
            @Valid @RequestBody ApproveUserRequest request) {
        return reviews.approve(UserPrincipal.requireUserId(authentication), id, request);
    }

    @PostMapping("/{id}/reject")
    public UserReviewResponse reject(Authentication authentication, @PathVariable @Positive Long id,
            @Valid @RequestBody(required = false) RejectUserRequest request) {
        return reviews.reject(UserPrincipal.requireUserId(authentication), id,
                request != null ? request : new RejectUserRequest(null));
    }
}
