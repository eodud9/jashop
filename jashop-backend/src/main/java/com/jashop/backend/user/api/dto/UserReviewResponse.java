package com.jashop.backend.user.api.dto;

import java.time.Instant;

import com.jashop.backend.user.domain.User;
import com.jashop.backend.user.domain.UserRole;
import com.jashop.backend.user.domain.UserStatus;

public record UserReviewResponse(Long id, String name, String email, UserRole role, UserStatus status,
        Long homeStoreId, Long approvedBy, Instant approvedAt, String rejectionReason,
        Instant createdAt, Instant updatedAt) {
    public static UserReviewResponse from(User user) {
        return new UserReviewResponse(user.getId(), user.getName(), user.getEmail(), user.getRole(), user.getStatus(),
                user.getHomeStoreId(), user.getApprovedBy(), user.getApprovedAt(), user.getRejectionReason(),
                user.getCreatedAt(), user.getUpdatedAt());
    }
}
