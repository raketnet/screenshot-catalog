package com.screenshots.catalog.service;

import com.screenshots.catalog.db.DatabaseConfig;
import com.screenshots.catalog.dto.ReleaseReport;
import com.screenshots.catalog.repository.ScreenshotRepository;
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

class ReleaseReportTest {

    private ScreenshotQueryService queryService;
    private ScreenshotScanService scanService;
    private Path repoRoot;

    @BeforeEach
    void setUp() throws IOException {
        String jdbcUrl = "jdbc:h2:mem:test-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        DatabaseConfig db = new DatabaseConfig(jdbcUrl, "sa", "");
        ScreenshotRepository repository = new ScreenshotRepository(db.dataSource());
        ImageDiffService diffService = new ImageDiffService();
        scanService = new ScreenshotScanService(repository, diffService);
        queryService = new ScreenshotQueryService(repository, diffService);

        repoRoot = Files.createTempDirectory("repo-report-test");

        write(repoRoot, "Release_24.1/Auth/LoginDialog/login.png", Color.RED);
        write(repoRoot, "Release_24.1/Auth/ForgotPassword/forgot.png", Color.BLUE);
        write(repoRoot, "Release_24.1/Alarm/AlarmOverview/overview.png", Color.WHITE);
        Files.copy(repoRoot.resolve("Release_24.1/Auth/LoginDialog/login.png"),
                repoRoot.resolve("Release_24.1/Auth/LoginDialog/login_copy.png"));

        write(repoRoot, "Release_24.2/Auth/LoginDialog/login.png", Color.GREEN);       // changed
        write(repoRoot, "Release_24.2/Auth/LoginDialog/login_copy.png", Color.RED);    // unchanged (matches 24.1's login.png)
        write(repoRoot, "Release_24.2/Alarm/AlarmOverview/overview.png", Color.WHITE); // unchanged
        write(repoRoot, "Release_24.2/Auth/TwoFactor/otp.png", Color.MAGENTA);         // new window in existing component
        write(repoRoot, "Release_24.2/Trend/TrendGraph/graph.png", Color.CYAN);        // new component entirely
        // Release_24.1's forgot.png has no counterpart in 24.2 -> removed

        scanService.scanRepository(repoRoot);
    }

    private void write(Path root, String relPath, Color color) throws IOException {
        Path p = root.resolve(relPath);
        Files.createDirectories(p.getParent());
        BufferedImage img = new BufferedImage(48, 48, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 48, 48);
        g.dispose();
        ImageIO.write(img, "png", p.toFile());
    }

    @Test
    void reportCountsMatchTheUnderlyingComparison() {
        ReleaseReport report = queryService.buildReleaseReport("24.1", "24.2");

        assertEquals(5, report.totalInToRelease());
        assertEquals(2, report.newCount());       // otp.png, graph.png
        assertEquals(1, report.removedCount());   // forgot.png
        assertEquals(1, report.changedCount());   // login.png
        assertEquals(2, report.unchangedCount()); // login_copy.png, overview.png
        assertEquals(List.of("Trend"), report.newComponents());
        assertTrue(report.newWindows().contains("Auth / TwoFactor"));
        assertTrue(report.newWindows().contains("Trend / TrendGraph"));
    }

    @Test
    void renderedHtmlIsWellFormedAndContainsEveryImageReference() {
        ReleaseReport report = queryService.buildReleaseReport("24.1", "24.2");
        String html = ReleaseReportRenderer.render(report);

        assertTrue(html.startsWith("<!doctype html>"));
        assertTrue(html.contains("Release Report"));
        assertTrue(html.contains("24.1"));
        assertTrue(html.contains("24.2"));

        // one changed screen -> 2 images (before/after); 2 new + 1 removed -> 3 more
        long imgCount = html.split("<img", -1).length - 1;
        assertEquals(5, imgCount);

        assertTrue(html.contains("/api/diff/image?leftId="));
        assertTrue(tagCountsBalance(html, "div"));
        assertTrue(tagCountsBalance(html, "section"));
    }

    @Test
    void handlesNoChangesGracefully() {
        // Compare a release against itself - everything should be UNCHANGED, no crashes.
        ReleaseReport report = queryService.buildReleaseReport("24.2", "24.2");
        String html = ReleaseReportRenderer.render(report);

        assertEquals(0, report.changedCount());
        assertEquals(0, report.newCount());
        assertEquals(0, report.removedCount());
        assertTrue(html.contains("No screens changed"));
    }

    @Test
    void reviewStatusShowsUpInTheRenderedReport() {
        var loginChange = queryService.buildReleaseReport("24.1", "24.2").changes().stream()
                .filter(c -> c.fileName().equals("login.png")).findFirst().orElseThrow();

        queryService.acknowledge(loginChange.fromId(), loginChange.toId(),
                com.screenshots.catalog.model.ChangeStatus.APPROVED, "known redesign", "alice");

        ReleaseReport report = queryService.buildReleaseReport("24.1", "24.2");
        String html = ReleaseReportRenderer.render(report);

        assertTrue(html.contains("approved"));
        assertTrue(html.contains("known redesign"));
        // Anything never explicitly reviewed still renders as pending, not silently omitted.
        assertTrue(html.contains("pending review"));
    }

    private boolean tagCountsBalance(String html, String tag) {
        long opens = html.split("<" + tag + "[ >]", -1).length - 1;
        long closes = html.split("</" + tag + ">", -1).length - 1;
        return opens == closes;
    }
}
