package com.jashop.backend.workforce.api.dto;

import java.util.List;

public record CheckoutNoticePageResponse(List<CheckoutNoticeResponse> content, int page, int size, long totalElements, int totalPages) {
    public CheckoutNoticePageResponse { content = List.copyOf(content); }
}
