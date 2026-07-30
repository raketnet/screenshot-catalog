package com.screenshots.catalog.repository;

import com.screenshots.catalog.db.DatabaseConfig;
import com.screenshots.catalog.dto.ReleaseCompareResult;
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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PaginationTest {

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

        Path repoRoot = Files.createTempDirectory("repo-pagination-test");
        // 25 distinct windows, one screenshot each, all in one release/component - plenty to page through.
        for (int i = 0; i < 25; i++) {
            Path windowDir = repoRoot.resolve("Release_1.0/Auth/Window" + String.format("%02d", i));
            Files.createDirectories(windowDir);
            writeSolidPng(windowDir.resolve("shot.png"), new Color(i * 7 % 255, 100, 150));
        }
        scanService.scanRepository(repoRoot);
    }

    private void writeSolidPng(Path path, Color color) throws IOException {
        BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 32, 32);
        g.dispose();
        ImageIO.write(img, "png", path.toFile());
    }

    @Test
    void searchPagedReturnsCorrectTotalAndPageSize() {
        var page1 = repository.searchPaged(
                new ScreenshotRepository.SearchFilters("1.0", null, null, null, null, null, null, null, null, null, null, null, null, null),
                10, 0);

        assertEquals(25, page1.total());
        assertEquals(10, page1.items().size());
        assertEquals(10, page1.limit());
        assertEquals(0, page1.offset());
    }

    @Test
    void searchPagedPagesCoverEveryResultExactlyOnce() {
        Set<Long> seenIds = new HashSet<>();
        int offset = 0;
        int pageSize = 7;
        int total = -1;

        while (true) {
            var page = repository.searchPaged(
                    new ScreenshotRepository.SearchFilters("1.0", null, null, null, null, null, null, null, null, null, null, null, null, null),
                    pageSize, offset);
            total = page.total();
            if (page.items().isEmpty()) break;
            for (Screenshot s : page.items()) {
                assertTrue(seenIds.add(s.id()), "id " + s.id() + " returned on more than one page");
            }
            offset += pageSize;
            if (offset > total + pageSize) fail("pagination never terminated - possible infinite loop");
        }

        assertEquals(25, total);
        assertEquals(25, seenIds.size());
    }

    @Test
    void compareReleasesPagedKeepsFullCountsButPagesTheChangesList() throws IOException {
        // Second release: same 25 windows, all changed (different color).
        Path repoRoot2 = Files.createTempDirectory("repo-pagination-test-2");
        for (int i = 0; i < 25; i++) {
            Path windowDir = repoRoot2.resolve("Release_1.0/Auth/Window" + String.format("%02d", i));
            Files.createDirectories(windowDir);
            writeSolidPng(windowDir.resolve("shot.png"), new Color(i * 7 % 255, 100, 150));
        }
        Path repoRoot3 = Files.createTempDirectory("repo-pagination-test-3");
        for (int i = 0; i < 25; i++) {
            Path windowDir = repoRoot3.resolve("Release_2.0/Auth/Window" + String.format("%02d", i));
            Files.createDirectories(windowDir);
            writeSolidPng(windowDir.resolve("shot.png"), new Color(255 - (i * 7 % 255), 50, 200));
        }
        var diffService = new ImageDiffService();
        var scanService2 = new ScreenshotScanService(repository, diffService);
        scanService2.scanRepository(repoRoot3);

        ReleaseCompareResult fullPage = queryService.compareReleases("1.0", "2.0", 5, 0);
        assertEquals(25, fullPage.changesTotal());
        assertEquals(5, fullPage.changes().size());
        assertEquals(25, fullPage.changedCount()); // full count, not page-scoped

        ReleaseCompareResult secondPage = queryService.compareReleases("1.0", "2.0", 5, 5);
        assertEquals(5, secondPage.changes().size());
        // Pages don't overlap.
        var firstIds = fullPage.changes().stream().map(c -> c.toId()).toList();
        var secondIds = secondPage.changes().stream().map(c -> c.toId()).toList();
        assertTrue(firstIds.stream().noneMatch(secondIds::contains));
    }
}
