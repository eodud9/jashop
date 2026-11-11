package com.jashop.backend.auth.api.dto;

import com.jashop.backend.user.domain.User;
import com.jashop.backend.user.domain.UserRole;
import com.jashop.backend.user.domain.UserStatus;

public record AuthenticatedUserResponse(Long id, String name, String email, UserRole role, UserStatus status, Long homeStoreId) {
    public static AuthenticatedUserResponse from(User user) {
        return new AuthenticatedUserResponse(user.getId(), user.getName(), user.getEmail(), user.getRole(),
                user.getStatus(), user.getHomeStoreId());
    }
}
