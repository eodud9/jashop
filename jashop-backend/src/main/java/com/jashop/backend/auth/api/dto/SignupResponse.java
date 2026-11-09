package com.jashop.backend.auth.api.dto;

import com.jashop.backend.user.domain.User;
import com.jashop.backend.user.domain.UserRole;
import com.jashop.backend.user.domain.UserStatus;

public record SignupResponse(Long id, String name, String email, UserRole role,
                             UserStatus status, Long homeStoreId) {
    public static SignupResponse from(User user) {
        return new SignupResponse(user.getId(), user.getName(), user.getEmail(),
                user.getRole(), user.getStatus(), user.getHomeStoreId());
    }
}
