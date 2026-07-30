package com.screenshots.catalog.dto;

public record AcknowledgeRequest(
        Long leftId,
        Long rightId,
        String status,      // "PENDING" | "APPROVED" | "REJECTED" - validated/parsed server-side
        String note,        // optional
        String acknowledgedBy // optional - who made the call, if you want to track that
) {
}
