package com.screenshots.catalog.service;

import com.screenshots.catalog.db.DatabaseConfig;
import com.screenshots.catalog.repository.ScreenshotRepository;
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

class ScreenshotScanCoordinatorTest {

    private ScreenshotScanCoordinator coordinator;
    private Path repoRoot;

    @BeforeEach
    void setUp() throws IOException {
        String jdbcUrl = "jdbc:h2:mem:test-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        DatabaseConfig db = new DatabaseConfig(jdbcUrl, "sa", "");
        ScreenshotRepository repository = new ScreenshotRepository(db.dataSource());
        ScreenshotScanService scanService = new ScreenshotScanService(repository, new ImageDiffService());
        coordinator = new ScreenshotScanCoordinator(scanService);

        // Large-ish fixture (a few hundred tiny files) so the scan takes long enough that an
        // immediate second tryStartScan() call reliably lands while the first is still running -
        // with a near-instant scan there's a real race where the background thread could finish
        // before the test even makes its second call.
        repoRoot = Files.createTempDirectory("coordinator-test");
        for (int release = 0; release < 3; release++) {
            for (int window = 0; window < 60; window++) {
                Path p = repoRoot.resolve("Release_%d.0/Auth/Window%d/shot.png".formatted(release, window));
                Files.createDirectories(p.getParent());
                writeSolidPng(p, new Color(window % 255, release * 50, 100));
            }
        }
    }

    private void writeSolidPng(Path path, Color color) throws IOException {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 16, 16);
        g.dispose();
        ImageIO.write(img, "png", path.toFile());
    }

    @Test
    void startsIdleAndReportsIdleStatus() {
        assertEquals(ScreenshotScanCoordinator.Status.IDLE, coordinator.currentStatus().status());
        assertFalse(coordinator.isRunning());
    }

    @Test
    void firstScanStartsSuccessfully() {
        var result = coordinator.tryStartScan(repoRoot);
        assertTrue(result.started());
        assertNull(result.reason());
    }

    @Test
    void overlappingScanIsRejectedNotQueuedOrRaced() throws InterruptedException {
        var first = coordinator.tryStartScan(repoRoot);
        assertTrue(first.started());

        // Immediately, same thread, before the background scan has any real chance to finish -
        // running is set synchronously inside tryStartScan() before the work is handed to the
        // background thread, so this is deterministic regardless of how fast the scan itself is.
        var second = coordinator.tryStartScan(repoRoot);
        assertFalse(second.started());
        assertNotNull(second.reason());
        assertTrue(second.reason().toLowerCase().contains("already in progress"));

        waitForCompletion(); // let the first scan finish before the test ends, so it doesn't keep
                              // running in the background and interleave its log output with
                              // whatever test runs next
    }

    @Test
    void scanEventuallyCompletesWithCorrectCountsAndAllowsANewScanAfterward() throws InterruptedException {
        coordinator.tryStartScan(repoRoot);

        var finalSnapshot = waitForCompletion();

        assertEquals(ScreenshotScanCoordinator.Status.COMPLETED, finalSnapshot.status());
        assertNotNull(finalSnapshot.result());
        assertEquals(180, finalSnapshot.result().imported()); // 3 releases * 60 windows * 1 file
        assertEquals(0, finalSnapshot.result().skipped());
        assertNotNull(finalSnapshot.startedAt());
        assertNotNull(finalSnapshot.finishedAt());
        assertFalse(coordinator.isRunning());

        // The guard must release after completion - a fresh scan can start.
        var again = coordinator.tryStartScan(repoRoot);
        assertTrue(again.started());
        var secondSnapshot = waitForCompletion(); // await it too, so this test doesn't leave an orphaned
                                                    // background scan whose log output interleaves with
                                                    // whatever test runs next
        assertEquals(ScreenshotScanCoordinator.Status.COMPLETED, secondSnapshot.status());
        assertEquals(180, secondSnapshot.result().imported());
        assertEquals(0, secondSnapshot.result().skipped());
    }

    @Test
    void progressSnapshotUpdatesWhileRunning() throws InterruptedException {
        coordinator.tryStartScan(repoRoot);

        // Poll briefly for any sign of progress (release/component name or a nonzero file count) -
        // don't assert on an exact value, just that the snapshot is actually being updated live
        // rather than staying blank until the very end.
        boolean sawProgress = false;
        for (int i = 0; i < 200 && !sawProgress; i++) {
            var snap = coordinator.currentStatus();
            if (snap.currentRelease() != null || snap.filesScanned() > 0 || snap.status() == ScreenshotScanCoordinator.Status.COMPLETED) {
                sawProgress = true;
            } else {
                Thread.sleep(5);
            }
        }
        assertTrue(sawProgress, "Expected the progress snapshot to update before the scan finished");

        waitForCompletion();
    }

    private ScreenshotScanCoordinator.ProgressSnapshot waitForCompletion() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            var snap = coordinator.currentStatus();
            if (snap.status() == ScreenshotScanCoordinator.Status.COMPLETED
                    || snap.status() == ScreenshotScanCoordinator.Status.FAILED) {
                return snap;
            }
            Thread.sleep(10);
        }
        fail("Scan did not complete within 30s");
        return null;
    }
}
