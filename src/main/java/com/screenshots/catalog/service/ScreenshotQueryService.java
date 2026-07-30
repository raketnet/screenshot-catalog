package com.screenshots.catalog.service;

import com.screenshots.catalog.dto.DiffSummary;
import com.screenshots.catalog.dto.DuplicateGroup;
import com.screenshots.catalog.dto.ReleaseCompareResult;
import com.screenshots.catalog.dto.ReleaseReport;
import com.screenshots.catalog.dto.ScreenshotChange;
import com.screenshots.catalog.model.ChangeAcknowledgment;
import com.screenshots.catalog.model.ChangeStatus;
import com.screenshots.catalog.model.Screenshot;
import com.screenshots.catalog.model.ScreenshotDetail;
import com.screenshots.catalog.repository.ScreenshotRepository;
import com.screenshots.catalog.util.VersionComparator;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

public class ScreenshotQueryService {

    private final ScreenshotRepository repository;
    private final ImageDiffService imageDiffService;

    public ScreenshotQueryService(ScreenshotRepository repository, ImageDiffService imageDiffService) {
        this.repository = repository;
        this.imageDiffService = imageDiffService;
    }

    public List<String> releases() {
        List<String> names = repository.findAllReleaseNames();
        return names.stream().sorted(VersionComparator.INSTANCE).toList();
    }

    public List<String> components(String release) {
        return repository.findComponentNames(release);
    }

    public List<String> windows(String release, String component) {
        return repository.findWindowNames(release, component);
    }

    public List<Screenshot> search(ScreenshotRepository.SearchFilters filters) {
        return repository.search(filters);
    }

    /** Paginated search - use for the API layer so a huge catalog can't blow up a single response. */
    public ScreenshotRepository.PagedResult<Screenshot> searchPaged(ScreenshotRepository.SearchFilters filters, int limit, int offset) {
        return repository.searchPaged(filters, limit, offset);
    }

    /** See ScreenshotRepository.search(SearchFilters, boolean) for what `exact` does. */
    public ScreenshotRepository.PagedResult<Screenshot> searchPaged(ScreenshotRepository.SearchFilters filters, boolean exact, int limit, int offset) {
        return repository.searchPaged(filters, exact, limit, offset);
    }

    public Optional<Screenshot> byId(long id) {
        return repository.findById(id);
    }

    /** Screenshot plus its optional Swing metadata and component list, for a detail view. */
    public Optional<ScreenshotDetail> detail(long id) {
        return repository.findById(id).map(s -> new ScreenshotDetail(
                s,
                repository.findMetadata(id).orElse(null),
                repository.findComponents(id)
        ));
    }

    /** Every distinct Swing class seen across the catalog - handy for a search UI's autocomplete. */
    public List<String> swingClasses() {
        return repository.findDistinctSwingClasses();
    }

    public DiffSummary diff(Screenshot left, Screenshot right) throws IOException {
        var result = imageDiffService.diff(Path.of(left.filePath()), Path.of(right.filePath()));
        ChangeAcknowledgment ack = repository.findAcknowledgment(left.id(), right.id())
                .orElse(ChangeAcknowledgment.pending(left.id(), right.id()));
        return new DiffSummary(
                left.id(), right.id(),
                result.dimensionsMatch(),
                round(result.pixelDifferencePercentage()),
                result.pHashDistance(),
                round(result.pHashSimilarityPercent()),
                round(result.ssimScore()),
                result.note(),
                ack.status(),
                ack.note()
        );
    }

    /* ------------------------------------------------------------------ */
    /* Change review workflow (approve/reject)                              */
    /* ------------------------------------------------------------------ */

    /**
     * Records a human review decision on a screenshot pair. leftId/rightId
     * should match whatever a change's fromId/toId (or a diff's leftId/rightId)
     * were - exactly one may be null, for a NEW or REMOVED screenshot with
     * nothing to pair it against.
     */
    public ChangeAcknowledgment acknowledge(Long leftId, Long rightId, ChangeStatus status, String note, String acknowledgedBy) {
        if (leftId == null && rightId == null) {
            throw new IllegalArgumentException("leftId and rightId cannot both be null");
        }
        return repository.upsertAcknowledgment(leftId, rightId, status, note, acknowledgedBy);
    }

    public ChangeAcknowledgment reviewStatus(Long leftId, Long rightId) {
        return repository.findAcknowledgment(leftId, rightId).orElse(ChangeAcknowledgment.pending(leftId, rightId));
    }

    /** Screenshots whose perceptual hash is within maxDistance of the given screenshot's hash. */
    public List<Screenshot> similarTo(long screenshotId, int maxHammingDistance) {
        Screenshot target = repository.findById(screenshotId)
                .orElseThrow(() -> new NoSuchElementException("No screenshot with id " + screenshotId));
        return repository.findSimilarByPHash(screenshotId, target.pHash(), maxHammingDistance);
    }

    public List<DuplicateGroup> exactDuplicates(String release) {
        return repository.findExactDuplicates(release).stream()
                .map(group -> new DuplicateGroup(group.get(0).sha256(), group.size(), group))
                .toList();
    }

    /**
     * Compares every screenshot in toRelease against fromRelease, matched by
     * (component, window, fileName) - the natural identity for a screenshot
     * that's expected to represent "the same screen" release over release.
     * Unpaginated - returns every change. Used internally (e.g. by the
     * release report, which needs the full picture); the API layer uses the
     * paginated overload below.
     */
    public ReleaseCompareResult compareReleases(String fromRelease, String toRelease) {
        return compareReleases(fromRelease, toRelease, null, Integer.MAX_VALUE, 0);
    }

    /** Same comparison, with the changes list paginated (counts remain totals, not page-scoped). */
    public ReleaseCompareResult compareReleases(String fromRelease, String toRelease, int limit, int offset) {
        return compareReleases(fromRelease, toRelease, null, limit, offset);
    }

    /**
     * Same comparison, optionally filtered down to a single review status first
     * (e.g. "only show me what's still PENDING") - counts and pagination both
     * apply to the filtered set when a status is given, so "?status=pending"
     * gives an accurate "here's what's left to review" picture rather than
     * totals that include already-reviewed changes.
     */
    public ReleaseCompareResult compareReleases(String fromRelease, String toRelease, ChangeStatus statusFilter, int limit, int offset) {
        List<Screenshot> fromShots = repository.findByRelease(fromRelease);
        List<Screenshot> toShots = repository.findByRelease(toRelease);

        Map<String, Screenshot> fromByKey = new HashMap<>();
        for (Screenshot s : fromShots) fromByKey.put(matchKey(s), s);
        Map<String, Screenshot> toByKey = new HashMap<>();
        for (Screenshot s : toShots) toByKey.put(matchKey(s), s);

        Set<String> allKeys = new TreeSet<>();
        allKeys.addAll(fromByKey.keySet());
        allKeys.addAll(toByKey.keySet());

        Map<String, ChangeAcknowledgment> acknowledgments = repository.findAllAcknowledgmentsByPairKey();

        List<ScreenshotChange> allChanges = new ArrayList<>();
        int added = 0, removed = 0, changed = 0, unchanged = 0;

        for (String key : allKeys) {
            Screenshot from = fromByKey.get(key);
            Screenshot to = toByKey.get(key);

            if (from == null) {
                added++;
                allChanges.add(buildChange(to.component(), to.windowName(), to.fileName(),
                        ScreenshotChange.ChangeType.NEW, null, to.id(), null, null, null, acknowledgments));
            } else if (to == null) {
                removed++;
                allChanges.add(buildChange(from.component(), from.windowName(), from.fileName(),
                        ScreenshotChange.ChangeType.REMOVED, from.id(), null, null, null, null, acknowledgments));
            } else if (from.sha256().equals(to.sha256())) {
                unchanged++;
                allChanges.add(buildChange(to.component(), to.windowName(), to.fileName(),
                        ScreenshotChange.ChangeType.UNCHANGED, from.id(), to.id(), 0.0, 1.0, 100.0, acknowledgments));
            } else {
                changed++;
                Double pixelDiff = null, ssim = null, phashSim = null;
                try {
                    var result = imageDiffService.diff(Path.of(from.filePath()), Path.of(to.filePath()));
                    pixelDiff = round(result.pixelDifferencePercentage());
                    ssim = round(result.ssimScore());
                    phashSim = round(result.pHashSimilarityPercent());
                } catch (Exception ignored) {
                    // hashes differ but pixel diff failed (e.g. missing file on disk) - still report as CHANGED
                }
                allChanges.add(buildChange(to.component(), to.windowName(), to.fileName(),
                        ScreenshotChange.ChangeType.CHANGED, from.id(), to.id(), pixelDiff, ssim, phashSim, acknowledgments));
            }
        }

        List<ScreenshotChange> filtered = statusFilter == null
                ? allChanges
                : allChanges.stream().filter(ch -> ch.status() == statusFilter).toList();

        // Counts reflect the filtered set when a status filter is active, so "?status=pending"
        // reports how much is actually left to review rather than unfiltered totals.
        int reportedAdded = added, reportedRemoved = removed, reportedChanged = changed, reportedUnchanged = unchanged;
        if (statusFilter != null) {
            reportedAdded = countByType(filtered, ScreenshotChange.ChangeType.NEW);
            reportedRemoved = countByType(filtered, ScreenshotChange.ChangeType.REMOVED);
            reportedChanged = countByType(filtered, ScreenshotChange.ChangeType.CHANGED);
            reportedUnchanged = countByType(filtered, ScreenshotChange.ChangeType.UNCHANGED);
        }

        int changesTotal = filtered.size();
        List<ScreenshotChange> page = filtered.stream().skip(Math.max(offset, 0)).limit(Math.max(limit, 0)).toList();

        return new ReleaseCompareResult(fromRelease, toRelease, toShots.size(),
                reportedAdded, reportedRemoved, reportedChanged, reportedUnchanged,
                changesTotal, limit, offset, page);
    }

    private ScreenshotChange buildChange(String component, String windowName, String fileName,
                                          ScreenshotChange.ChangeType type, Long fromId, Long toId,
                                          Double pixelDiff, Double ssim, Double phashSim,
                                          Map<String, ChangeAcknowledgment> acknowledgments) {
        ChangeAcknowledgment ack = acknowledgments.getOrDefault(
                ScreenshotRepository.pairKey(fromId, toId), ChangeAcknowledgment.pending(fromId, toId));
        return new ScreenshotChange(component, windowName, fileName, type, fromId, toId,
                pixelDiff, ssim, phashSim, ack.status(), ack.note());
    }

    private static int countByType(List<ScreenshotChange> changes, ScreenshotChange.ChangeType type) {
        return (int) changes.stream().filter(c -> c.changeType() == type).count();
    }

    /**
     * Everything an "automatic release report" needs: the NEW/REMOVED/CHANGED/
     * UNCHANGED comparison, plus which components and windows are entirely new
     * to this release (not just new screenshots within an existing window),
     * plus a duplicate-screen count for the target release.
     */
    public ReleaseReport buildReleaseReport(String fromRelease, String toRelease) {
        ReleaseCompareResult compare = compareReleases(fromRelease, toRelease);

        List<String> fromComponents = repository.findComponentNames(fromRelease);
        List<String> toComponents = repository.findComponentNames(toRelease);
        List<String> newComponents = toComponents.stream()
                .filter(c -> !fromComponents.contains(c))
                .toList();

        List<String> newWindows = new ArrayList<>();
        for (String component : toComponents) {
            List<String> toWindows = repository.findWindowNames(toRelease, component);
            List<String> fromWindows = fromComponents.contains(component)
                    ? repository.findWindowNames(fromRelease, component)
                    : List.of();
            for (String w : toWindows) {
                if (!fromWindows.contains(w)) newWindows.add(component + " / " + w);
            }
        }

        int duplicateScreenCount = exactDuplicates(toRelease).stream()
                .mapToInt(DuplicateGroup::count)
                .sum();

        return new ReleaseReport(
                fromRelease, toRelease, LocalDateTime.now(),
                compare.totalInToRelease(), compare.newCount(), compare.removedCount(),
                compare.changedCount(), compare.unchangedCount(), duplicateScreenCount,
                newComponents, newWindows, compare.changes()
        );
    }

    private static String matchKey(Screenshot s) {
        return s.component() + "::" + s.windowName() + "::" + s.fileName();
    }

    private static Double round(Double value) {
        return value == null ? null : Math.round(value * 100.0) / 100.0;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
