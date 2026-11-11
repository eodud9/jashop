package com.jashop.backend.user.api.dto;

import java.util.List;

import com.jashop.backend.user.domain.User;
import org.springframework.data.domain.Page;

public record PendingUserPageResponse(List<UserReviewResponse> content, int page, int size, long totalElements, int totalPages) {
    public PendingUserPageResponse {
        content = List.copyOf(content);
    }

    public static PendingUserPageResponse from(Page<User> users) {
        return new PendingUserPageResponse(users.getContent().stream().map(UserReviewResponse::from).toList(),
                users.getNumber(), users.getSize(), users.getTotalElements(), users.getTotalPages());
    }
}
