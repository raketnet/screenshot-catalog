package com.screenshots.catalog.dto;

import java.util.List;

public record ReleaseCompareResult(
        String fromRelease,
        String toRelease,
        int totalInToRelease,
        int newCount,
        int removedCount,
        int changedCount,
        int unchangedCount,
        int changesTotal,   // newCount + removedCount + changedCount + unchangedCount, before paging
        int changesLimit,
        int changesOffset,
        List<ScreenshotChange> changes  // one page, per changesLimit/changesOffset
) {
}
