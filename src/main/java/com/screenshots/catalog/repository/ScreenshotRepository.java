package com.screenshots.catalog.repository;

import com.screenshots.catalog.model.ChangeAcknowledgment;
import com.screenshots.catalog.model.ChangeStatus;
import com.screenshots.catalog.model.Screenshot;
import com.screenshots.catalog.model.SwingComponentInfo;
import com.screenshots.catalog.model.SwingMetadata;

import javax.sql.DataSource;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Storage is normalized (releases -> components -> windows -> screenshots),
 * matching the on-disk Repository/Release_X/Component/Window/ hierarchy.
 * Reads are denormalized back into a flat Screenshot record via JOINs so API
 * consumers don't have to walk the tree themselves.
 *
 * screenshot_metadata / screenshot_components are optional 1:1 / 1:many
 * sidecars populated only when the capture tool exports Swing UI metadata
 * (see ScreenshotScanService).
 */
public class ScreenshotRepository {

    private static final String SELECT_SCREENSHOT_JOIN = """
            SELECT s.id, r.name AS release, c.name AS component, w.name AS window_name,
                   s.variant, s.file_name, s.file_path, s.width, s.height, s.file_size_bytes,
                   s.sha256, s.phash, s.captured_at
            FROM screenshots s
            JOIN windows w ON s.window_id = w.id
            JOIN components c ON w.component_id = c.id
            JOIN releases r ON c.release_id = r.id
            """;

    private final DataSource dataSource;

    public ScreenshotRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Opens a session backed by a single reused JDBC connection, for a whole
     * scan instead of one connection per get-or-create/upsert call. Matters
     * a lot in practice: a scan does dozens of small DB operations per file
     * (get-or-create window, upsert the screenshot, upsert metadata, replace
     * components), and opening a fresh physical connection for each one is
     * real, measurable overhead - worse the slower/more remote the
     * underlying storage for the H2 file is (a network share or VM-mounted
     * folder in particular). Every other repository method still opens its
     * own connection per call, which is fine for them - a single request
     * handler doing one query is not the hot path a scan is.
     */
    public ScanSession openScanSession() {
        try {
            return new ScanSession(dataSource.getConnection());
        } catch (SQLException e) {
            throw new RuntimeException("Failed to open a scan session", e);
        }
    }

    public final class ScanSession implements AutoCloseable {
        private final Connection connection;

        private ScanSession(Connection connection) {
            this.connection = connection;
        }

        public long getOrCreateRelease(String name) {
            try {
                return ScreenshotRepository.this.getOrCreateRelease(connection, name);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }

        public long getOrCreateComponent(long releaseId, String name) {
            try {
                return ScreenshotRepository.this.getOrCreateComponent(connection, releaseId, name);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }

        public long getOrCreateWindow(long componentId, String name) {
            try {
                return ScreenshotRepository.this.getOrCreateWindow(connection, componentId, name);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }

        public Optional<Screenshot> findByFilePath(String filePath) {
            try {
                return ScreenshotRepository.this.findByFilePath(connection, filePath);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }

        public Screenshot upsert(NewScreenshot s) {
            try {
                return ScreenshotRepository.this.upsert(connection, s);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to upsert screenshot: " + s.filePath(), e);
            }
        }

        public void upsertMetadata(long screenshotId, SwingMetadata m) {
            try {
                ScreenshotRepository.this.upsertMetadata(connection, screenshotId, m);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to upsert metadata for screenshot " + screenshotId, e);
            }
        }

        public void replaceComponents(long screenshotId, List<SwingComponentInfo> components) {
            try {
                ScreenshotRepository.this.replaceComponents(connection, screenshotId, components);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to replace components for screenshot " + screenshotId, e);
            }
        }

        public int deleteScreenshotsInWindowsNotIn(Set<Long> visitedWindowIds, Set<String> keepPaths) {
            try {
                return ScreenshotRepository.this.deleteScreenshotsInWindowsNotIn(connection, visitedWindowIds, keepPaths);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void close() {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Get-or-create hierarchy (single-writer scan assumed - no locking)   */
    /* ------------------------------------------------------------------ */

    public long getOrCreateRelease(String name) {
        try (Connection c = dataSource.getConnection()) {
            return getOrCreateRelease(c, name);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public long getOrCreateComponent(long releaseId, String name) {
        try (Connection c = dataSource.getConnection()) {
            return getOrCreateComponent(c, releaseId, name);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public long getOrCreateWindow(long componentId, String name) {
        try (Connection c = dataSource.getConnection()) {
            return getOrCreateWindow(c, componentId, name);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private long getOrCreateRelease(Connection c, String name) throws SQLException {
        Long id = queryLong(c, "SELECT id FROM releases WHERE name = ?", name);
        if (id != null) return id;
        return insertReturningId(c, "INSERT INTO releases (name) VALUES (?)", name);
    }

    private long getOrCreateComponent(Connection c, long releaseId, String name) throws SQLException {
        Long id = queryLong(c, "SELECT id FROM components WHERE release_id = ? AND name = ?", releaseId, name);
        if (id != null) return id;
        return insertReturningId(c, "INSERT INTO components (release_id, name) VALUES (?, ?)", releaseId, name);
    }

    private long getOrCreateWindow(Connection c, long componentId, String name) throws SQLException {
        Long id = queryLong(c, "SELECT id FROM windows WHERE component_id = ? AND name = ?", componentId, name);
        if (id != null) return id;
        return insertReturningId(c, "INSERT INTO windows (component_id, name) VALUES (?, ?)", componentId, name);
    }

    /* ------------------------------------------------------------------ */
    /* Screenshot upsert                                                    */
    /* ------------------------------------------------------------------ */

    public record NewScreenshot(
            long windowId, String fileName, String filePath, String variant,
            int width, int height, long fileSizeBytes, String sha256, long pHash, LocalDateTime capturedAt
    ) {
    }

    public Screenshot upsert(NewScreenshot s) {
        try (Connection c = dataSource.getConnection()) {
            return upsert(c, s);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert screenshot: " + s.filePath(), e);
        }
    }

    private Screenshot upsert(Connection c, NewScreenshot s) throws SQLException {
        String sql = """
                MERGE INTO screenshots
                    (window_id, file_name, file_path, variant, width, height, file_size_bytes, sha256, phash, captured_at)
                KEY (file_path)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, s.windowId());
            ps.setString(2, s.fileName());
            ps.setString(3, s.filePath());
            ps.setString(4, s.variant());
            ps.setInt(5, s.width());
            ps.setInt(6, s.height());
            ps.setLong(7, s.fileSizeBytes());
            ps.setString(8, s.sha256());
            ps.setLong(9, s.pHash());
            ps.setTimestamp(10, Timestamp.valueOf(s.capturedAt()));
            ps.executeUpdate();
        }
        return findByFilePath(c, s.filePath())
                .orElseThrow(() -> new IllegalStateException("Upsert succeeded but row not found: " + s.filePath()));
    }

    /**
     * Deletes any screenshot whose file_path falls under rootPrefix but wasn't
     * seen in this scan (i.e. the file no longer exists on disk). Cascades to
     * screenshot_metadata / screenshot_components via FK. Returns how many rows
     * were removed.
     *
     * Scoped by window_id rather than a file_path string prefix - a prefix match
     * against an absolute path is fragile across platforms (e.g. Windows can
     * resolve the same temp directory to short/8.3 vs long path forms
     * inconsistently between calls, silently breaking a string prefix match
     * with no exception). Scoping by "every window this scan actually visited"
     * avoids that entirely and is arguably more correct: it only touches windows
     * we know we just looked at.
     */
    public int deleteScreenshotsInWindowsNotIn(Set<Long> visitedWindowIds, Set<String> keepPaths) {
        if (visitedWindowIds.isEmpty()) return 0;
        try (Connection c = dataSource.getConnection()) {
            return deleteScreenshotsInWindowsNotIn(c, visitedWindowIds, keepPaths);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private int deleteScreenshotsInWindowsNotIn(Connection c, Set<Long> visitedWindowIds, Set<String> keepPaths) throws SQLException {
        if (visitedWindowIds.isEmpty()) return 0;
        List<Long> idsToDelete = new ArrayList<>();
        String placeholders = String.join(",", Collections.nCopies(visitedWindowIds.size(), "?"));
        String sql = "SELECT id, file_path FROM screenshots WHERE window_id IN (" + placeholders + ")";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            for (Long windowId : visitedWindowIds) ps.setLong(i++, windowId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (!keepPaths.contains(rs.getString("file_path"))) {
                        idsToDelete.add(rs.getLong("id"));
                    }
                }
            }
        }
        if (idsToDelete.isEmpty()) return 0;

        try (PreparedStatement del = c.prepareStatement("DELETE FROM screenshots WHERE id = ?")) {
            for (long id : idsToDelete) {
                del.setLong(1, id);
                del.addBatch();
            }
            del.executeBatch();
        }
        return idsToDelete.size();
    }

    /* ------------------------------------------------------------------ */
    /* Optional Swing metadata (sidecar JSON)                               */
    /* ------------------------------------------------------------------ */

    public void upsertMetadata(long screenshotId, SwingMetadata m) {
        try (Connection c = dataSource.getConnection()) {
            upsertMetadata(c, screenshotId, m);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert metadata for screenshot " + screenshotId, e);
        }
    }

    private void upsertMetadata(Connection c, long screenshotId, SwingMetadata m) throws SQLException {
        String sql = """
                MERGE INTO screenshot_metadata
                    (screenshot_id, window_class, theme, plaf_version, locale, screen_width, screen_height)
                KEY (screenshot_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, screenshotId);
            ps.setString(2, m.windowClass());
            ps.setString(3, m.theme());
            ps.setString(4, m.plafVersion());
            ps.setString(5, m.locale());
            setNullableInt(ps, 6, m.screenWidth());
            setNullableInt(ps, 7, m.screenHeight());
            ps.executeUpdate();
        }
    }

    /** Replaces the full component list for a screenshot (delete then insert) - keeps rescans idempotent. */
    public void replaceComponents(long screenshotId, List<SwingComponentInfo> components) {
        try (Connection c = dataSource.getConnection()) {
            replaceComponents(c, screenshotId, components);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to replace components for screenshot " + screenshotId, e);
        }
    }

    private void replaceComponents(Connection c, long screenshotId, List<SwingComponentInfo> components) throws SQLException {
        try (PreparedStatement del = c.prepareStatement("DELETE FROM screenshot_components WHERE screenshot_id = ?")) {
            del.setLong(1, screenshotId);
            del.executeUpdate();
        }
        if (components == null || components.isEmpty()) return;
        try (PreparedStatement ins = c.prepareStatement(
                "INSERT INTO screenshot_components (screenshot_id, component_id, swing_class) VALUES (?, ?, ?)")) {
            for (SwingComponentInfo comp : components) {
                ins.setLong(1, screenshotId);
                ins.setString(2, comp.componentId());
                ins.setString(3, comp.swingClass());
                ins.addBatch();
            }
            ins.executeBatch();
        }
    }

    public Optional<SwingMetadata> findMetadata(long screenshotId) {
        String sql = "SELECT window_class, theme, plaf_version, locale, screen_width, screen_height " +
                "FROM screenshot_metadata WHERE screenshot_id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, screenshotId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new SwingMetadata(
                        rs.getString("window_class"), rs.getString("theme"), rs.getString("plaf_version"),
                        rs.getString("locale"), getNullableInt(rs, "screen_width"), getNullableInt(rs, "screen_height")
                ));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<SwingComponentInfo> findComponents(long screenshotId) {
        String sql = "SELECT component_id, swing_class FROM screenshot_components WHERE screenshot_id = ? ORDER BY id";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, screenshotId);
            try (ResultSet rs = ps.executeQuery()) {
                List<SwingComponentInfo> out = new ArrayList<>();
                while (rs.next()) out.add(new SwingComponentInfo(rs.getString("component_id"), rs.getString("swing_class")));
                return out;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<String> findDistinctSwingClasses() {
        return queryStrings("SELECT DISTINCT swing_class FROM screenshot_components ORDER BY swing_class");
    }

    /* ------------------------------------------------------------------ */
    /* Lookups                                                              */
    /* ------------------------------------------------------------------ */

    public Optional<Screenshot> findById(long id) {
        return queryOneScreenshot(SELECT_SCREENSHOT_JOIN + " WHERE s.id = ?", id);
    }

    public Optional<Screenshot> findByFilePath(String filePath) {
        try (Connection c = dataSource.getConnection()) {
            return findByFilePath(c, filePath);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private Optional<Screenshot> findByFilePath(Connection c, String filePath) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(SELECT_SCREENSHOT_JOIN + " WHERE s.file_path = ?")) {
            ps.setString(1, filePath);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        }
    }

    public List<String> findAllReleaseNames() {
        return queryStrings("SELECT name FROM releases ORDER BY name");
    }

    public List<String> findComponentNames(String release) {
        String sql = "SELECT c.name FROM components c JOIN releases r ON c.release_id = r.id " +
                "WHERE r.name = ? ORDER BY c.name";
        return queryStringsWithParam(sql, release);
    }

    public List<String> findWindowNames(String release, String component) {
        String sql = "SELECT w.name FROM windows w " +
                "JOIN components c ON w.component_id = c.id " +
                "JOIN releases r ON c.release_id = r.id " +
                "WHERE r.name = ? AND c.name = ? ORDER BY w.name";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, release);
            ps.setString(2, component);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) out.add(rs.getString(1));
                return out;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Screenshot> findByRelease(String release) {
        return queryScreenshots(SELECT_SCREENSHOT_JOIN + " WHERE r.name = ? ORDER BY c.name, w.name, s.file_name", release);
    }

    /**
     * Flexible search: every filter is optional. `q` free-texts against
     * component, window name, and file name. swingClass/theme/plafVersion/
     * locale/windowClass only match screenshots that have a sidecar with
     * that data - screenshots without a sidecar simply won't match those.
     */
    public record SearchFilters(
            String release, String component, String window, String fileName,
            LocalDateTime dateFrom, LocalDateTime dateTo,
            Integer minWidth, Integer maxWidth, Integer minHeight, Integer maxHeight,
            Long minSizeBytes, Long maxSizeBytes,
            String sha256, String q,
            String swingClass, String theme, String plafVersion, String locale, String windowClass
    ) {
        // Convenience constructor for callers that don't care about Swing metadata filters.
        public SearchFilters(
                String release, String component, String window, String fileName,
                LocalDateTime dateFrom, LocalDateTime dateTo,
                Integer minWidth, Integer maxWidth, Integer minHeight, Integer maxHeight,
                Long minSizeBytes, Long maxSizeBytes,
                String sha256, String q
        ) {
            this(release, component, window, fileName, dateFrom, dateTo, minWidth, maxWidth, minHeight, maxHeight,
                    minSizeBytes, maxSizeBytes, sha256, q, null, null, null, null, null);
        }
    }

    public List<Screenshot> search(SearchFilters f) {
        return search(f, false);
    }

    /**
     * @param exact if true, release/component/window/fileName match exactly (case-sensitive
     *              `=`) instead of the default case-insensitive substring `LIKE '%...%'`.
     *              Exact mode is for callers that already have a full, known value (e.g. the
     *              UI's grid/timeline views, which always pass a value straight from a prior
     *              /api/releases or /api/.../windows response) - substring matching there is
     *              both unnecessary and, measured against a synthetic 180k-row/36k-window
     *              dataset, meaningfully slower (~10ms vs ~4ms) than a plain equality lookup.
     *              The public free-text search experience (e.g. "component=auth" matching
     *              "AuthService") intentionally keeps using substring matching by default.
     */
    public List<Screenshot> search(SearchFilters f, boolean exact) {
        WhereClause where = buildWhereClause(f, exact);
        String sql = SELECT_SCREENSHOT_JOIN + " LEFT JOIN screenshot_metadata sm ON sm.screenshot_id = s.id WHERE 1=1"
                + where.sql() + " ORDER BY r.name, c.name, w.name, s.file_name";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            bindParams(ps, where.params());
            try (ResultSet rs = ps.executeQuery()) {
                return mapAll(rs);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public record PagedResult<T>(List<T> items, int total, int limit, int offset) {
    }

    /**
     * Same filtering as search(), but returns one page plus the total match
     * count (a separate COUNT(*) query with the same WHERE clause) so a
     * client knows whether there's more to fetch.
     */
    public PagedResult<Screenshot> searchPaged(SearchFilters f, int limit, int offset) {
        return searchPaged(f, false, limit, offset);
    }

    /** See search(SearchFilters, boolean) for what `exact` does. */
    public PagedResult<Screenshot> searchPaged(SearchFilters f, boolean exact, int limit, int offset) {
        WhereClause where = buildWhereClause(f, exact);

        int total = count(where);

        String sql = SELECT_SCREENSHOT_JOIN + " LEFT JOIN screenshot_metadata sm ON sm.screenshot_id = s.id WHERE 1=1"
                + where.sql() + " ORDER BY r.name, c.name, w.name, s.file_name LIMIT ? OFFSET ?";
        List<Object> params = new ArrayList<>(where.params());
        params.add(limit);
        params.add(offset);

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return new PagedResult<>(mapAll(rs), total, limit, offset);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private int count(WhereClause where) {
        String sql = "SELECT COUNT(*) FROM screenshots s " +
                "JOIN windows w ON s.window_id = w.id " +
                "JOIN components c ON w.component_id = c.id " +
                "JOIN releases r ON c.release_id = r.id " +
                "LEFT JOIN screenshot_metadata sm ON sm.screenshot_id = s.id WHERE 1=1" + where.sql();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            bindParams(ps, where.params());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private record WhereClause(String sql, List<Object> params) {
    }

    private WhereClause buildWhereClause(SearchFilters f, boolean exact) {
        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();

        if (notBlank(f.release())) {
            if (exact) { sql.append(" AND r.name = ?"); params.add(f.release()); }
            else { sql.append(" AND LOWER(r.name) LIKE ?"); params.add(like(f.release())); }
        }
        if (notBlank(f.component())) {
            if (exact) { sql.append(" AND c.name = ?"); params.add(f.component()); }
            else { sql.append(" AND LOWER(c.name) LIKE ?"); params.add(like(f.component())); }
        }
        if (notBlank(f.window())) {
            if (exact) { sql.append(" AND w.name = ?"); params.add(f.window()); }
            else { sql.append(" AND LOWER(w.name) LIKE ?"); params.add(like(f.window())); }
        }
        if (notBlank(f.fileName())) {
            if (exact) { sql.append(" AND s.file_name = ?"); params.add(f.fileName()); }
            else { sql.append(" AND LOWER(s.file_name) LIKE ?"); params.add(like(f.fileName())); }
        }
        if (f.dateFrom() != null) { sql.append(" AND s.captured_at >= ?"); params.add(Timestamp.valueOf(f.dateFrom())); }
        if (f.dateTo() != null) { sql.append(" AND s.captured_at <= ?"); params.add(Timestamp.valueOf(f.dateTo())); }
        if (f.minWidth() != null) { sql.append(" AND s.width >= ?"); params.add(f.minWidth()); }
        if (f.maxWidth() != null) { sql.append(" AND s.width <= ?"); params.add(f.maxWidth()); }
        if (f.minHeight() != null) { sql.append(" AND s.height >= ?"); params.add(f.minHeight()); }
        if (f.maxHeight() != null) { sql.append(" AND s.height <= ?"); params.add(f.maxHeight()); }
        if (f.minSizeBytes() != null) { sql.append(" AND s.file_size_bytes >= ?"); params.add(f.minSizeBytes()); }
        if (f.maxSizeBytes() != null) { sql.append(" AND s.file_size_bytes <= ?"); params.add(f.maxSizeBytes()); }
        if (notBlank(f.sha256())) { sql.append(" AND s.sha256 = ?"); params.add(f.sha256()); }
        if (notBlank(f.q())) {
            sql.append(" AND (LOWER(c.name) LIKE ? OR LOWER(w.name) LIKE ? OR LOWER(s.file_name) LIKE ?)");
            String needle = like(f.q());
            params.add(needle); params.add(needle); params.add(needle);
        }
        if (notBlank(f.theme())) { sql.append(" AND LOWER(sm.theme) LIKE ?"); params.add(like(f.theme())); }
        if (notBlank(f.plafVersion())) { sql.append(" AND LOWER(sm.plaf_version) LIKE ?"); params.add(like(f.plafVersion())); }
        if (notBlank(f.locale())) { sql.append(" AND LOWER(sm.locale) LIKE ?"); params.add(like(f.locale())); }
        if (notBlank(f.windowClass())) { sql.append(" AND LOWER(sm.window_class) LIKE ?"); params.add(like(f.windowClass())); }
        if (notBlank(f.swingClass())) {
            sql.append(" AND EXISTS (SELECT 1 FROM screenshot_components sc " +
                    "WHERE sc.screenshot_id = s.id AND LOWER(sc.swing_class) = ?)");
            params.add(f.swingClass().toLowerCase());
        }

        return new WhereClause(sql.toString(), params);
    }

    private static void bindParams(PreparedStatement ps, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
    }

    /** Exact duplicates: screenshots in a release sharing the same SHA-256, grouped. */
    public List<List<Screenshot>> findExactDuplicates(String release) {
        String sql = "SELECT s.sha256 FROM screenshots s " +
                "JOIN windows w ON s.window_id = w.id JOIN components c ON w.component_id = c.id " +
                "JOIN releases r ON c.release_id = r.id WHERE r.name = ? " +
                "GROUP BY s.sha256 HAVING COUNT(*) > 1";
        List<String> dupHashes = queryStringsWithParam(sql, release);
        List<List<Screenshot>> groups = new ArrayList<>();
        for (String hash : dupHashes) {
            groups.add(queryScreenshots(SELECT_SCREENSHOT_JOIN + " WHERE r.name = ? AND s.sha256 = ? ORDER BY s.file_path",
                    release, hash));
        }
        return groups;
    }

    /** Near-duplicates by perceptual hash within a Hamming distance threshold, for one screenshot. */
    public List<Screenshot> findSimilarByPHash(long screenshotId, long pHash, int maxHammingDistance) {
        // H2 has no BIT_COUNT for BIGINT XOR out of the box in all versions; do the Hamming filter in Java.
        List<Screenshot> all = queryScreenshots(SELECT_SCREENSHOT_JOIN + " WHERE s.id <> ?", screenshotId);
        List<Screenshot> out = new ArrayList<>();
        for (Screenshot s : all) {
            if (Long.bitCount(s.pHash() ^ pHash) <= maxHammingDistance) out.add(s);
        }
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* Change acknowledgments (approve/reject review workflow)              */
    /* ------------------------------------------------------------------ */

    /** Get-or-create + update, since there's no DB-level unique constraint to MERGE against (see V4 migration comment). */
    public ChangeAcknowledgment upsertAcknowledgment(Long leftId, Long rightId, ChangeStatus status, String note, String acknowledgedBy) {
        try (Connection c = dataSource.getConnection()) {
            Long existingId = findAcknowledgmentRowId(c, leftId, rightId);
            if (existingId == null) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO change_acknowledgments (left_id, right_id, status, note, acknowledged_by, acknowledged_at) " +
                        "VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)")) {
                    setNullableLong(ps, 1, leftId);
                    setNullableLong(ps, 2, rightId);
                    ps.setString(3, status.name());
                    ps.setString(4, note);
                    ps.setString(5, acknowledgedBy);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE change_acknowledgments SET status = ?, note = ?, acknowledged_by = ?, acknowledged_at = CURRENT_TIMESTAMP WHERE id = ?")) {
                    ps.setString(1, status.name());
                    ps.setString(2, note);
                    ps.setString(3, acknowledgedBy);
                    ps.setLong(4, existingId);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to upsert acknowledgment for (" + leftId + "," + rightId + ")", e);
        }
        return findAcknowledgment(leftId, rightId).orElseThrow();
    }

    public Optional<ChangeAcknowledgment> findAcknowledgment(Long leftId, Long rightId) {
        StringBuilder sql = new StringBuilder(
                "SELECT left_id, right_id, status, note, acknowledged_by, acknowledged_at FROM change_acknowledgments WHERE ");
        List<Object> params = new ArrayList<>();
        appendPairMatch(sql, params, leftId, rightId);

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql.toString())) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapAcknowledgment(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Every stored acknowledgment, as a map keyed by "leftId:rightId" (using
     * the literal string "null" for a null side) - built once and reused
     * across a whole compareReleases() call so attaching status to N changes
     * doesn't mean N extra queries.
     */
    public Map<String, ChangeAcknowledgment> findAllAcknowledgmentsByPairKey() {
        Map<String, ChangeAcknowledgment> out = new HashMap<>();
        String sql = "SELECT left_id, right_id, status, note, acknowledged_by, acknowledged_at FROM change_acknowledgments";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ChangeAcknowledgment ack = mapAcknowledgment(rs);
                out.put(pairKey(ack.leftId(), ack.rightId()), ack);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    public static String pairKey(Long leftId, Long rightId) {
        return (leftId == null ? "null" : leftId) + ":" + (rightId == null ? "null" : rightId);
    }

    private Long findAcknowledgmentRowId(Connection c, Long leftId, Long rightId) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT id FROM change_acknowledgments WHERE ");
        List<Object> params = new ArrayList<>();
        appendPairMatch(sql, params, leftId, rightId);
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            bindParams(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private void appendPairMatch(StringBuilder sql, List<Object> params, Long leftId, Long rightId) {
        if (leftId == null) sql.append("left_id IS NULL");
        else { sql.append("left_id = ?"); params.add(leftId); }
        sql.append(" AND ");
        if (rightId == null) sql.append("right_id IS NULL");
        else { sql.append("right_id = ?"); params.add(rightId); }
    }

    private ChangeAcknowledgment mapAcknowledgment(ResultSet rs) throws SQLException {
        long left = rs.getLong("left_id");
        Long leftId = rs.wasNull() ? null : left;
        long right = rs.getLong("right_id");
        Long rightId = rs.wasNull() ? null : right;
        Timestamp ackAt = rs.getTimestamp("acknowledged_at");
        return new ChangeAcknowledgment(
                leftId, rightId,
                ChangeStatus.valueOf(rs.getString("status")),
                rs.getString("note"),
                rs.getString("acknowledged_by"),
                ackAt == null ? null : ackAt.toLocalDateTime()
        );
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) ps.setNull(index, Types.BIGINT); else ps.setLong(index, value);
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                              */
    /* ------------------------------------------------------------------ */

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static String like(String s) { return "%" + s.toLowerCase() + "%"; }

    private static void setNullableInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) ps.setNull(index, Types.INTEGER); else ps.setInt(index, value);
    }

    private static Integer getNullableInt(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private Long queryLong(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private long insertReturningId(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private List<String> queryStrings(String sql) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<String> out = new ArrayList<>();
            while (rs.next()) out.add(rs.getString(1));
            return out;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private List<String> queryStringsWithParam(String sql, Object param) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) out.add(rs.getString(1));
                return out;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private Optional<Screenshot> queryOneScreenshot(String sql, Object param) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private List<Screenshot> queryScreenshots(String sql, Object... params) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                return mapAll(rs);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private List<Screenshot> mapAll(ResultSet rs) throws SQLException {
        List<Screenshot> out = new ArrayList<>();
        while (rs.next()) out.add(mapRow(rs));
        return out;
    }

    private Screenshot mapRow(ResultSet rs) throws SQLException {
        return new Screenshot(
                rs.getLong("id"),
                rs.getString("release"),
                rs.getString("component"),
                rs.getString("window_name"),
                rs.getString("variant"),
                rs.getString("file_name"),
                rs.getString("file_path"),
                rs.getInt("width"),
                rs.getInt("height"),
                rs.getLong("file_size_bytes"),
                rs.getString("sha256"),
                rs.getLong("phash"),
                rs.getTimestamp("captured_at").toLocalDateTime()
        );
    }
}
