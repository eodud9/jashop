package com.jashop.backend.workforce.api.dto;

import java.util.List;

public record CheckoutNoticeBatchResponse(List<CheckoutNoticeResponse> notices) {
    public CheckoutNoticeBatchResponse { notices = List.copyOf(notices); }
}
