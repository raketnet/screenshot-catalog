package com.screenshots.catalog.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Guards POST /api/scan: without this, the endpoint would accept any
 * absolute path a caller supplies and walk it, with no restriction on what
 * part of the filesystem that might be (e.g. a home directory, "/etc").
 *
 * Two checks:
 *  1. The requested root's REAL path (symlinks resolved, case canonicalized
 *     on a case-insensitive filesystem) must fall under one of the
 *     configured allowed roots. Checking the real path rather than the
 *     as-given path is what stops a symlink from being used to point
 *     "inside" an allowed root while actually resolving somewhere else.
 *  2. The root must exist and be a directory.
 *
 * Callers MUST scan using canonicalPath() from the result, not whatever
 * path they originally passed in. Screenshot identity (file_path) is keyed
 * on the exact string a scan computes for each file - if two scans of "the
 * same" root produce even slightly different absolute-path strings (a
 * trailing slash, different case on a case-insensitive mount, a different
 * but equivalent alias for the same network/VM-shared folder), every file's
 * computed path shifts, and the window-scoped rescan cleanup will interpret
 * the old paths as deleted and recreate everything under the new ones -
 * every single scan, forever. Scanning from the canonicalized real path
 * instead of the as-given one is what makes file identity stable regardless
 * of how the caller happened to type the root each time.
 */
public final class ScanPathValidator {

    private final List<Path> allowedRoots; // already absolute + normalized (see AppConfig)

    public ScanPathValidator(List<Path> allowedRoots) {
        this.allowedRoots = allowedRoots;
    }

    public record ValidationResult(boolean allowed, String reason, Path canonicalPath) {
        public static ValidationResult ok(Path canonicalPath) {
            return new ValidationResult(true, null, canonicalPath);
        }
        public static ValidationResult rejected(String reason) {
            return new ValidationResult(false, reason, null);
        }
    }

    public ValidationResult validate(Path requestedRoot) {
        if (!Files.exists(requestedRoot)) {
            return ValidationResult.rejected("Path does not exist: " + requestedRoot);
        }
        if (!Files.isDirectory(requestedRoot)) {
            return ValidationResult.rejected("Not a directory: " + requestedRoot);
        }

        Path real;
        try {
            real = requestedRoot.toRealPath(); // resolves symlinks + canonicalizes case/aliases
        } catch (IOException e) {
            return ValidationResult.rejected("Could not resolve path: " + e.getMessage());
        }

        boolean withinAllowedRoot = allowedRoots.stream().anyMatch(real::startsWith);
        if (!withinAllowedRoot) {
            return ValidationResult.rejected(
                    "Path is outside the configured allowed scan roots. " +
                    "Set SCREENSHOT_ALLOWED_ROOTS to permit this location if it's intentional."
            );
        }

        return ValidationResult.ok(real);
    }
}
