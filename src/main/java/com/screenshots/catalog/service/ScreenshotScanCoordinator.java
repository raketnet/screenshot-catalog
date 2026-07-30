package com.screenshots.catalog.service;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Runs scans on a single dedicated background thread, so POST /api/scan can
 * return immediately instead of blocking the caller's HTTP request for the
 * whole scan duration. The rest of the app keeps serving other requests
 * normally in the meantime regardless (Jetty already handles each request on
 * its own thread) - what this actually buys you is that the person who
 * *triggered* the scan (or the UI they're using) isn't stuck on one long
 * blocking call, and can poll for progress instead.
 *
 * Deliberately single-threaded and guarded against overlapping scans:
 * ScreenshotScanService's get-or-create hierarchy and rescan cleanup were
 * both designed assuming only one scan runs at a time, with no locking -
 * running two concurrently could produce duplicate rows, or one scan's
 * cleanup deleting something a concurrent scan just imported.
 * tryStartScan() returns started=false if a scan is already running,
 * rather than silently queuing or racing.
 */
public class ScreenshotScanCoordinator {

    public enum Status { IDLE, RUNNING, COMPLETED, FAILED }

    public record ProgressSnapshot(
            Status status,
            String rootPath,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            String currentRelease,
            String currentComponent,
            int filesScanned,
            int imported,
            int skipped,
            ScreenshotScanService.ScanResult result,
            String errorMessage
    ) {
        static ProgressSnapshot idle() {
            return new ProgressSnapshot(Status.IDLE, null, null, null, null, null, 0, 0, 0, null, null);
        }
    }

    public record StartResult(boolean started, String reason) {
    }

    private final ScreenshotScanService scanService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "screenshot-scan");
        t.setDaemon(true); // never blocks JVM shutdown on a stuck scan
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ProgressSnapshot snapshot = ProgressSnapshot.idle();

    public ScreenshotScanCoordinator(ScreenshotScanService scanService) {
        this.scanService = scanService;
    }

    public StartResult tryStartScan(Path rootPath) {
        if (!running.compareAndSet(false, true)) {
            return new StartResult(false, "A scan is already in progress - check GET /api/scan/status.");
        }

        snapshot = new ProgressSnapshot(Status.RUNNING, rootPath.toString(), LocalDateTime.now(), null,
                null, null, 0, 0, 0, null, null);

        executor.submit(() -> {
            try {
                scanService.scanRepository(rootPath, new ScanProgressSink() {
                    @Override
                    public void onStarted(String root) {
                        // already captured in the snapshot set just above
                    }

                    @Override
                    public void onRelease(String releaseName) {
                        updateSnapshot(s -> withProgress(s, releaseName, null, s.filesScanned(), s.imported(), s.skipped()));
                    }

                    @Override
                    public void onComponent(String releaseName, String componentName) {
                        updateSnapshot(s -> withProgress(s, releaseName, componentName, s.filesScanned(), s.imported(), s.skipped()));
                    }

                    @Override
                    public void onFileProcessed(int scanned, int imported, int skipped) {
                        updateSnapshot(s -> withProgress(s, s.currentRelease(), s.currentComponent(), scanned, imported, skipped));
                    }

                    @Override
                    public void onFinished(ScreenshotScanService.ScanResult result) {
                        updateSnapshot(s -> new ProgressSnapshot(Status.COMPLETED, s.rootPath(), s.startedAt(), LocalDateTime.now(),
                                s.currentRelease(), s.currentComponent(), result.filesScanned(), result.imported(), result.skipped(),
                                result, null));
                    }

                    @Override
                    public void onFailed(Exception e) {
                        updateSnapshot(s -> new ProgressSnapshot(Status.FAILED, s.rootPath(), s.startedAt(), LocalDateTime.now(),
                                s.currentRelease(), s.currentComponent(), s.filesScanned(), s.imported(), s.skipped(),
                                null, e.getMessage()));
                    }
                });
            } catch (Exception e) {
                updateSnapshot(s -> new ProgressSnapshot(Status.FAILED, s.rootPath(), s.startedAt(), LocalDateTime.now(),
                        s.currentRelease(), s.currentComponent(), s.filesScanned(), s.imported(), s.skipped(),
                        null, e.getMessage()));
            } finally {
                running.set(false);
            }
        });

        return new StartResult(true, null);
    }

    public ProgressSnapshot currentStatus() {
        return snapshot;
    }

    public boolean isRunning() {
        return running.get();
    }

    private static ProgressSnapshot withProgress(ProgressSnapshot s, String release, String component, int scanned, int imported, int skipped) {
        return new ProgressSnapshot(s.status(), s.rootPath(), s.startedAt(), s.finishedAt(),
                release, component, scanned, imported, skipped, s.result(), s.errorMessage());
    }

    private synchronized void updateSnapshot(Function<ProgressSnapshot, ProgressSnapshot> updater) {
        snapshot = updater.apply(snapshot);
    }
}
