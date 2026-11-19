package com.jashop.backend.workforce.api.dto;

import java.time.Instant;
import java.time.LocalDate;
import com.jashop.backend.workforce.domain.CheckoutNotice;
import com.jashop.backend.workforce.domain.WorkSchedule;

public record CheckoutNoticeResponse(Long id, Long workScheduleId, Long userId, Long storeId, LocalDate workDate,
        Instant plannedCheckOutAt, Long sentBy, Instant sentAt, Instant acknowledgedAt) {
    public static CheckoutNoticeResponse from(CheckoutNotice n, WorkSchedule s) {
        return new CheckoutNoticeResponse(n.getId(), n.getWorkScheduleId(), s.getUserId(), s.getStoreId(), s.getWorkDate(),
                n.getPlannedCheckOutAt(), n.getSentBy(), n.getSentAt(), n.getAcknowledgedAt());
    }
}
