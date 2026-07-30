package com.screenshots.catalog.repository;

import com.screenshots.catalog.db.DatabaseConfig;
import com.screenshots.catalog.dto.ReleaseCompareResult;
import com.screenshots.catalog.dto.ScreenshotChange;
import com.screenshots.catalog.model.ChangeAcknowledgment;
import com.screenshots.catalog.model.ChangeStatus;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChangeAcknowledgmentTest {

    private ScreenshotRepository repository;
    private ScreenshotQueryService queryService;

    @BeforeEach
    void setUp() throws IOException {
        String jdbcUrl = "jdbc:h2:mem:test-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        DatabaseConfig db = new DatabaseConfig(jdbcUrl, "sa", "");
        repository = new ScreenshotRepository(db.dataSource());
        ImageDiffService diffService = new ImageDiffService();
        ScreenshotScanService scanService = new ScreenshotScanService(repository, diffService);
        queryService = new ScreenshotQueryService(repository, diffService);

        Path repoRoot = Files.createTempDirectory("repo-ack-test");
        write(repoRoot, "Release_1.0/Auth/LoginDialog/login.png", Color.RED);
        write(repoRoot, "Release_2.0/Auth/LoginDialog/login.png", Color.GREEN); // changed
        write(repoRoot, "Release_2.0/Auth/NewWindow/new.png", Color.BLUE);      // new
        scanService.scanRepository(repoRoot);
    }

    private void write(Path root, String relPath, Color color) throws IOException {
        Path p = root.resolve(relPath);
        Files.createDirectories(p.getParent());
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 32, 32);
        g.dispose();
        ImageIO.write(img, "png", p.toFile());
    }

    @Test
    void unacknowledgedPairDefaultsToPending() {
        var status = queryService.reviewStatus(1L, 2L);
        assertEquals(ChangeStatus.PENDING, status.status());
    }

    @Test
    void approvingPersistsAndIsRetrievable() {
        queryService.acknowledge(1L, 2L, ChangeStatus.APPROVED, "intentional redesign", "alice");
        var status = queryService.reviewStatus(1L, 2L);
        assertEquals(ChangeStatus.APPROVED, status.status());
        assertEquals("intentional redesign", status.note());
        assertEquals("alice", status.acknowledgedBy());
        assertNotNull(status.acknowledgedAt());
    }

    @Test
    void reAcknowledgingOverwritesThePreviousDecision() {
        queryService.acknowledge(1L, 2L, ChangeStatus.APPROVED, "looks fine", "alice");
        queryService.acknowledge(1L, 2L, ChangeStatus.REJECTED, "actually a bug", "bob");

        var status = queryService.reviewStatus(1L, 2L);
        assertEquals(ChangeStatus.REJECTED, status.status());
        assertEquals("actually a bug", status.note());
        assertEquals("bob", status.acknowledgedBy());

        // still exactly one row for this pair, not two
        assertEquals(1, repository.findAllAcknowledgmentsByPairKey().size());
    }

    @Test
    void oneSidedPairsWorkForNewAndRemovedScreenshots() {
        Screenshot newWindowShot = repository.search(new ScreenshotRepository.SearchFilters(
                "2.0", "Auth", "NewWindow", null, null, null, null, null, null, null, null, null, null, null)).get(0);

        // NEW screenshot: fromId null, toId set
        queryService.acknowledge(null, newWindowShot.id(), ChangeStatus.APPROVED, "expected new screen", "alice");
        assertEquals(ChangeStatus.APPROVED, queryService.reviewStatus(null, newWindowShot.id()).status());
        assertEquals(ChangeStatus.PENDING, queryService.reviewStatus(null, newWindowShot.id() + 1).status()); // different real-ish id, unaffected
    }

    @Test
    void rejectsWhenBothIdsAreNull() {
        assertThrows(IllegalArgumentException.class, () ->
                queryService.acknowledge(null, null, ChangeStatus.APPROVED, null, null));
    }

    @Test
    void compareReleasesAttachesStatusToMatchingChangeAndDefaultsRestToPending() {
        Screenshot login1 = repository.search(new ScreenshotRepository.SearchFilters(
                "1.0", "Auth", "LoginDialog", null, null, null, null, null, null, null, null, null, null, null)).get(0);
        Screenshot login2 = repository.search(new ScreenshotRepository.SearchFilters(
                "2.0", "Auth", "LoginDialog", null, null, null, null, null, null, null, null, null, null, null)).get(0);

        queryService.acknowledge(login1.id(), login2.id(), ChangeStatus.APPROVED, "known change", "alice");

        ReleaseCompareResult result = queryService.compareReleases("1.0", "2.0");
        ScreenshotChange loginChange = result.changes().stream()
                .filter(c -> c.fileName().equals("login.png")).findFirst().orElseThrow();
        assertEquals(ChangeStatus.APPROVED, loginChange.status());
        assertEquals("known change", loginChange.reviewNote());

        ScreenshotChange newWindowChange = result.changes().stream()
                .filter(c -> c.changeType() == ScreenshotChange.ChangeType.NEW).findFirst().orElseThrow();
        assertEquals(ChangeStatus.PENDING, newWindowChange.status()); // never acknowledged - defaults correctly
    }

    @Test
    void statusFilterNarrowsBothTheListAndTheCounts() {
        Screenshot login1 = repository.search(new ScreenshotRepository.SearchFilters(
                "1.0", "Auth", "LoginDialog", null, null, null, null, null, null, null, null, null, null, null)).get(0);
        Screenshot login2 = repository.search(new ScreenshotRepository.SearchFilters(
                "2.0", "Auth", "LoginDialog", null, null, null, null, null, null, null, null, null, null, null)).get(0);
        queryService.acknowledge(login1.id(), login2.id(), ChangeStatus.APPROVED, null, "alice");

        // Unfiltered: 1 changed (approved) + 1 new (pending) = 2 total changes
        ReleaseCompareResult all = queryService.compareReleases("1.0", "2.0", (ChangeStatus) null, 100, 0);
        assertEquals(2, all.changesTotal());

        // Filtered to PENDING: only the NEW window, not the approved login change
        ReleaseCompareResult pendingOnly = queryService.compareReleases("1.0", "2.0", ChangeStatus.PENDING, 100, 0);
        assertEquals(1, pendingOnly.changesTotal());
        assertEquals(1, pendingOnly.newCount());
        assertEquals(0, pendingOnly.changedCount()); // the approved one is filtered out
        assertTrue(pendingOnly.changes().stream().allMatch(c -> c.status() == ChangeStatus.PENDING));

        // Filtered to APPROVED: only the login change
        ReleaseCompareResult approvedOnly = queryService.compareReleases("1.0", "2.0", ChangeStatus.APPROVED, 100, 0);
        assertEquals(1, approvedOnly.changesTotal());
        assertEquals(1, approvedOnly.changedCount());
        assertEquals(0, approvedOnly.newCount());
    }
}
