package com.screenshots.catalog.model;

import java.time.LocalDateTime;

public record ChangeAcknowledgment(
        Long leftId,
        Long rightId,
        ChangeStatus status,
        String note,
        String acknowledgedBy,
        LocalDateTime acknowledgedAt
) {
    public static ChangeAcknowledgment pending(Long leftId, Long rightId) {
        return new ChangeAcknowledgment(leftId, rightId, ChangeStatus.PENDING, null, null, null);
    }
}
