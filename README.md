# Screenshot Catalog (v2 — release-first)

A visual regression / screenshot intelligence system for Swing HMI
screenshots. **v2 replaces the earlier component-first, filename-parsing
design** with a release-first repository layout, since the folder tree now
carries the metadata directly:

```
Repository/
  Release_24.1/
    Authentication/
      LoginDialog/
        login.png
        login_dark.png
      ChangePassword/
    Alarm/
      AlarmOverview/
  Release_24.2/
  Release_25.0/
```

- **Release / Component / Window** come entirely from folder names — no
  filename convention to get right. `Release_24.1` → release `"24.1"`
  (prefix stripped if present).
- **File name** is used two ways: as-is for display/search, and
  heuristically split on the last underscore for an optional **variant**
  tag (`login_dark.png` → variant `"dark"`). This is a convenience, not a
  strict format — `login.png` just gets `variant = null`.
- **Optional Swing UI metadata.** If your capture tool exports a sidecar
  JSON next to a screenshot (`login.png` → `login.json`, same folder), it's
  parsed and linked automatically — window class, theme, PLAF version,
  locale, screen resolution, and every Swing component present:

  ```json
  {
    "windowClass": "com.acme.hmi.LoginDialog",
    "theme": "Nimbus",
    "plafVersion": "1.2.0",
    "locale": "en_US",
    "screenWidth": 1920,
    "screenHeight": 1080,
    "components": [
      { "componentId": "usernameField", "swingClass": "JTextField" },
      { "componentId": "loginButton",   "swingClass": "JButton" }
    ]
  }
  ```

  No sidecar → the screenshot still imports fine, just without this data.
  This is what makes `/api/search?swingClass=JTable` ("show every screenshot
  containing a table") and `/api/search?theme=...` ("find every window on
  the old PLAF") possible — see the API table below.

Built as the same three layers as before, now against this layout:

1. **File-based dataset (source of truth)** — the `Repository/` tree itself.
2. **Indexed metadata store** — H2 + Flyway, normalized as
   `releases → components → windows → screenshots`, with an optional
   `screenshot_metadata` / `screenshot_components` sidecar layer for Swing
   UI details (see below).
3. **Image diffing layer** — unchanged: `ImageDiffService` combines pixel
   diff, perceptual hash (pHash), and SSIM.

A React UI (`ui/`, Vite-built) is served by this same app at `/` — see
[`ui/README.md`](ui/README.md) for how the build is wired in and what's
still demo data vs real.

## Features

- 📁 **Release-first scanning** — point it at a `Repository/` folder; the
  folder tree *is* the metadata, no filename convention required. Images
  sitting directly in a component folder (no window subfolder) are still
  cataloged, under a synthetic window named `(root)` - with a warning
  telling you it happened, not a silent drop.
- ⚡ **Async scanning with live progress** — `POST /api/scan` returns
  immediately and runs in the background (one connection reused for the
  whole scan, not one per file - a real, measured fix for scans that used
  to take the better part of an hour); the UI has a "Start Scan" panel that
  polls `/api/scan/status` and shows it happening in real time.
- 🔍 **Multi-facet search** — release, component, window, filename, date,
  resolution, file size, hash, free text, and (if you export it) Swing
  metadata — paginated
- 🔄 **Release comparison** — NEW / CHANGED / REMOVED / UNCHANGED between
  any two releases, matched by component + window + filename
- ✅ **Approve/reject review workflow** — mark a change as an intentional
  update or a real regression, with an optional note; recognized whether you
  review it from a timeline connector or the release report; filter
  `/api/compare/releases` down to just what's still unreviewed
- 🖼️ **Three-signal image diffing** — exact pixel diff, perceptual hash
  (survives resolution differences), and SSIM, all computed together
- 🎯 **Duplicate detection** — exact (SHA-256) and near-duplicate
  (perceptual hash) search, both backed by hashes stored at scan time
- 🧩 **Optional Swing UI metadata** — sidecar JSON per screenshot (window
  class, theme, PLAF version, locale, components) — no sidecar, no problem
- 📊 **Automatic release report** — standalone HTML page, thumbnails sorted
  by significance, plus a print/save-as-PDF button
- 🖥️ **React UI** — grid, timeline (filmstrip with diff-scored connectors),
  and slider/overlay compare views, all backed by the real API
- 🎚️ **Variant picker** — for windows with multiple files (e.g.
  `login.png` + `login_dark.png`), pick which one's history to follow
- 🔐 **Security controls, off by default** — scan-path allowlist, symlink
  rejection, optional API key, rate limiting, CORS allowlist — all tuned so
  local testing needs zero configuration
- 📄 **OpenAPI spec + Swagger UI** at `/swagger-ui.html`
- ✅ **Real test suite** — H2-backed, exercises scanning, search, diffing,
  comparison, duplicates, security, and the report end-to-end

## Stack

JDK 25 · Javalin 6 · H2 · Flyway · Jackson · JUnit 5 · Maven. OpenAPI spec is
hand-maintained at `ui/public/openapi.yaml`, served via Swagger UI
at `/swagger-ui.html`. Both live alongside the built React UI in `ui/public/`
and get bundled into `src/main/resources/public/` by `npm run build` -
one static output directory, one Javalin static-file mapping, served all
together at `/`.

## Running

```
mvn clean package
java -jar target/screenshot-catalog.jar
```

Every setting below can be set three ways, in this order of precedence
(highest wins): an **environment variable**, a value in a **properties
file**, or the built-in default. The properties file is looked for at
`./config.properties` (relative to wherever you launch the jar from) unless
`SCREENSHOT_CONFIG_FILE` points somewhere else - it's entirely optional, and
if it doesn't exist everything just falls back to env vars/defaults exactly
as before. Copy `config.properties.example` (in the project root) to
`config.properties` and uncomment what you need - same key names either way,
e.g. `SCREENSHOT_PORT=8080`. The properties file is for settings you want to
keep for a machine or check into version control; env vars are for
overriding just one thing for one run (a container, CI, etc.) without
touching the file.

| Env var / properties key    | Default                                              |
|-----------------------------|-------------------------------------------------------|
| `SCREENSHOT_DB_URL`         | `jdbc:h2:file:./data/screenshot-catalog;AUTO_SERVER=TRUE` |
| `SCREENSHOT_DB_USER`        | `sa`                                                  |
| `SCREENSHOT_DB_PASSWORD`    | *(empty)*                                             |
| `SCREENSHOT_PORT`           | `7070`                                                |
| `SCREENSHOT_ALLOWED_ROOTS`  | your home directory + the app's working directory (comma-separated to override) |
| `SCREENSHOT_API_KEY`        | *(unset = auth disabled)*                             |
| `SCREENSHOT_ALLOWED_ORIGINS`| *(unset = only localhost + Vite's default dev port)*  |
| `SCREENSHOT_RATE_LIMIT_PER_MINUTE` | `300` per client IP, on `/api/scan`, `/api/diff`, `/api/diff/image` |

`SCREENSHOT_CONFIG_FILE` itself is env-var-only (it has to be, to know which
file to read before any properties-file value could apply) - it's not a
properties-file key.

Swagger UI: `http://localhost:7070/swagger-ui.html`

## How scanning actually runs

**Threading.** This is one Javalin app on Jetty, which handles each HTTP
request on its own worker thread from a pool - so browsing the catalog in
one tab while a scan runs (triggered from another tab, curl, or Swagger)
generally works fine even without anything below, since it's a different
request on a different thread hitting the database through its own
connection.

**But `POST /api/scan` itself used to be a problem.** The endpoint ran the
whole scan synchronously on whatever thread picked up that one HTTP request
- the caller was stuck waiting for the entire scan before getting a
response, with no way to check progress except watching server logs, and no
"Start Scan" button in the UI at all (only reachable via curl/Swagger).
Fixed now:

- **`POST /api/scan` returns immediately (202)** by default. The scan runs
  on a single dedicated background thread (`ScreenshotScanCoordinator`).
  Add `?sync=true` if you want the old blocking behavior back (useful for
  scripts/CI that want to wait for the result) - it still goes through the
  same coordinator, so it can't race with an async-triggered scan.
- **`GET /api/scan/status`** reports live progress: current release/
  component, running scanned/imported/skipped counts, and the final result
  or error once it's done. The Contact Sheet UI has a "Start Scan" panel in
  Settings now that uses exactly this - type a path, hit Start, watch it go.
- **Only one scan runs at a time, by design, not by accident.** The
  get-or-create hierarchy and rescan cleanup were built assuming a single
  writer with no locking (see "Design notes"); running two scans
  concurrently could produce duplicate rows or one scan's cleanup deleting
  something the other just imported. A second `POST /api/scan` while one is
  running gets a `409`, not a silent race.

**The other real fix: connection-per-operation was the actual bottleneck.**
A scan used to open a brand-new physical database connection for every
single get-or-create/upsert/metadata call - for one file with sidecar
metadata, that's 5 separate connections opened and closed. At even a few
hundred files, that's thousands of connection opens, and if the H2 file
lives on a network share or VM-mounted folder (slow per-connection I/O),
this compounds badly - a scan of ~120 files was measured taking the better
part of an hour before this fix, almost entirely on connection overhead,
not the actual image work. `ScreenshotRepository.ScanSession` now reuses
**one** connection for an entire scan.

Progress still prints to stdout as it runs, same as before - useful for
watching a scan from the server console directly instead of polling the API:

```
2026-07-29 12:59:19  [scan] Scan started: /data/Repository
2026-07-29 12:59:19  [scan] Release '24.1'
2026-07-29 12:59:19  [scan]   Component 'Auth'
2026-07-29 12:59:19  [scan]     Window 'LoginDialog' - 2 image file(s)
2026-07-29 12:59:19  [scan]       + new     login.png
2026-07-29 12:59:19  [scan]       ~ updated login_dark.png
2026-07-29 12:59:19  [scan]   Component 'Alarm'
2026-07-29 12:59:19  [scan]     Window 'AlarmOverview' - 1 image file(s)
2026-07-29 12:59:19  [scan]       ! Skipped (could not import corrupt.png: Could not decode image)
2026-07-29 12:59:19  [scan] Scan finished in 0.2s: scanned=3 imported=2 skipped=1 removedMissing=0
```

`+ new` vs `~ updated` reflects whether that file path was already in the
catalog before this scan - not whether its *content* changed (that's what
release comparison is for). A symlinked directory being skipped shows up
here immediately too (`! Skipped ... it's a symlink ...`), not just at the
end in the JSON response's `warnings` field. Every 50 files, a running
counter line prints too, so a long scan doesn't look stalled.

## Security model

Every default above is chosen so **`java -jar` + open `localhost:7070` needs
zero configuration** - auth is off, the rate limit is generous, and the scan
allowlist covers the two places a `Repository/` folder realistically lives
for local testing (your home directory, and wherever you launched the jar
from). Nothing below changes that unless you explicitly set an env var.

What's actually enforced, and why each exists:

- **`POST /api/scan` is restricted to an allowlist of root directories**
  (`SCREENSHOT_ALLOWED_ROOTS`), not "anywhere on disk." Without this, the
  endpoint would happily walk and read any path a caller supplied - `/etc`,
  someone's `.ssh` folder, whatever. Requests outside the allowlist get a
  403 with the reason, logged server-side too.
- **Symlinks are never followed during a scan**, at every level (release/
  component/window/file), and the allowlist check resolves the *real* path
  before checking it - both close the same hole: a symlink inside an
  otherwise-allowed folder pointing somewhere outside it, which would
  otherwise let a scan (and then `/api/images/{id}`) reach files outside the
  intended scope. **A skipped symlinked directory now shows up in the scan
  result's `warnings`**, naming the exact path - it used to be silent, which
  made a symlinked component/window directory indistinguishable from a
  genuinely empty one (a real, reported source of confusion: components
  would list correctly but windows underneath would come back empty with no
  clue why). If your capture/sync setup relies on symlinks or junctions
  anywhere in the tree (common on Windows shares or some VM-mounted folders),
  check `warnings` after a scan.
- **A scan always runs against the canonicalized real path**
  (`ScanPathValidator`'s `toRealPath()`), never the raw string a caller
  typed. This was a real bug: if the same root got typed even slightly
  differently between two scans (different case on a case-insensitive
  mount, a trailing slash, a different alias for the same VM-shared folder),
  every computed file path would shift, and the rescan cleanup would
  interpret the *entire* previous catalog as deleted while re-importing
  everything under the new path strings - `removedMissing` equal to
  `imported` equal to `filesScanned`, every single scan. Canonicalizing once,
  before scanning, makes file identity stable regardless of how the root is
  typed on any given call.
- **Optional API key auth** (`SCREENSHOT_API_KEY`) - if set, every `/api/*`
  request needs a matching key, checked in constant time. Off by default.
  Accepted two ways: an `X-API-Key` header (normal API calls, and what the
  UI sends) or a `key=` query param (for plain `<img src>`/`<a href>` tags,
  which can't carry headers - the release report's embedded image links use
  this). The UI's settings panel has a field for it now.
- **CORS is an explicit allowlist**, not `anyHost()`. Doesn't affect the
  normal same-origin workflow at all (CORS only applies cross-origin) - it
  matters if you run `npm run dev` against a real backend, or host the UI
  separately. Add more origins via `SCREENSHOT_ALLOWED_ORIGINS`.
- **Rate limiting** on the three endpoints that do real work per request
  (`/api/scan` walks a filesystem tree and hashes every file; `/api/diff`
  and `/api/diff/image` do pixel-by-pixel comparison) - an in-memory
  sliding window per client IP, generous enough that normal use never hits
  it.
- **Errors are sanitized at the top level.** Any exception that isn't
  already caught by a specific route handler gets logged in full server-side
  but returns a generic message to the caller - not a stack trace. Routes
  that already catch their own exceptions (most of them) still return a
  specific message; those are intentionally more detailed since you're
  usually the one who triggered them.
- **Every request is logged** (method, path, client IP, timestamp) to
  stdout, and `/api/scan` requests specifically log the requested path and
  whether it was allowed or rejected.
- **TLS is not implemented here.** This app speaks plain HTTP. If you're
  running it somewhere reachable over an untrusted network, put a reverse
  proxy (Caddy, nginx) in front to terminate TLS - more robust than
  hand-rolling Jetty SSL config in the app, and the standard pattern for a
  small Javalin service like this one.

## API

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/scan?sync=` | `{"rootPath": "/path/to/Repository"}` — starts a scan. Async by default: returns 202 immediately, runs on a background thread. Add `?sync=true` to block and get the full result back instead (old behavior) — still goes through the same single-scan guard. |
| GET | `/api/scan/status` | Poll this while a scan is running: status, current release/component, live scanned/imported/skipped counts, and the final result or error once done |
| GET | `/api/releases` | All releases, in release order |
| GET | `/api/releases/{release}/components` | Components within a release |
| GET | `/api/releases/{release}/components/{component}/windows` | Windows within a component |
| GET | `/api/search?release=&component=&window=&fileName=&dateFrom=&dateTo=&minWidth=&maxWidth=&minHeight=&maxHeight=&minSizeBytes=&maxSizeBytes=&sha256=&q=&swingClass=&theme=&plafVersion=&locale=&windowClass=&limit=&offset=` | Multi-facet search, every param optional. Paginated - returns `{items, total, limit, offset}`, not a bare array. Default `limit=100`, capped at 500. |
| GET | `/api/screenshots/{id}` | Full detail: screenshot facts + Swing metadata (if any) + component list |
| GET | `/api/swing-classes` | Every distinct Swing class in the catalog, for a search UI's autocomplete |
| GET | `/api/compare/releases?from=&to=&limit=&offset=&status=` | Release ↔ release: NEW / REMOVED / CHANGED / UNCHANGED (JSON). `changes` is paginated (default `limit=200`, capped 1000). Add `status=pending\|approved\|rejected` to narrow both the list and the counts to just that review status. |
| GET | `/api/changes/status?leftId=&rightId=` | Current review status for a screenshot pair (defaults to PENDING if never reviewed) |
| POST | `/api/changes/status` | `{leftId, rightId, status, note?, acknowledgedBy?}` — approve, reject, or reset a change. Same pair is recognized whether it came from a release comparison or an ad-hoc `/api/diff` call. |
| GET | `/api/reports/release?from=&to=[&download=1]` | Same comparison as an HTML page - print/save-as-PDF button included (browser print-to-PDF, not server-side PDF generation) |
| GET | `/api/diff?leftId=&rightId=` | Full diff (pixel + pHash + SSIM) between any two screenshots |
| GET | `/api/diff/image?leftId=&rightId=` | PNG diff image |
| GET | `/api/similar/{id}?maxDistance=` | Perceptual-hash similarity search, catalog-wide (default distance ≤ 8 of 63) |
| GET | `/api/duplicates?release=` | Exact-duplicate groups (SHA-256) within a release |
| GET | `/api/images/{id}` | Raw, full-resolution image bytes |
| GET | `/api/images/{id}/thumbnail?maxDimension=` | Downscaled PNG preview (default 480px longer side), cached by content hash — used for grid/timeline browsing |


## Design notes

- **Images directly in a component folder get a synthetic `(root)` window,
  not silently dropped.** The scanner expects `Component/Window/*.png`, but
  a plain file where a window subdirectory was expected used to just get
  filtered out with no trace - the same silent-skip problem symlinks had
  (see below) before that got fixed, and a real one reported in practice.
  Now those files are cataloged under a window literally named `(root)`
  (parentheses so it can't collide with a real folder someone happens to
  name "root"), and the scan result's `warnings` says so explicitly,
  including how many files and which component. If you want a real window
  name instead, just put the images in a subfolder.
- **Review status is keyed by screenshot pair, not by "change."** A "change"
  in `/api/compare/releases` isn't a stored row — it's computed fresh every
  call by comparing two releases. What *is* stored is a decision on a
  `(leftId, rightId)` pair (`change_acknowledgments` table), so the same
  approval is recognized whether you reach that pair via a timeline
  connector (`/api/diff`) or a release comparison — both read and write
  through the same table. Re-submitting a decision for the same pair
  overwrites it rather than creating a second row (get-or-create in
  application code, same pattern used for releases/components/windows —
  there's no DB-level unique constraint here either, since H2 doesn't treat
  two NULLs as equal, which would let duplicate rows in for NEW/REMOVED
  pairs where one side is null).
- **Approving/rejecting doesn't retroactively repaint an already-loaded
  timeline.** Act on a change from Compare view and the score panel there
  updates immediately; a timeline you had open before that action keeps
  showing its connectors' old ✓/✗ state until you reload or reselect the
  window. Known scope boundary, not a bug — full cross-view state
  invalidation wasn't worth the complexity here.
- **Matching across releases** uses `(component, window, fileName)` as the
  identity key — the natural assumption being that `login.png` under
  `Authentication/LoginDialog` in one release *is* the same screen in the
  next release, just possibly changed. If your capture tool ever renames
  files between releases, that screen will show up as REMOVED + NEW instead
  of CHANGED — worth knowing going in.
- **SHA-256 and pHash are computed once, at scan time**, and stored on the
  row — not recomputed per request. That's what makes `/api/duplicates` and
  `/api/similar` cheap: duplicate detection is a `GROUP BY sha256 HAVING
  COUNT(*) > 1`, not a pairwise image comparison across the whole catalog.
- **`/api/similar` does the Hamming-distance filter in Java**, not SQL —
  simplest correct thing for a per-release-scale catalog; would want a
  proper index (e.g. bucketed by hash prefix) if this grows to hundreds of
  thousands of screenshots.
- **Rescanning is self-cleaning, scoped per window.** For every window
  directory actually visited during a scan, any screenshot previously
  cataloged in that window whose file wasn't seen this time is deleted
  (cascading to its metadata/components). This is scoped by `window_id`
  rather than a file-path string prefix — a prefix match against an
  absolute path is fragile across platforms (Windows in particular can
  resolve the same temp/working directory to short 8.3 vs long path forms
  inconsistently between calls, which would silently break a string-prefix
  match with no exception — a real bug an earlier version of this had).
  One consequence: if a whole window/component/release folder is deleted
  from disk entirely, its old rows are left behind, since that window is
  never visited to trigger its cleanup. That's a known limitation, not a
  bug — worth an explicit "prune releases no longer on disk" endpoint if
  it matters to you.
- Get-or-create and cleanup both assume a single scan running at a time —
  no locking — so don't run two scans of the same repository concurrently.
- **The release report links to images, doesn't embed them.** `/api/reports/release`
  builds a self-contained HTML *document*, but the `<img>` tags inside it
  point at `/api/images/{id}` on the running server, not embedded base64
  data. Great for viewing/printing while the app is running (small file,
  always shows the current image); a saved copy won't render images if
  opened later against a server that no longer has those ids, or emailed to
  someone without access to the server. Worth embedding images if you need
  a truly portable archive copy - not done here to keep report generation
  fast and reports small.

## Performance notes

- **`/api/search?exact=true`** — release/component/window/fileName match
  exactly instead of the default case-insensitive substring `LIKE`. The UI's
  internal lookups (grid view, timeline) always use this, since they already
  have a complete value from a prior API response, not user-typed partial
  text — only the free-text search box needs substring matching. Measured
  against a synthetic 180,000-screenshot / 36,000-window dataset in H2, this
  is a real (if modest at that scale — ~10ms → ~4ms) improvement, and more
  correct: without it, searching for component `Auth` would also match
  `AuthService`.
- **Grid view fetches all windows in parallel now**, not one-at-a-time. If a
  release/component has many windows, this used to mean N sequential
  round-trips (search + baseline-search + diff, per window); now they all
  fire together. **Timeline view's per-release diffs are now parallel too**
  (were still a sequential loop until this point).
- **`components.name` and `windows.name` now have plain indexes** (migration
  `V3__add_name_indexes.sql`), in addition to the composite unique indexes
  from V1. The composite ones `(release_id, name)` / `(component_id, name)`
  don't help a name-only lookup since `name` isn't the leading column -
  these plain indexes close that gap for both exact and substring search.
- **If search is still slow for you specifically**, the SQL itself measured
  fast even at 180k rows/36k windows in isolated testing here, which points
  elsewhere: how large is your actual catalog (total screenshot count)? Is
  the H2 database file (`./data/screenshot-catalog.mv.db` by default) on a
  network share or a VM-shared/mounted folder rather than local disk?
  Embedded databases like H2 are frequently much slower on networked or
  virtualized filesystems than on native disk. And is it slow on every
  request, or just the first one after startup (JVM/JIT warmup)?
- **Thumbnails are content-addressed, not id-addressed.** `/api/images/{id}/thumbnail`
  caches in memory keyed by the screenshot's SHA-256, not its id. If it were
  keyed by id, a rescan that updates a screenshot's file (same id, new
  content) would keep serving the *old* thumbnail from cache — content
  hashing sidesteps that entirely, and gets automatic thumbnail sharing
  across exact-duplicate files for free. The cache is in-process memory
  only (bounded to 300 entries, evicting oldest-used first) — it resets on
  restart and isn't shared across multiple app instances, which is fine at
  the scale this is built for but worth knowing if that changes.

## Deliberately not built

Each of these is a real separate decision, not a small addition — flagged
rather than silently skipped:

- 🗣️ **OCR (Tesseract/Tess4J)** — needs a native `tesseract` binary + trained
  language data on the host, not just a Maven dependency. Would add an
  `ocr_text` column and a `q`-style full-text search over it.
- 🌈 **OpenCV heatmap overlay** — powerful, but pulls in large
  platform-specific native binaries (bigger jar, bigger deploy surface).
  The current diff image (magenta highlight) is the light-dependency
  version of the same idea.
- 🖨️ **True server-side PDF generation** — the release report's PDF export
  is the browser's native print-to-PDF, not server-side rendering. I
  checked: the only Java PDF library reachable here (PDFBox, via apt) does
  low-level PDF construction, not HTML/CSS rendering, so it can't produce
  this report's layout. A real HTML-to-PDF renderer (OpenHTMLtoPDF/Flying
  Saucer, or driving headless Chrome) would be needed for a single portable
  file generated without a browser in the loop.
- 🎨 **Material UI** — `ui/` is hand-styled, not MUI. A bounded swap if
  you want it.

## Tests

```
mvn test
```

26 tests across 6 files, all against a real in-memory H2 instance:
`ScreenshotRepositoryTest` builds a small synthetic `Repository/` tree on
disk (two releases, a changed screenshot, a new one, a removed one, an
exact duplicate, a symlink escape attempt) and scans it for real, then
asserts on search, duplicate detection, similarity, and release comparison
end-to-end. `PaginationTest`, `ReleaseReportTest`, `ScanPathValidatorTest`,
`RateLimiterTest`, and `ImageDiffServiceComponentsTest` cover the rest.

## ⚠️ Known limitation

Written without network access to Maven Central, so it has not been
compiled/run in this environment. Worth a build check before relying on it.
