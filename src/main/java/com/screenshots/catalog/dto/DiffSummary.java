package com.screenshots.catalog.dto;

import com.screenshots.catalog.model.ChangeStatus;

public record DiffSummary(
        long leftId,
        long rightId,
        boolean dimensionsMatch,
        Double pixelDifferencePercentage,
        int pHashDistance,
        double pHashSimilarityPercent,
        double ssimScore,
        String note,
        ChangeStatus status,
        String reviewNote
) {
}
