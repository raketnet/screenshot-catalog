package com.screenshots.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.screenshots.catalog.config.AppConfig;
import com.screenshots.catalog.db.DatabaseConfig;
import com.screenshots.catalog.dto.AcknowledgeRequest;
import com.screenshots.catalog.dto.ErrorResponse;
import com.screenshots.catalog.dto.ScanRequest;
import com.screenshots.catalog.dto.ScanStartedResponse;
import com.screenshots.catalog.model.ChangeStatus;
import com.screenshots.catalog.model.Screenshot;
import com.screenshots.catalog.repository.ScreenshotRepository;
import com.screenshots.catalog.security.RateLimiter;
import com.screenshots.catalog.security.ScanPathValidator;
import com.screenshots.catalog.service.ImageDiffService;
import com.screenshots.catalog.service.ReleaseReportRenderer;
import com.screenshots.catalog.service.ScreenshotQueryService;
import com.screenshots.catalog.service.ScreenshotScanCoordinator;
import com.screenshots.catalog.service.ScreenshotScanService;
import com.screenshots.catalog.service.ThumbnailService;
import io.javalin.Javalin;
import io.javalin.http.ContentType;
import io.javalin.json.JavalinJackson;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class App {

    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void main(String[] args) {
        AppConfig config = AppConfig.fromEnv();

        DatabaseConfig db = new DatabaseConfig(config.jdbcUrl(), config.dbUser(), config.dbPassword());
        ScreenshotRepository repository = new ScreenshotRepository(db.dataSource());
        ImageDiffService imageDiffService = new ImageDiffService();
        ThumbnailService thumbnailService = new ThumbnailService();
        ScreenshotScanService scanService = new ScreenshotScanService(repository, imageDiffService);
        ScreenshotScanCoordinator scanCoordinator = new ScreenshotScanCoordinator(scanService);
        ScreenshotQueryService queryService = new ScreenshotQueryService(repository, imageDiffService);
        ScanPathValidator scanPathValidator = new ScanPathValidator(config.allowedScanRoots());
        RateLimiter rateLimiter = new RateLimiter(config.rateLimitPerMinute());

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

        Javalin app = Javalin.create(cfg -> {
            cfg.jsonMapper(new JavalinJackson(mapper, true));
            // Everything static (built React UI, openapi.yaml, swagger-ui.html) lives together
            // under resources/public and is served at "/" via a single mapping - deliberately
            // just one staticFiles registration, not two, to avoid any ambiguity between static
            // resource resolution and the /api/* routes below.
            cfg.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/";
                staticFiles.directory = "/public";
                staticFiles.location = io.javalin.http.staticfiles.Location.CLASSPATH;
            });

            // CORS: same-origin requests (the normal "java -jar, open localhost:PORT" workflow)
            // are never subject to CORS at all, so this only matters for cross-origin callers -
            // e.g. running `npm run dev` against a real backend, or a separately hosted UI.
            // Explicit allowlist, not anyHost(), plus whatever SCREENSHOT_ALLOWED_ORIGINS adds.
            List<String> allowedOrigins = new ArrayList<>(List.of(
                    "http://localhost:" + config.port(),
                    "http://127.0.0.1:" + config.port(),
                    "http://localhost:5173",   // Vite dev server default
                    "http://127.0.0.1:5173"
            ));
            allowedOrigins.addAll(config.extraAllowedOrigins());
            String[] originsArray = allowedOrigins.toArray(new String[0]);
            String firstOrigin = originsArray[0];
            String[] remainingOrigins = java.util.Arrays.copyOfRange(originsArray, 1, originsArray.length);
            cfg.bundledPlugins.enableCors(cors -> cors.addRule(rule -> rule.allowHost(firstOrigin, remainingOrigins)));
        });

        // Audit log: every request, plus the requested path specifically for scans.
        app.before(ctx -> {
            String line = "%s  %-6s %s  from %s".formatted(
                    LocalDateTime.now().format(LOG_TIME), ctx.method(), ctx.path(), ctx.ip());
            System.out.println(line);
        });

        // Optional API key auth (SCREENSHOT_API_KEY). Off by default - if unset, every request
        // is allowed through, so local testing needs no extra setup. Only applies to /api/*;
        // the UI and static assets always load so the app is at least visible without a key.
        // Accepts the key via the X-API-Key header (normal API calls) OR a ?key= query param
        // (needed for plain <img src> / <a href> tags in the release report and the UI's image
        // loading, which can't attach headers).
        if (config.apiKey().isPresent()) {
            String requiredKey = config.apiKey().get();
            app.before("/api/*", ctx -> {
                String provided = ctx.header("X-API-Key");
                if (provided == null) provided = ctx.queryParam("key");
                if (provided == null || !constantTimeEquals(provided, requiredKey)) {
                    ctx.status(401).json(new ErrorResponse("Missing or invalid X-API-Key header (or key= query param)"));
                }
            });
            System.out.println("API key auth ENABLED - all /api/* requests require X-API-Key header or key= query param.");
        } else {
            System.out.println("API key auth disabled (SCREENSHOT_API_KEY not set) - fine for local testing, " +
                    "not recommended if this is reachable beyond your own machine.");
        }

        // Rate limiting on the expensive endpoints - real pixel-by-pixel work, not just a lookup.
        app.before("/api/diff", ctx -> enforceRateLimit(ctx, rateLimiter));
        app.before("/api/diff/image", ctx -> enforceRateLimit(ctx, rateLimiter));
        app.before("/api/scan", ctx -> enforceRateLimit(ctx, rateLimiter));

        // Safety net for anything that escapes the explicit try/catch blocks below - log full
        // details server-side, but don't hand a stack trace to the client.
        app.exception(Exception.class, (e, ctx) -> {
            System.err.println("Unhandled exception on " + ctx.method() + " " + ctx.path() + ": " + e);
            e.printStackTrace();
            ctx.status(500).json(new ErrorResponse("Internal error - see server logs"));
        });

        registerRoutes(app, scanCoordinator, queryService, repository, scanPathValidator, thumbnailService);

        app.start(config.port());
        System.out.println("Screenshot Catalog running on http://localhost:" + config.port());
        System.out.println("Contact Sheet UI:       http://localhost:" + config.port() + "/");
        System.out.println("Swagger UI:             http://localhost:" + config.port() + "/swagger-ui.html");
        System.out.println("Allowed scan roots:     " + config.allowedScanRoots());
    }

    private static void enforceRateLimit(io.javalin.http.Context ctx, RateLimiter rateLimiter) {
        if (!rateLimiter.allow(ctx.ip())) {
            ctx.status(429).json(new ErrorResponse("Rate limit exceeded - try again shortly"));
        }
    }

    /** Avoids leaking key length/content via response-timing differences on comparison. */
    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) result |= a.charAt(i) ^ b.charAt(i);
        return result == 0;
    }

    static void registerRoutes(Javalin app,
                                ScreenshotScanCoordinator scanCoordinator,
                                ScreenshotQueryService queryService,
                                ScreenshotRepository repository,
                                ScanPathValidator scanPathValidator,
                                ThumbnailService thumbnailService) {

        // POST /api/scan - starts a scan asynchronously by default: returns 202 immediately, the
        // actual scan runs on a dedicated background thread (ScreenshotScanCoordinator), and the
        // caller polls GET /api/scan/status for progress. Add ?sync=true to get the old blocking
        // behavior back (this HTTP call waits for the whole scan and returns the full ScanResult) -
        // useful for scripts/CI that want to wait, but it still goes through the same coordinator
        // and its single-scan guard, so it can never race with an async-triggered scan.
        app.post("/api/scan", ctx -> {
            ScanRequest req = ctx.bodyAsClass(ScanRequest.class);
            if (req.rootPath() == null || req.rootPath().isBlank()) {
                ctx.status(400).json(new ErrorResponse("rootPath is required (the Repository/ folder containing Release_x.y subfolders)"));
                return;
            }

            Path requested = Path.of(req.rootPath()).toAbsolutePath().normalize();
            var validation = scanPathValidator.validate(requested);
            if (!validation.allowed()) {
                System.out.println("REJECTED scan request for " + requested + ": " + validation.reason());
                ctx.status(403).json(new ErrorResponse(validation.reason()));
                return;
            }

            // Scan the canonicalized real path, not whatever form the caller happened to type -
            // otherwise file identity shifts between scans if the same root is ever typed slightly
            // differently (case, trailing slash, a mount alias), and the rescan cleanup deletes and
            // recreates every single row, every time. See ScanPathValidator's class doc.
            var startResult = scanCoordinator.tryStartScan(validation.canonicalPath());
            if (!startResult.started()) {
                ctx.status(409).json(new ErrorResponse(startResult.reason()));
                return;
            }

            boolean sync = "true".equalsIgnoreCase(ctx.queryParam("sync")) || "1".equals(ctx.queryParam("sync"));
            if (!sync) {
                ctx.status(202).json(new ScanStartedResponse(true,
                        "Scan started - poll GET /api/scan/status for progress."));
                return;
            }

            while (scanCoordinator.isRunning()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            var finished = scanCoordinator.currentStatus();
            if (finished.status() == ScreenshotScanCoordinator.Status.FAILED) {
                ctx.status(400).json(new ErrorResponse(finished.errorMessage()));
            } else {
                ctx.json(finished.result());
            }
        });

        // GET /api/scan/status - poll this while a scan (started without ?sync=true) is running.
        app.get("/api/scan/status", ctx -> ctx.json(scanCoordinator.currentStatus()));

        app.get("/api/releases", ctx -> ctx.json(queryService.releases()));

        app.get("/api/releases/{release}/components", ctx ->
                ctx.json(queryService.components(ctx.pathParam("release"))));

        app.get("/api/releases/{release}/components/{component}/windows", ctx ->
                ctx.json(queryService.windows(ctx.pathParam("release"), ctx.pathParam("component"))));

        // Paginated: default 100 per page, capped at 500. Response is {items, total, limit, offset}.
        app.get("/api/search", ctx -> {
            var filters = new ScreenshotRepository.SearchFilters(
                    ctx.queryParam("release"),
                    ctx.queryParam("component"),
                    ctx.queryParam("window"),
                    ctx.queryParam("fileName"),
                    parseDateTime(ctx.queryParam("dateFrom")),
                    parseDateTime(ctx.queryParam("dateTo")),
                    parseInt(ctx.queryParam("minWidth")),
                    parseInt(ctx.queryParam("maxWidth")),
                    parseInt(ctx.queryParam("minHeight")),
                    parseInt(ctx.queryParam("maxHeight")),
                    parseLong(ctx.queryParam("minSizeBytes")),
                    parseLong(ctx.queryParam("maxSizeBytes")),
                    ctx.queryParam("sha256"),
                    ctx.queryParam("q"),
                    ctx.queryParam("swingClass"),
                    ctx.queryParam("theme"),
                    ctx.queryParam("plafVersion"),
                    ctx.queryParam("locale"),
                    ctx.queryParam("windowClass")
            );
            int limit = clamp(Optional.ofNullable(parseInt(ctx.queryParam("limit"))).orElse(100), 1, 500);
            int offset = Math.max(Optional.ofNullable(parseInt(ctx.queryParam("offset"))).orElse(0), 0);
            boolean exact = "true".equalsIgnoreCase(ctx.queryParam("exact")) || "1".equals(ctx.queryParam("exact"));
            ctx.json(queryService.searchPaged(filters, exact, limit, offset));
        });

        // Every distinct Swing class seen across the catalog - for populating a search dropdown.
        app.get("/api/swing-classes", ctx -> ctx.json(queryService.swingClasses()));

        // GET /api/compare/releases?from=24.1&to=24.2[&limit=&offset=&status=pending] - the data behind
        // the automatic release report. `changes` is paginated (default 200/page, capped 1000). If `status`
        // is given (pending/approved/rejected), both the list and the summary counts are narrowed to just
        // that status - "?status=pending" gives an accurate "what's left to review" picture.
        app.get("/api/compare/releases", ctx -> {
            String from = ctx.queryParam("from");
            String to = ctx.queryParam("to");
            if (from == null || to == null) {
                ctx.status(400).json(new ErrorResponse("from and to query params are required"));
                return;
            }
            int limit = clamp(Optional.ofNullable(parseInt(ctx.queryParam("limit"))).orElse(200), 1, 1000);
            int offset = Math.max(Optional.ofNullable(parseInt(ctx.queryParam("offset"))).orElse(0), 0);
            ChangeStatus statusFilter = parseChangeStatus(ctx.queryParam("status"));
            if (ctx.queryParam("status") != null && statusFilter == null) {
                ctx.status(400).json(new ErrorResponse("status must be one of PENDING, APPROVED, REJECTED"));
                return;
            }
            ctx.json(queryService.compareReleases(from, to, statusFilter, limit, offset));
        });

        // POST /api/changes/status {leftId, rightId, status, note?, acknowledgedBy?} - record a human
        // review decision on a screenshot pair (approve as intentional, or reject as a real regression).
        // Exactly one of leftId/rightId may be omitted, for a NEW or REMOVED screenshot with nothing to
        // pair it against. The same pair is recognized whether it came from a release comparison
        // (ScreenshotChange.fromId/toId) or an ad-hoc /api/diff call (DiffSummary.leftId/rightId) - both
        // read and write through this same table.
        app.post("/api/changes/status", ctx -> {
            var req = ctx.bodyAsClass(AcknowledgeRequest.class);
            if (req.leftId() == null && req.rightId() == null) {
                ctx.status(400).json(new ErrorResponse("leftId and rightId cannot both be null"));
                return;
            }
            ChangeStatus status = parseChangeStatus(req.status());
            if (status == null) {
                ctx.status(400).json(new ErrorResponse("status must be one of PENDING, APPROVED, REJECTED"));
                return;
            }
            try {
                var ack = queryService.acknowledge(req.leftId(), req.rightId(), status, req.note(), req.acknowledgedBy());
                ctx.json(ack);
            } catch (IllegalArgumentException e) {
                ctx.status(400).json(new ErrorResponse(e.getMessage()));
            }
        });

        // GET /api/changes/status?leftId=&rightId= - current review status for a pair (defaults to PENDING
        // if never reviewed). Mainly useful for a UI that wants this without pulling a full diff.
        app.get("/api/changes/status", ctx -> {
            Long leftId = parseLong(ctx.queryParam("leftId"));
            Long rightId = parseLong(ctx.queryParam("rightId"));
            if (leftId == null && rightId == null) {
                ctx.status(400).json(new ErrorResponse("leftId and/or rightId query params are required"));
                return;
            }
            ctx.json(queryService.reviewStatus(leftId, rightId));
        });

        // GET /api/reports/release?from=24.1&to=24.2[&download=1] - the same comparison, rendered as a
        // standalone printable/shareable HTML page instead of JSON. Includes a Print/Save-as-PDF button
        // (browser-native print-to-PDF, not server-side PDF generation).
        app.get("/api/reports/release", ctx -> {
            String from = ctx.queryParam("from");
            String to = ctx.queryParam("to");
            if (from == null || to == null) {
                ctx.status(400).json(new ErrorResponse("from and to query params are required"));
                return;
            }
            var report = queryService.buildReleaseReport(from, to);
            // Whatever key authenticated this request (header or query) gets echoed into the
            // report's own image/diff links, so they still work when the report is opened later.
            String effectiveKey = ctx.header("X-API-Key") != null ? ctx.header("X-API-Key") : ctx.queryParam("key");
            String html = ReleaseReportRenderer.render(report, effectiveKey);
            ctx.contentType("text/html");
            if (ctx.queryParam("download") != null) {
                ctx.header("Content-Disposition",
                        "attachment; filename=\"release-report-" + from + "-to-" + to + ".html\"");
            }
            ctx.result(html);
        });

        app.get("/api/diff", ctx -> {
            Long leftId = parseLong(ctx.queryParam("leftId"));
            Long rightId = parseLong(ctx.queryParam("rightId"));
            if (leftId == null || rightId == null) {
                ctx.status(400).json(new ErrorResponse("leftId and rightId query params are required"));
                return;
            }
            Optional<Screenshot> left = repository.findById(leftId);
            Optional<Screenshot> right = repository.findById(rightId);
            if (left.isEmpty() || right.isEmpty()) {
                ctx.status(404).json(new ErrorResponse("leftId and/or rightId do not exist in the catalog"));
                return;
            }
            try {
                ctx.json(queryService.diff(left.get(), right.get()));
            } catch (IOException e) {
                ctx.status(400).json(new ErrorResponse("Could not compute diff: " + e.getMessage()));
            }
        });

        app.get("/api/diff/image", ctx -> {
            Long leftId = parseLong(ctx.queryParam("leftId"));
            Long rightId = parseLong(ctx.queryParam("rightId"));
            if (leftId == null || rightId == null) {
                ctx.status(400).json(new ErrorResponse("leftId and rightId query params are required"));
                return;
            }
            Optional<Screenshot> left = repository.findById(leftId);
            Optional<Screenshot> right = repository.findById(rightId);
            if (left.isEmpty() || right.isEmpty()) {
                ctx.status(404).json(new ErrorResponse("leftId and/or rightId do not exist in the catalog"));
                return;
            }
            try {
                ImageDiffService imageDiffService = new ImageDiffService();
                var result = imageDiffService.diff(Path.of(left.get().filePath()), Path.of(right.get().filePath()));
                if (result.diffImage() == null) {
                    ctx.status(422).json(new ErrorResponse(result.note()));
                    return;
                }
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                ImageIO.write(result.diffImage(), "png", buffer);
                ctx.contentType("image/png");
                ctx.result(buffer.toByteArray());
            } catch (IOException e) {
                ctx.status(400).json(new ErrorResponse("Could not compute diff: " + e.getMessage()));
            }
        });

        // Perceptual-hash similarity search: GET /api/similar/{id}?maxDistance=8
        app.get("/api/similar/{id}", ctx -> {
            long id = Long.parseLong(ctx.pathParam("id"));
            int maxDistance = Optional.ofNullable(parseInt(ctx.queryParam("maxDistance"))).orElse(8);
            try {
                ctx.json(queryService.similarTo(id, maxDistance));
            } catch (java.util.NoSuchElementException e) {
                ctx.status(404).json(new ErrorResponse(e.getMessage()));
            }
        });

        // Exact-duplicate groups (same SHA-256) within one release.
        app.get("/api/duplicates", ctx -> {
            String release = ctx.queryParam("release");
            if (release == null) {
                ctx.status(400).json(new ErrorResponse("release query param is required"));
                return;
            }
            ctx.json(queryService.exactDuplicates(release));
        });

        app.get("/api/images/{id}", ctx -> {
            long id = Long.parseLong(ctx.pathParam("id"));
            Optional<Screenshot> shot = repository.findById(id);
            if (shot.isEmpty()) {
                ctx.status(404).json(new ErrorResponse("No screenshot with id " + id));
                return;
            }
            Path file = Path.of(shot.get().filePath());
            if (!Files.exists(file)) {
                ctx.status(404).json(new ErrorResponse("File no longer exists on disk: " + file));
                return;
            }
            String contentType = Files.probeContentType(file);
            ctx.contentType(contentType != null ? contentType : ContentType.APPLICATION_OCTET_STREAM.getMimeType());
            ctx.result(Files.newInputStream(file));
        });

        // Downscaled preview for grid/timeline browsing - GET /api/images/{id}/thumbnail?maxDimension=480.
        // Cached in memory keyed by content hash, so it never goes stale across rescans (see ThumbnailService).
        app.get("/api/images/{id}/thumbnail", ctx -> {
            long id = Long.parseLong(ctx.pathParam("id"));
            Optional<Screenshot> shot = repository.findById(id);
            if (shot.isEmpty()) {
                ctx.status(404).json(new ErrorResponse("No screenshot with id " + id));
                return;
            }
            Path file = Path.of(shot.get().filePath());
            if (!Files.exists(file)) {
                ctx.status(404).json(new ErrorResponse("File no longer exists on disk: " + file));
                return;
            }
            int maxDimension = ThumbnailService.clampDimension(parseInt(ctx.queryParam("maxDimension")));
            try {
                byte[] thumbnail = thumbnailService.getThumbnail(file, shot.get().sha256(), maxDimension);
                ctx.contentType("image/png");
                ctx.header("Cache-Control", "public, max-age=31536000, immutable"); // content-addressed - never changes
                ctx.result(thumbnail);
            } catch (IOException e) {
                ctx.status(400).json(new ErrorResponse("Could not generate thumbnail: " + e.getMessage()));
            }
        });

        // Full detail view: screenshot facts + optional Swing metadata + component list.
        app.get("/api/screenshots/{id}", ctx -> {
            long id = Long.parseLong(ctx.pathParam("id"));
            var detail = queryService.detail(id);
            if (detail.isEmpty()) {
                ctx.status(404).json(new ErrorResponse("No screenshot with id " + id));
                return;
            }
            ctx.json(detail.get());
        });
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Long.parseLong(value); } catch (NumberFormatException e) { return null; }
    }

    private static Integer parseInt(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Integer.parseInt(value); } catch (NumberFormatException e) { return null; }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static ChangeStatus parseChangeStatus(String value) {
        if (value == null || value.isBlank()) return null;
        try { return ChangeStatus.valueOf(value.trim().toUpperCase()); } catch (IllegalArgumentException e) { return null; }
    }

    private static LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) return null;
        try { return LocalDateTime.parse(value); } catch (Exception e) { return null; }
    }
}
