package com.screenshots.catalog.security;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScanPathValidatorTest {

    @Test
    void allowsPathsUnderAnAllowedRoot() throws IOException {
        Path allowedRoot = Files.createTempDirectory("allowed");
        Path repo = allowedRoot.resolve("Repository");
        Files.createDirectories(repo);

        var validator = new ScanPathValidator(List.of(allowedRoot));
        var result = validator.validate(repo);

        assertTrue(result.allowed(), result.reason());
    }

    @Test
    void canonicalPathIsPopulatedWhenAllowed() throws IOException {
        Path allowedRoot = Files.createTempDirectory("allowed");
        Path repo = allowedRoot.resolve("Repository");
        Files.createDirectories(repo);

        var validator = new ScanPathValidator(List.of(allowedRoot));
        var result = validator.validate(repo);

        assertNotNull(result.canonicalPath());
        assertTrue(Files.isSameFile(repo, result.canonicalPath()));
    }

    @Test
    void canonicalPathIsNullWhenRejected() throws IOException {
        Path allowedRoot = Files.createTempDirectory("allowed");
        Path elsewhere = Files.createTempDirectory("elsewhere");

        var validator = new ScanPathValidator(List.of(allowedRoot));
        var result = validator.validate(elsewhere);

        assertNull(result.canonicalPath());
    }

    /**
     * This is the actual bug this class exists to prevent: two different-looking but
     * equivalent path strings for the SAME real directory (redundant "." segment here;
     * a trailing slash or different case on a case-insensitive filesystem are other real
     * examples) must resolve to the exact same canonicalPath(). If they didn't, a scanner
     * using canonicalPath() as its root would compute different absolute file paths for
     * the same physical files on each scan - which is exactly what caused every screenshot
     * to be deleted-and-recreated on every single rescan in practice.
     */
    @Test
    void equivalentPathRepresentationsCanonicalizeToTheSameRealPath() throws IOException {
        Path allowedRoot = Files.createTempDirectory("allowed");
        Path repo = allowedRoot.resolve("Repository");
        Files.createDirectories(repo);

        Path messyEquivalent = allowedRoot.resolve("./Repository/.");

        var validator = new ScanPathValidator(List.of(allowedRoot));
        var cleanResult = validator.validate(repo);
        var messyResult = validator.validate(messyEquivalent);

        assertTrue(cleanResult.allowed());
        assertTrue(messyResult.allowed());
        assertEquals(cleanResult.canonicalPath(), messyResult.canonicalPath(),
                "Two equivalent representations of the same directory must canonicalize identically");
    }

    @Test
    void rejectsPathsOutsideAllAllowedRoots() throws IOException {
        Path allowedRoot = Files.createTempDirectory("allowed");
        Path elsewhere = Files.createTempDirectory("elsewhere");

        var validator = new ScanPathValidator(List.of(allowedRoot));
        var result = validator.validate(elsewhere);

        assertFalse(result.allowed());
        assertNotNull(result.reason());
    }

    @Test
    void rejectsNonExistentPaths() {
        Path allowedRoot = Path.of("/tmp");
        var validator = new ScanPathValidator(List.of(allowedRoot));

        var result = validator.validate(Path.of("/tmp/definitely-does-not-exist-" + System.nanoTime()));
        assertFalse(result.allowed());
    }

    @Test
    void rejectsFilesThatArentDirectories() throws IOException {
        Path allowedRoot = Files.createTempDirectory("allowed");
        Path file = allowedRoot.resolve("not-a-directory.txt");
        Files.writeString(file, "hello");

        var validator = new ScanPathValidator(List.of(allowedRoot));
        var result = validator.validate(file);

        assertFalse(result.allowed());
    }

    @Test
    void rejectsSymlinkEscapeOutOfAllowedRoot() throws IOException {
        Path allowedRoot = Files.createTempDirectory("allowed");
        Path secretElsewhere = Files.createTempDirectory("secret");

        Path symlinkInsideAllowedRoot = allowedRoot.resolve("looks-innocent");
        try {
            Files.createSymbolicLink(symlinkInsideAllowedRoot, secretElsewhere);
        } catch (UnsupportedOperationException | IOException e) {
            // Some environments (e.g. restricted CI) can't create symlinks - skip rather than fail.
            return;
        }

        var validator = new ScanPathValidator(List.of(allowedRoot));
        // The symlink's real path resolves OUTSIDE allowedRoot, so this must be rejected
        // even though the as-given path is nominally "inside" allowedRoot.
        var result = validator.validate(symlinkInsideAllowedRoot);

        assertFalse(result.allowed(), "A symlink pointing outside the allowed root must be rejected");
    }
}
