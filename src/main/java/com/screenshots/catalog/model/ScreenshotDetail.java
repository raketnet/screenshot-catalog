package com.screenshots.catalog.model;

import java.util.List;

public record ScreenshotDetail(
        Screenshot screenshot,
        SwingMetadata metadata,      // null if no sidecar was found at scan time
        List<SwingComponentInfo> components
) {
}
