package com.screenshots.catalog.repository;

import com.screenshots.catalog.db.DatabaseConfig;
import com.screenshots.catalog.dto.ReleaseCompareResult;
import com.screenshots.catalog.dto.ScreenshotChange;
import com.screenshots.catalog.model.Screenshot;
import com.screenshots.catalog.service.ImageDiffService;
import com.screenshots.catalog.service.ScreenshotQueryService;
import com.screenshots.catalog.service.ScreenshotScanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ScreenshotRepositoryTest {

    private ScreenshotRepository repository;
    private ScreenshotScanService scanService;
    private ScreenshotQueryService queryService;
    private Path repoRoot;

    @BeforeEach
    void setUp() throws IOException {
        String jdbcUrl = "jdbc:h2:mem:test-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        DatabaseConfig db = new DatabaseConfig(jdbcUrl, "sa", "");
        repository = new ScreenshotRepository(db.dataSource());
        ImageDiffService diffService = new ImageDiffService();
        scanService = new ScreenshotScanService(repository, diffService);
        queryService = new ScreenshotQueryService(repository, diffService);

        repoRoot = Files.createTempDirectory("repo-test");
        buildFixture(repoRoot);
        scanService.scanRepository(repoRoot);
    }

    /**
     * Release_24.1/Auth/LoginDialog/login.png       (red)
     * Release_24.1/Auth/LoginDialog/login_copy.png  (identical bytes to login.png -> exact duplicate)
     * Release_24.1/Auth/ForgotPassword/forgot.png   (blue)   -- absent in 24.2 -> REMOVED
     * Release_24.2/Auth/LoginDialog/login.png       (green)  -- differs from 24.1's -> CHANGED
     * Release_24.2/Auth/LoginDialog/login_dark.png  (black)  -- absent in 24.1 -> NEW, variant "dark"
     */
    private void buildFixture(Path root) throws IOException {
        Path loginDialog241 = root.resolve("Release_24.1/Auth/LoginDialog");
        Path forgotPassword241 = root.resolve("Release_24.1/Auth/ForgotPassword");
        Path loginDialog242 = root.resolve("Release_24.2/Auth/LoginDialog");
        Files.createDirectories(loginDialog241);
        Files.createDirectories(forgotPassword241);
        Files.createDirectories(loginDialog242);

        writeSolidPng(loginDialog241.resolve("login.png"), Color.RED);
        Files.copy(loginDialog241.resolve("login.png"), loginDialog241.resolve("login_copy.png"));
        writeSolidPng(forgotPassword241.resolve("forgot.png"), Color.BLUE);

        writeSolidPng(loginDialog242.resolve("login.png"), Color.GREEN);
        writeSolidPng(loginDialog242.resolve("login_dark.png"), Color.BLACK);
    }

    private void writeSolidPng(Path path, Color color) throws IOException {
        BufferedImage img = new BufferedImage(48, 48, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 48, 48);
        g.dispose();
        ImageIO.write(img, "png", path.toFile());
    }

    @Test
    void scanPopulatesReleasesComponentsAndWindows() {
        assertEquals(List.of("24.1", "24.2"), queryService.releases());
        assertEquals(List.of("Auth"), queryService.components("24.1"));
        assertEquals(List.of("ForgotPassword", "LoginDialog"), queryService.windows("24.1", "Auth"));
    }

    @Test
    void variantIsParsedFromFileName() {
        var results = repository.search(new ScreenshotRepository.SearchFilters(
                "24.2", null, null, "login_dark.png", null, null, null, null, null, null, null, null, null, null));
        assertEquals(1, results.size());
        assertEquals("dark", results.get(0).variant());
    }

    @Test
    void exactDuplicatesAreDetectedBySha256() {
        var groups = queryService.exactDuplicates("24.1");
        assertEquals(1, groups.size());
        assertEquals(2, groups.get(0).count());
    }

    @Test
    void similarityIncludesExactDuplicateAtZeroDistance() {
        Screenshot login = repository.search(new ScreenshotRepository.SearchFilters(
                "24.1", "Auth", "LoginDialog", "login.png", null, null, null, null, null, null, null, null, null, null))
                .get(0);
        var similar = queryService.similarTo(login.id(), 0);
        assertTrue(similar.stream().anyMatch(s -> s.fileName().equals("login_copy.png")));
    }

    @Test
    void compareReleasesClassifiesNewRemovedChanged() {
        ReleaseCompareResult result = queryService.compareReleases("24.1", "24.2");

        assertEquals(1, result.newCount());       // login_dark.png
        assertEquals(2, result.removedCount());   // login_copy.png, forgot.png
        assertEquals(1, result.changedCount());   // login.png (red -> green)
        assertEquals(0, result.unchangedCount());
        assertEquals(2, result.totalInToRelease());

        boolean foundChangedLogin = result.changes().stream()
                .anyMatch(c -> c.changeType() == ScreenshotChange.ChangeType.CHANGED
                        && c.fileName().equals("login.png")
                        && c.pixelDifferencePercentage() != null
                        && c.pixelDifferencePercentage() > 0);
        assertTrue(foundChangedLogin);
    }

    @Test
    void sidecarMetadataIsIngestedAndSearchable() throws IOException {
        Path loginDialog = repoRoot.resolve("Release_24.1/Auth/LoginDialog");
        Files.writeString(loginDialog.resolve("login.json"), """
                {
                  "windowClass": "com.acme.hmi.LoginDialog",
                  "theme": "Nimbus",
                  "plafVersion": "1.2.0",
                  "locale": "en_US",
                  "screenWidth": 1920,
                  "screenHeight": 1080,
                  "components": [
                    { "componentId": "usernameField", "swingClass": "JTextField" },
                    { "componentId": "loginButton", "swingClass": "JButton" }
                  ]
                }
                """);

        scanService.scanRepository(repoRoot); // rescan to pick up the sidecar

        Screenshot login = repository.search(new ScreenshotRepository.SearchFilters(
                        "24.1", "Auth", "LoginDialog", "login.png", null, null, null, null, null, null, null, null, null, null))
                .get(0);

        var detail = queryService.detail(login.id()).orElseThrow();
        assertNotNull(detail.metadata());
        assertEquals("Nimbus", detail.metadata().theme());
        assertEquals(1920, detail.metadata().screenWidth());
        assertEquals(2, detail.components().size());

        var bySwingClass = repository.search(new ScreenshotRepository.SearchFilters(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                "JButton", null, null, null, null));
        assertTrue(bySwingClass.stream().anyMatch(s -> s.id().equals(login.id())));

        assertTrue(queryService.swingClasses().containsAll(List.of("JButton", "JTextField")));
    }

    @Test
    void rescanRemovesScreenshotsWhoseFileNoLongerExists() throws IOException {
        Files.delete(repoRoot.resolve("Release_24.1/Auth/ForgotPassword/forgot.png"));
        scanService.scanRepository(repoRoot);

        var results = repository.search(new ScreenshotRepository.SearchFilters(
                "24.1", "Auth", "ForgotPassword", null, null, null, null, null, null, null, null, null, null, null));
        assertTrue(results.isEmpty());
    }

    @Test
    void symlinkedFilesAndDirectoriesInsideTheTreeAreNotFollowed() throws IOException {
        Path secretDir = Files.createTempDirectory("secret-outside-repo");
        writeSolidPng(secretDir.resolve("secret.png"), Color.PINK);

        Path windowDir = repoRoot.resolve("Release_24.1/Auth/LoginDialog");
        Path symlinkFile = windowDir.resolve("sneaky-link.png");
        Path symlinkDir = repoRoot.resolve("Release_24.1/Auth/SneakySymlinkWindow");

        try {
            Files.createSymbolicLink(symlinkFile, secretDir.resolve("secret.png"));
            Files.createSymbolicLink(symlinkDir, secretDir);
        } catch (UnsupportedOperationException | IOException e) {
            return; // environment can't create symlinks - nothing to assert here
        }

        var result = scanService.scanRepository(repoRoot);

        // Neither the symlinked file nor anything reachable only via the symlinked directory
        // should have been imported.
        var bySneakyFile = repository.search(new ScreenshotRepository.SearchFilters(
                null, null, null, "sneaky-link.png", null, null, null, null, null, null, null, null, null, null));
        assertTrue(bySneakyFile.isEmpty());

        var sneakyWindow = repository.findWindowNames("24.1", "Auth").stream()
                .anyMatch(w -> w.equals("SneakySymlinkWindow"));
        assertFalse(sneakyWindow, "A symlinked directory should never be walked into");

        // A skipped symlinked directory must be visible in the warnings, not silent - a silent skip
        // is what made this genuinely confusing to debug in practice (component/window rows exist,
        // but come back empty, with nothing indicating why).
        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("SneakySymlinkWindow") && w.contains("symlink")),
                "Expected a warning about the skipped symlinked directory, got: " + result.warnings());
    }

    /**
     * The actual bug reported in practice: every screenshot got deleted and recreated on
     * every single rescan. Root cause was App.java scanning with whatever raw path form the
     * caller typed, rather than ScanPathValidator's canonicalized real path - so two calls
     * that both nominally point at "the same" root but are typed slightly differently
     * (redundant "." segment here; a trailing slash or case difference on a case-insensitive
     * mount are the real-world equivalents) computed different absolute file paths for the
     * same physical files, and the rescan cleanup interpreted the old paths as gone.
     *
     * This test exercises the fix the way App.java actually uses it: validate() first, scan
     * using canonicalPath(), not the raw input.
     */
    @Test
    void rescanningViaDifferentlyTypedButEquivalentPathsDoesNotDeleteAnything() throws IOException {
        var validator = new com.screenshots.catalog.security.ScanPathValidator(List.of(repoRoot.getParent()));

        var firstValidation = validator.validate(repoRoot);
        assertTrue(firstValidation.allowed(), firstValidation.reason());
        scanService.scanRepository(firstValidation.canonicalPath());

        // Same real directory, deliberately typed with a redundant "." segment.
        Path messyEquivalent = repoRoot.resolveSibling(repoRoot.getFileName() + "/.");
        var secondValidation = validator.validate(messyEquivalent);
        assertTrue(secondValidation.allowed(), secondValidation.reason());
        var secondScan = scanService.scanRepository(secondValidation.canonicalPath());

        assertEquals(0, secondScan.removedMissing(),
                "Rescanning the same real directory via a differently-typed but equivalent path " +
                "must not delete anything - file identity should be stable");
    }

    @Test
    void imagesDirectlyInAComponentRootAreCatalogedUnderASyntheticWindow() throws IOException {
        Path componentRootImage = repoRoot.resolve("Release_24.1/Auth/no-window.png");
        writeSolidPng(componentRootImage, Color.YELLOW);

        var result = scanService.scanRepository(repoRoot);

        var rootWindowShots = repository.search(new ScreenshotRepository.SearchFilters(
                "24.1", "Auth", ScreenshotScanService.ROOT_WINDOW_NAME, null,
                null, null, null, null, null, null, null, null, null, null));
        assertEquals(1, rootWindowShots.size());
        assertEquals("no-window.png", rootWindowShots.get(0).fileName());

        assertTrue(result.warnings().stream().anyMatch(w -> w.contains("no window subfolder") && w.contains(ScreenshotScanService.ROOT_WINDOW_NAME)),
                "Expected a warning explaining the synthetic window, got: " + result.warnings());

        // The pre-existing LoginDialog window (a real subfolder) must be completely unaffected.
        var loginShots = repository.search(new ScreenshotRepository.SearchFilters(
                "24.1", "Auth", "LoginDialog", null,
                null, null, null, null, null, null, null, null, null, null));
        assertEquals(2, loginShots.size()); // login.png + login_copy.png, per buildFixture
    }
}
