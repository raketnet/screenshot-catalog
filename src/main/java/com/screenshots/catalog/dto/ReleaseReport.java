package com.screenshots.catalog.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ReleaseReport(
        String fromRelease,
        String toRelease,
        LocalDateTime generatedAt,
        int totalInToRelease,
        int newCount,
        int removedCount,
        int changedCount,
        int unchangedCount,
        int duplicateScreenCount,
        List<String> newComponents,
        List<String> newWindows,   // "Component / Window"
        List<ScreenshotChange> changes
) {
}
