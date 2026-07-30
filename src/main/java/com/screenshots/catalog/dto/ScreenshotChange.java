package com.screenshots.catalog.dto;

import com.screenshots.catalog.model.ChangeStatus;

public record ScreenshotChange(
        String component,
        String windowName,
        String fileName,
        ChangeType changeType,
        Long fromId,
        Long toId,
        Double pixelDifferencePercentage,
        Double ssimScore,
        Double pHashSimilarityPercent,
        ChangeStatus status,
        String reviewNote
) {
    public enum ChangeType { NEW, REMOVED, CHANGED, UNCHANGED }
}
