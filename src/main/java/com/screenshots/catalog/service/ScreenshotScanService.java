package com.screenshots.catalog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.screenshots.catalog.dto.SidecarMetadata;
import com.screenshots.catalog.model.Screenshot;
import com.screenshots.catalog.model.SwingComponentInfo;
import com.screenshots.catalog.model.SwingMetadata;
import com.screenshots.catalog.repository.ScreenshotRepository;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

/**
 * Walks a repository laid out as:
 *   Repository/Release_24.1/Authentication/LoginDialog/login.png
 *   Repository/Release_24.1/Authentication/LoginDialog/login_dark.png
 *   Repository/Release_24.1/Alarm/AlarmOverview/...
 *
 * Release/Component/Window come entirely from the folder tree - there is no
 * filename convention to parse. The "Release_" prefix is stripped from the
 * top-level folder name if present (Release_24.1 -> "24.1").
 *
 * Image files sitting directly in a component folder (no window
 * subdirectory) are cataloged too, under a synthetic window named
 * ROOT_WINDOW_NAME ("(root)") - this used to be silently dropped (a
 * non-directory entry where a window was expected got filtered out with no
 * indication why, the same class of bug symlinks had before that was fixed).
 *
 * File name is used two ways: as-is for display/search, and heuristically
 * split on the last underscore for an optional "variant" tag (login_dark.png
 * -> variant "dark").
 *
 * Optional Swing metadata: if a sidecar JSON file with the same base name
 * exists next to a screenshot (login.png -> login.json), it's parsed and
 * linked to the screenshot - window class, theme, PLAF version, locale,
 * screen resolution, and the list of Swing components present. See
 * SidecarMetadata for the exact shape. No sidecar -> no metadata, screenshot
 * still imports normally.
 *
 * Re-running a scan is safe and self-cleaning: for every window directory
 * actually visited this scan, any screenshot previously cataloged in that
 * window whose file wasn't seen this time is removed (cascades to its
 * metadata/components). If a whole window/component/release folder is
 * deleted from disk, its old rows are left behind since that window is
 * never visited to trigger cleanup - a known limitation, not a bug.
 *
 * Symlinks (directories or files) are never followed - rejected outright
 * during the walk, with a warning added to the scan result explaining why
 * (previously this was silent, which made a symlinked window/component
 * directory indistinguishable from a genuinely empty one - a real, reported
 * source of confusion). Otherwise a symlink inside the scanned tree could
 * pull in and later serve, via /api/images/{id}, files from outside the
 * configured allowed scan roots entirely (see ScanPathValidator, which
 * separately checks the root itself).
 *
 * The whole scan runs against a single reused database connection
 * (ScreenshotRepository.ScanSession), not one connection per get-or-create/
 * upsert call. Opening a fresh physical connection for every single small
 * operation was real, measurable overhead in practice - especially with the
 * underlying H2 file on a network share or VM-mounted folder, where a scan
 * of just over a hundred files was observed taking the better part of an
 * hour before this change.
 *
 * Progress is logged to stdout as the scan runs (release/component/window
 * discovered, each file imported/updated/skipped, periodic counters) and
 * also tracked in memory (see ScreenshotScanCoordinator) so a caller can
 * poll progress instead of only watching the server console.
 */
public class ScreenshotScanService {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "bmp", "webp");
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int PROGRESS_EVERY = 50;
    public static final String ROOT_WINDOW_NAME = "(root)";

    private final ScreenshotRepository repository;
    private final ObjectMapper mapper = new ObjectMapper();

    public ScreenshotScanService(ScreenshotRepository repository, ImageDiffService imageDiffService) {
        this.repository = repository;
        // imageDiffService currently unused by the scanner (reserved for future scan-time near-dup flagging)
    }

    public record ScanResult(
            int filesScanned, int imported, int skipped, int removedMissing,
            List<String> warnings, Map<String, Integer> perRelease
    ) {
    }

    public ScanResult scanRepository(Path repositoryRoot) throws IOException {
        return scanRepository(repositoryRoot, null);
    }

    /**
     * @param progress optional - if given, updated live as the scan runs so a caller (e.g. a
     *                 background job polling endpoint) can report progress without waiting for
     *                 this method to return. Safe to pass null for a plain synchronous scan.
     */
    public ScanResult scanRepository(Path repositoryRoot, ScanProgressSink progress) throws IOException {
        if (!Files.isDirectory(repositoryRoot)) {
            throw new IllegalArgumentException("Not a directory: " + repositoryRoot);
        }

        long startedAt = System.currentTimeMillis();
        log("Scan started: " + repositoryRoot);
        if (progress != null) progress.onStarted(repositoryRoot.toString());

        ScanState state = new ScanState();

        try (ScreenshotRepository.ScanSession session = repository.openScanSession()) {
            try (Stream<Path> releaseEntries = Files.list(repositoryRoot)) {
                for (Path releaseDir : realDirectories(releaseEntries, "release", state.warnings)) {
                    String releaseName = stripReleasePrefix(releaseDir.getFileName().toString());
                    log("Release '" + releaseName + "'");
                    if (progress != null) progress.onRelease(releaseName);
                    long releaseId = session.getOrCreateRelease(releaseName);
                    int releaseCount = 0;

                    try (Stream<Path> componentEntries = Files.list(releaseDir)) {
                        for (Path componentDir : realDirectories(componentEntries, "component", state.warnings)) {
                            String componentName = componentDir.getFileName().toString();
                            log("  Component '" + componentName + "'");
                            if (progress != null) progress.onComponent(releaseName, componentName);
                            long componentId = session.getOrCreateComponent(releaseId, componentName);

                            // A component folder's children are a mix of window subdirectories (the normal
                            // case) and, sometimes, image files sitting directly in the component root with
                            // no window subfolder at all.
                            List<Path> windowDirs = new ArrayList<>();
                            List<Path> rootLevelImages = new ArrayList<>();
                            try (Stream<Path> componentChildren = Files.list(componentDir)) {
                                for (Path p : componentChildren.toList()) {
                                    if (Files.isSymbolicLink(p)) {
                                        String msg = "Skipped component-level entry '%s': it's a symlink, which is never followed."
                                                .formatted(p);
                                        state.warnings.add(msg);
                                        log("  ! " + msg);
                                    } else if (Files.isDirectory(p)) {
                                        windowDirs.add(p);
                                    } else if (isRealFile(p) && hasImageExtension(p)) {
                                        rootLevelImages.add(p);
                                    }
                                    // non-image plain files (e.g. a stray .txt) are ignored - only a sidecar
                                    // .json next to a specific image is meaningful
                                }
                            }

                            for (Path windowDir : windowDirs) {
                                String windowName = windowDir.getFileName().toString();
                                long windowId = session.getOrCreateWindow(componentId, windowName);
                                state.visitedWindowIds.add(windowId);

                                try (Stream<Path> files = Files.list(windowDir)) {
                                    List<Path> imageFiles = files.filter(ScreenshotScanService::isRealFile)
                                            .filter(ScreenshotScanService::hasImageExtension)
                                            .toList();
                                    log("    Window '" + windowName + "' - " + imageFiles.size() + " image file(s)");
                                    releaseCount += importImages(session, windowId, imageFiles, state, progress);
                                }
                            }

                            if (!rootLevelImages.isEmpty()) {
                                log("    " + rootLevelImages.size() + " image file(s) directly in component root - "
                                        + "cataloging under synthetic window '" + ROOT_WINDOW_NAME + "'");
                                state.warnings.add(("Component '%s' has %d image file(s) with no window subfolder - " +
                                        "cataloged under a synthetic window named '%s'. Put them in a Component/WindowName/ " +
                                        "subfolder if you want a real window name instead.")
                                        .formatted(componentName, rootLevelImages.size(), ROOT_WINDOW_NAME));
                                long rootWindowId = session.getOrCreateWindow(componentId, ROOT_WINDOW_NAME);
                                state.visitedWindowIds.add(rootWindowId);
                                releaseCount += importImages(session, rootWindowId, rootLevelImages, state, progress);
                            }
                        }
                    }
                    state.perRelease.merge(releaseName, releaseCount, Integer::sum);
                }
            }

            int removedMissing = session.deleteScreenshotsInWindowsNotIn(state.visitedWindowIds, state.scannedFilePaths);

            double elapsedSeconds = (System.currentTimeMillis() - startedAt) / 1000.0;
            log("Scan finished in %.1fs: scanned=%d imported=%d skipped=%d removedMissing=%d"
                    .formatted(elapsedSeconds, state.scanned, state.imported, state.skipped, removedMissing));
            if (!state.warnings.isEmpty()) {
                log("%d warning(s) - see the scan response's `warnings` field for the full list".formatted(state.warnings.size()));
            }

            ScanResult result = new ScanResult(state.scanned, state.imported, state.skipped, removedMissing, state.warnings, state.perRelease);
            if (progress != null) progress.onFinished(result);
            return result;
        } catch (RuntimeException e) {
            if (progress != null) progress.onFailed(e);
            throw e;
        }
    }

    /** Mutable accumulators threaded through one scanRepository() call - kept together so helper methods don't need a long parameter list. */
    private static final class ScanState {
        int scanned = 0, imported = 0, skipped = 0;
        final List<String> warnings = new ArrayList<>();
        final Map<String, Integer> perRelease = new HashMap<>();
        final Set<String> scannedFilePaths = new HashSet<>();
        final Set<Long> visitedWindowIds = new HashSet<>();
    }

    /** Imports every file in imageFiles into windowId, updating state's counters/log as it goes. @return how many were successfully imported. */
    private int importImages(ScreenshotRepository.ScanSession session, long windowId, List<Path> imageFiles,
                              ScanState state, ScanProgressSink progress) {
        int importedHere = 0;
        for (Path file : imageFiles) {
            state.scanned++;
            try {
                boolean existedBefore = session.findByFilePath(file.toAbsolutePath().toString()).isPresent();
                Screenshot s = importFile(session, windowId, file);
                state.scannedFilePaths.add(s.filePath());
                state.imported++;
                importedHere++;
                log("      %s %s".formatted(existedBefore ? "~ updated" : "+ new    ", s.fileName()));
            } catch (Exception e) {
                state.skipped++;
                String msg = "Skipped (could not import %s: %s)".formatted(file, e.getMessage());
                state.warnings.add(msg);
                log("      ! " + msg);
            }
            if (progress != null) progress.onFileProcessed(state.scanned, state.imported, state.skipped);
            if (state.scanned % PROGRESS_EVERY == 0) {
                log("... " + state.scanned + " files scanned so far (" + state.imported + " imported, " + state.skipped + " skipped)");
            }
        }
        return importedHere;
    }

    private Screenshot importFile(ScreenshotRepository.ScanSession session, long windowId, Path file) throws IOException, NoSuchAlgorithmException {
        byte[] bytes = Files.readAllBytes(file);
        String sha256 = sha256Hex(bytes);

        BufferedImage image = ImageIO.read(file.toFile());
        if (image == null) {
            throw new IOException("Could not decode image");
        }
        long pHash = PerceptualHash.compute(image);

        String fileName = file.getFileName().toString();
        String variant = extractVariant(fileName);

        LocalDateTime capturedAt = LocalDateTime.ofInstant(
                Files.getLastModifiedTime(file).toInstant(), ZoneId.systemDefault());

        Screenshot screenshot = session.upsert(new ScreenshotRepository.NewScreenshot(
                windowId, fileName, file.toAbsolutePath().toString(), variant,
                image.getWidth(), image.getHeight(), bytes.length, sha256, pHash, capturedAt
        ));

        importSidecarIfPresent(session, screenshot, file);
        return screenshot;
    }

    /** login.png -> login.json in the same folder, if it exists. */
    private void importSidecarIfPresent(ScreenshotRepository.ScanSession session, Screenshot screenshot, Path imageFile) {
        String fileName = imageFile.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        Path sidecar = imageFile.resolveSibling(base + ".json");

        if (!Files.exists(sidecar)) return;

        try {
            SidecarMetadata parsed = mapper.readValue(sidecar.toFile(), SidecarMetadata.class);

            session.upsertMetadata(screenshot.id(), new SwingMetadata(
                    parsed.windowClass(), parsed.theme(), parsed.plafVersion(), parsed.locale(),
                    parsed.screenWidth(), parsed.screenHeight()
            ));

            List<SwingComponentInfo> components = parsed.components() == null
                    ? List.of()
                    : parsed.components().stream()
                        .map(e -> new SwingComponentInfo(e.componentId(), e.swingClass()))
                        .toList();
            session.replaceComponents(screenshot.id(), components);
        } catch (IOException e) {
            // Malformed sidecar shouldn't fail the whole import - the screenshot itself is still valid.
            System.err.println("Could not parse sidecar metadata " + sidecar + ": " + e.getMessage());
        }
    }

    /** "Release_24.1" -> "24.1"; a folder with no such prefix is used as-is. */
    private static String stripReleasePrefix(String folderName) {
        return folderName.regionMatches(true, 0, "Release_", 0, "Release_".length())
                ? folderName.substring("Release_".length())
                : folderName;
    }

    private static boolean hasImageExtension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        return IMAGE_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase());
    }

    /**
     * Lists the real (non-symlink) subdirectories of a stream of entries,
     * adding a warning (and printing it immediately, not just at the end)
     * for anything skipped - a symlinked directory in particular, since
     * that's silent-by-default otherwise and genuinely confusing to debug
     * (a component/window that "should" have content shows up empty with no
     * indication why). See the class doc for why symlinks are rejected
     * rather than followed.
     */
    private static List<Path> realDirectories(Stream<Path> entries, String levelLabel, List<String> warnings) {
        List<Path> result = new ArrayList<>();
        for (Path p : entries.toList()) {
            if (Files.isSymbolicLink(p)) {
                String msg = "Skipped %s '%s': it's a symlink, which is never followed (see README - symlinks are rejected to prevent scanning outside the allowed root). If this should have content, replace the symlink with a real directory or copy of its target."
                        .formatted(levelLabel, p);
                warnings.add(msg);
                log("  ! " + msg);
            } else if (Files.isDirectory(p)) {
                result.add(p);
            }
            // plain files sitting at a release level (not expected by the convention) are ignored silently
        }
        return result;
    }

    private static boolean isRealFile(Path p) {
        return !Files.isSymbolicLink(p) && Files.isRegularFile(p);
    }

    /** login_dark.png -> "dark"; login.png -> null. Heuristic, not a strict format. */
    private static String extractVariant(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        int underscore = base.lastIndexOf('_');
        if (underscore < 0 || underscore == base.length() - 1) return null;
        return base.substring(underscore + 1);
    }

    private static String sha256Hex(byte[] bytes) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static void log(String message) {
        System.out.println("%s  [scan] %s".formatted(LocalDateTime.now().format(LOG_TIME), message));
    }
}
