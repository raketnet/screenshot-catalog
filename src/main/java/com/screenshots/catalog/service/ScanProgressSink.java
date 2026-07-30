package com.screenshots.catalog.service;

/**
 * Callback interface a scan reports its progress through, so a caller (in
 * practice, ScreenshotScanCoordinator running the scan on a background
 * thread) can expose live status without waiting for the whole scan to
 * finish. Optional - scanRepository(path) with no sink runs exactly as
 * before, purely synchronous.
 */
public interface ScanProgressSink {
    void onStarted(String rootPath);
    void onRelease(String releaseName);
    void onComponent(String releaseName, String componentName);
    void onFileProcessed(int scanned, int imported, int skipped);
    void onFinished(ScreenshotScanService.ScanResult result);
    void onFailed(Exception e);
}
