package com.screenshots.catalog.service;

import com.screenshots.catalog.dto.ReleaseReport;
import com.screenshots.catalog.dto.ScreenshotChange;
import com.screenshots.catalog.model.ChangeStatus;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Renders a ReleaseReport as a single self-contained HTML page: summary
 * counts, newly introduced components/windows, and thumbnail previews of
 * every changed/new/removed screen (most significant changes first).
 *
 * Images are referenced via /api/images/{id} and /api/diff/image?... -
 * relative links back into this same running app, not embedded. That keeps
 * the report small and always shows the current file, but it also means a
 * saved copy of the HTML only renders correctly while pointed at a server
 * that still has those ids. Worth knowing if you intend to email/archive
 * reports long-term - base64-embedding images would make them fully
 * portable at the cost of a much larger file, and isn't done here.
 *
 * "PDF export" is the browser's native print-to-PDF, triggered by the Print
 * button this page includes, using the @media print rules below - not
 * server-side PDF generation. A real HTML-to-PDF renderer (e.g. an
 * OpenHTMLtoPDF/Flying Saucer dependency, or driving a headless browser)
 * would be needed for that, and isn't wired in here.
 */
public final class ReleaseReportRenderer {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int MAX_CHANGED_SHOWN = 200;

    private ReleaseReportRenderer() {
    }

    public static String render(ReleaseReport r) {
        return render(r, null);
    }

    /**
     * @param apiKey if the server has SCREENSHOT_API_KEY set, pass the key that was used to
     *               request this report so the image/diff links embedded in the page stay
     *               authenticated too (they're plain &lt;img&gt;/&lt;a&gt; tags, not fetch()
     *               calls, so they can't carry a header - the key is appended as a query param
     *               instead). Null/blank when auth is disabled.
     */
    public static String render(ReleaseReport r, String apiKey) {
        List<ScreenshotChange> allChanged = r.changes().stream()
                .filter(c -> c.changeType() == ScreenshotChange.ChangeType.CHANGED)
                .sorted(Comparator.comparing(
                        (ScreenshotChange c) -> c.pixelDifferencePercentage() == null ? -1.0 : c.pixelDifferencePercentage()
                ).reversed())
                .toList();
        List<ScreenshotChange> added = r.changes().stream()
                .filter(c -> c.changeType() == ScreenshotChange.ChangeType.NEW)
                .toList();
        List<ScreenshotChange> removed = r.changes().stream()
                .filter(c -> c.changeType() == ScreenshotChange.ChangeType.REMOVED)
                .toList();

        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">");
        html.append("<title>Release Report: ").append(esc(r.fromRelease())).append(" \u2192 ").append(esc(r.toRelease())).append("</title>");
        html.append("<style>").append(CSS).append("</style></head><body>");

        renderHeader(html, r);
        renderSummary(html, r);
        renderNewContainers(html, r);
        renderChangedSection(html, allChanged, apiKey);
        renderThumbnailSection(html, "New screens", added, ScreenshotChange::toId, "new", apiKey);
        renderThumbnailSection(html, "Removed screens", removed, ScreenshotChange::fromId, "removed", apiKey);
        renderFooter(html, r);

        html.append("<script>function csPrint(){window.print();}</script>");
        html.append("</body></html>");
        return html.toString();
    }

    private static void renderHeader(StringBuilder html, ReleaseReport r) {
        html.append("<header><div class=\"header-row\"><div>");
        html.append("<h1>Release Report</h1>");
        html.append("<div class=\"subtitle\"><span class=\"release-badge\">").append(esc(r.fromRelease())).append("</span>");
        html.append(" &rarr; <span class=\"release-badge current\">").append(esc(r.toRelease())).append("</span></div>");
        html.append("<div class=\"generated\">Generated ").append(r.generatedAt().format(TIMESTAMP)).append("</div>");
        html.append("</div>");
        html.append("<button class=\"print-btn no-print\" onclick=\"csPrint()\">Print / Save as PDF</button>");
        html.append("</div></header>");
    }

    private static void renderSummary(StringBuilder html, ReleaseReport r) {
        html.append("<section class=\"summary\">");
        stat(html, String.valueOf(r.totalInToRelease()), "Imported", "");
        stat(html, String.valueOf(r.newCount()), "New", "stat-new");
        stat(html, String.valueOf(r.changedCount()), "Changed", "stat-changed");
        stat(html, String.valueOf(r.removedCount()), "Removed", "stat-removed");
        stat(html, String.valueOf(r.unchangedCount()), "Unchanged", "");
        stat(html, String.valueOf(r.duplicateScreenCount()), "Duplicate screens", "");
        html.append("</section>");
    }

    private static void stat(StringBuilder html, String value, String label, String cls) {
        html.append("<div class=\"stat ").append(cls).append("\"><div class=\"stat-value\">").append(value)
                .append("</div><div class=\"stat-label\">").append(esc(label)).append("</div></div>");
    }

    private static void renderNewContainers(StringBuilder html, ReleaseReport r) {
        if (r.newComponents().isEmpty() && r.newWindows().isEmpty()) return;
        html.append("<section><h2>New components &amp; windows</h2>");
        if (!r.newComponents().isEmpty()) {
            html.append("<p class=\"muted\">New components: ").append(
                    r.newComponents().stream().map(ReleaseReportRenderer::esc).collect(Collectors.joining(", "))
            ).append("</p>");
        }
        if (!r.newWindows().isEmpty()) {
            html.append("<ul class=\"plain-list\">");
            for (String w : r.newWindows()) html.append("<li>").append(esc(w)).append("</li>");
            html.append("</ul>");
        }
        html.append("</section>");
    }

    private static void renderChangedSection(StringBuilder html, List<ScreenshotChange> allChanged, String apiKey) {
        html.append("<section><h2>Changed screens (").append(allChanged.size()).append(")</h2>");
        if (allChanged.isEmpty()) {
            html.append("<p class=\"muted\">No screens changed between these two releases.</p></section>");
            return;
        }

        boolean truncated = allChanged.size() > MAX_CHANGED_SHOWN;
        List<ScreenshotChange> shown = truncated ? allChanged.subList(0, MAX_CHANGED_SHOWN) : allChanged;
        if (truncated) {
            html.append("<p class=\"muted\">Showing the top ").append(MAX_CHANGED_SHOWN)
                    .append(" by pixel-diff severity, out of ").append(allChanged.size())
                    .append(" total. Use <code>/api/compare/releases</code> with <code>limit</code>/<code>offset</code> for the rest.</p>");
        }

        html.append("<div class=\"changed-grid\">");
        for (ScreenshotChange c : shown) {
            String pct = c.pixelDifferencePercentage() == null ? "n/a" : c.pixelDifferencePercentage() + "%";
            String sev = severityClass(c.pixelDifferencePercentage());
            String fromImg = imageUrl(c.fromId(), apiKey);
            String toImg = imageUrl(c.toId(), apiKey);
            html.append("<div class=\"change-card\">");
            html.append("<div class=\"change-images\">");
            html.append("<a href=\"").append(fromImg).append("\" target=\"_blank\"><img loading=\"lazy\" src=\"").append(fromImg).append("\" alt=\"before\"></a>");
            html.append("<a href=\"").append(toImg).append("\" target=\"_blank\"><img loading=\"lazy\" src=\"").append(toImg).append("\" alt=\"after\"></a>");
            html.append("</div>");
            html.append("<div class=\"change-meta\">");
            html.append("<div class=\"change-title\">").append(esc(c.component())).append(" / ").append(esc(c.windowName())).append("</div>");
            html.append("<div class=\"change-file\">").append(esc(c.fileName())).append("</div>");
            html.append("<div class=\"change-scores\">");
            html.append("<span class=\"badge ").append(sev).append("\">").append(pct).append(" pixel diff</span>");
            html.append(statusBadge(c.status()));
            if (c.ssimScore() != null) html.append("<span class=\"badge-plain\">SSIM ").append(c.ssimScore()).append("</span>");
            if (c.pHashSimilarityPercent() != null) html.append("<span class=\"badge-plain\">pHash sim ").append(c.pHashSimilarityPercent()).append("%</span>");
            html.append("</div>");
            if (c.reviewNote() != null && !c.reviewNote().isBlank()) {
                html.append("<div class=\"review-note\">\u201c").append(esc(c.reviewNote())).append("\u201d</div>");
            }
            html.append("<a class=\"diff-link\" href=\"").append(diffImageUrl(c.fromId(), c.toId(), apiKey))
                    .append("\" target=\"_blank\">View highlighted diff &rarr;</a>");
            html.append("</div></div>");
        }
        html.append("</div></section>");
    }

    private static void renderThumbnailSection(StringBuilder html, String title, List<ScreenshotChange> items,
                                                java.util.function.Function<ScreenshotChange, Long> idFn,
                                                String cssClass, String apiKey) {
        html.append("<section><h2>").append(esc(title)).append(" (").append(items.size()).append(")</h2>");
        if (items.isEmpty()) {
            html.append("<p class=\"muted\">None.</p></section>");
            return;
        }
        html.append("<div class=\"thumb-grid\">");
        for (ScreenshotChange c : items) {
            String img = imageUrl(idFn.apply(c), apiKey);
            html.append("<div class=\"thumb-card ").append(cssClass).append("\">");
            html.append("<a href=\"").append(img).append("\" target=\"_blank\"><img loading=\"lazy\" src=\"").append(img).append("\" alt=\"").append(esc(c.fileName())).append("\"></a>");
            html.append("<div class=\"thumb-meta\">").append(esc(c.component())).append(" / ").append(esc(c.windowName()))
                    .append("<br><span class=\"muted\">").append(esc(c.fileName())).append("</span>")
                    .append(statusBadge(c.status())).append("</div>");
            html.append("</div>");
        }
        html.append("</div></section>");
    }

    private static void renderFooter(StringBuilder html, ReleaseReport r) {
        html.append("<footer>Screenshot Catalog &middot; <a href=\"/\">open interactive UI</a></footer>");
    }

    private static String imageUrl(Long id, String apiKey) {
        String base = "/api/images/" + id;
        return withKey(base, apiKey, "?");
    }

    private static String diffImageUrl(Long leftId, Long rightId, String apiKey) {
        String base = "/api/diff/image?leftId=" + leftId + "&rightId=" + rightId;
        return withKey(base, apiKey, "&");
    }

    private static String withKey(String url, String apiKey, String separator) {
        if (apiKey == null || apiKey.isBlank()) return url;
        return url + separator + "key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
    }

    private static String statusBadge(ChangeStatus status) {
        if (status == null || status == ChangeStatus.PENDING) {
            return "<span class=\"badge-status pending\">pending review</span>";
        }
        if (status == ChangeStatus.APPROVED) {
            return "<span class=\"badge-status approved\">\u2713 approved</span>";
        }
        return "<span class=\"badge-status rejected\">\u2717 rejected</span>";
    }

    private static String severityClass(Double pct) {
        if (pct == null) return "badge-minor";
        if (pct > 10) return "badge-regression";
        if (pct > 2) return "badge-minor";
        return "badge-stable";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static final String CSS = """
            :root { color-scheme: light; }
            * { box-sizing: border-box; }
            body { font-family: -apple-system, "Segoe UI", Inter, sans-serif; margin: 0; padding: 32px 40px 60px;
                   background: #F7F6F3; color: #1A1D22; }
            h1 { font-family: Georgia, "Times New Roman", serif; font-size: 28px; margin: 0 0 4px; }
            h2 { font-size: 16px; margin: 0 0 12px; padding-top: 4px; }
            header { border-bottom: 2px solid #1A1D22; padding-bottom: 16px; margin-bottom: 24px; }
            .header-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
            .print-btn { background: #14171C; color: #F7F6F3; border: none; border-radius: 6px; padding: 9px 16px;
                         font-size: 13px; cursor: pointer; white-space: nowrap; }
            .print-btn:hover { background: #2A2F3A; }
            .subtitle { font-size: 15px; margin-top: 6px; }
            .release-badge { font-family: "Courier New", monospace; background: #E8E6E1; padding: 3px 10px; border-radius: 4px; }
            .release-badge.current { background: #14171C; color: #F7F6F3; }
            .generated { color: #6B6F76; font-size: 12px; margin-top: 8px; }
            section { margin-bottom: 36px; }
            .summary { display: flex; flex-wrap: wrap; gap: 20px; background: white; border: 1px solid #E0DED8;
                       border-radius: 10px; padding: 20px; margin-bottom: 32px; }
            .stat { min-width: 90px; }
            .stat-value { font-family: "Courier New", monospace; font-size: 26px; font-weight: bold; }
            .stat-label { font-size: 11px; color: #6B6F76; text-transform: uppercase; letter-spacing: 0.04em; margin-top: 2px; }
            .stat-new .stat-value { color: #1F8A7A; }
            .stat-changed .stat-value { color: #B5721A; }
            .stat-removed .stat-value { color: #C0392B; }
            .muted { color: #6B6F76; font-size: 13px; }
            .plain-list { margin: 4px 0; padding-left: 18px; font-size: 13px; }
            .changed-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 16px; }
            .change-card { background: white; border: 1px solid #E0DED8; border-radius: 10px; overflow: hidden; }
            .change-images { display: flex; }
            .change-images img { width: 50%; display: block; object-fit: cover; aspect-ratio: 4/3; border: none; }
            .change-images a:first-child img { border-right: 1px solid #E0DED8; }
            .change-meta { padding: 12px 14px; }
            .change-title { font-weight: 600; font-size: 13px; }
            .change-file { font-family: "Courier New", monospace; font-size: 11px; color: #6B6F76; margin-top: 2px; }
            .change-scores { margin-top: 8px; display: flex; gap: 6px; flex-wrap: wrap; }
            .badge { font-size: 11px; font-family: "Courier New", monospace; padding: 3px 8px; border-radius: 999px; }
            .badge-stable { background: #DFF4F0; color: #1F8A7A; }
            .badge-minor { background: #FBEBD9; color: #B5721A; }
            .badge-regression { background: #FBE1DD; color: #C0392B; }
            .badge-plain { font-size: 11px; font-family: "Courier New", monospace; padding: 3px 8px; border-radius: 999px; background: #EFEEEA; color: #4A4E55; }
            .badge-status { font-size: 10.5px; font-family: "Courier New", monospace; padding: 3px 8px; border-radius: 999px; text-transform: uppercase; letter-spacing: 0.03em; }
            .badge-status.pending { background: #EFEEEA; color: #6B6F76; }
            .badge-status.approved { background: #DFF4F0; color: #1F8A7A; }
            .badge-status.rejected { background: #FBE1DD; color: #C0392B; font-weight: 600; }
            .review-note { margin-top: 8px; font-size: 12px; color: #4A4E55; font-style: italic; }
            .diff-link { display: inline-block; margin-top: 10px; font-size: 12px; color: #14171C; text-decoration: underline; }
            .thumb-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 14px; }
            .thumb-card { background: white; border: 1px solid #E0DED8; border-radius: 10px; overflow: hidden; }
            .thumb-card.new { border-color: #1F8A7A66; }
            .thumb-card.removed { border-color: #C0392B66; opacity: 0.85; }
            .thumb-card img { width: 100%; aspect-ratio: 4/3; object-fit: cover; display: block; }
            .thumb-meta { padding: 8px 10px; font-size: 12px; }
            footer { margin-top: 40px; padding-top: 16px; border-top: 1px solid #E0DED8; font-size: 12px; color: #6B6F76; }
            footer a { color: #14171C; }
            a { color: inherit; }
            @media print {
                body { background: white; }
                .change-card, .thumb-card, .summary { break-inside: avoid; }
                .no-print { display: none !important; }
            }
            """;
}
