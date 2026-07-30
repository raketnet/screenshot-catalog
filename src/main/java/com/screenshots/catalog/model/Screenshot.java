package com.screenshots.catalog.model;

import java.time.LocalDateTime;

/**
 * A single cataloged screenshot, denormalized for reading: the underlying
 * storage is releases -> components -> windows -> screenshots, but API
 * consumers just want one flat record per screenshot.
 */
public record Screenshot(
        Long id,
        String release,
        String component,
        String windowName,
        String variant,       // nullable - heuristically parsed from file name (e.g. "dark" from login_dark.png)
        String fileName,
        String filePath,
        int width,
        int height,
        long fileSizeBytes,
        String sha256,
        long pHash,
        LocalDateTime capturedAt
) {
}
