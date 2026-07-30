# Contact Sheet UI

A Vite + React app providing:

- ⚡ **Start Scan panel** — in Settings, point it at a `Repository/` folder
  and watch it scan live: current release/component, running scanned/
  imported/skipped counts, polling `/api/scan/status` every second while a
  scan is in progress. No more curl/Swagger just to trigger a scan.
- 🖼️ **Grid view** — every window in the selected release/component as a
  thumbnail card, tagged stable / minor change / regression based on a real
  diff against the previous release's screenshot of the same window+file.
- 🎞️ **Timeline view** — a filmstrip of a window's real screenshots across
  every release it appears in, connected by real diff-scored links (green =
  stable, amber = minor, red = regression). Click a connector to open the slider.
- 🔎 **Larger thumbnails, server-generated + full-size lightbox** — grid
  cards, timeline frames, and search results now load a downscaled
  server-side preview (`/api/images/{id}/thumbnail`, cached by content
  hash) instead of the full-resolution file, so browsing loads faster
  without giving up sharpness where it matters. Click the zoom icon on any
  of them (or the "open full size" links in Compare) for the real
  full-resolution image, native browser zoom/pan included.
- 🎚️ **Compare view** — a before/after reveal slider *or* a real pixel-diff
  overlay, between any two releases' screenshots of a window, with the
  pixel / pHash / SSIM score panel from `/api/diff`.
- ✅ **Approve / reject** — from Compare view, mark the pair you're looking
  at as an intentional change or a real regression, with an optional note.
  Reviewed pairs show a ✓ or ✗ on their timeline connector.
- 🧩 **Variant picker** — when a window has multiple files (e.g.
  `login.png` + `login_dark.png`), pick which one's timeline to follow.
- 🔍 **Search** — free-text search box hits `/api/search?q=`, paginated with
  a "Load more" button; click a result to jump straight to its timeline.
- 🔑 **API key field** — in Settings, for when the backend has
  `SCREENSHOT_API_KEY` set.

## Live vs demo data

On load, the UI checks `/api/releases`. If that succeeds, it's in **live
mode**: the release picker, sidebar components, windows, grid, timeline,
compare, and search are all backed by real fetches against your running
catalog (`/api/releases`, `/api/releases/{r}/components`, `.../windows`,
`/api/search`, `/api/diff`, `/api/images/{id}`). If it fails, it falls back
to **demo mode** — deterministic fake data, useful for looking at the UI
with no backend running at all.

If live mode shows an empty sidebar or "no windows found," that almost
always means the catalog itself is empty — run a scan first:
```
curl -X POST http://localhost:7070/api/scan -H "Content-Type: application/json" \
  -d '{"rootPath": "/path/to/your/Repository"}'
```

## How it's wired to the backend

`vite.config.js` builds straight into `../src/main/resources/public`, and
`ui/public/` (Vite's own static-asset convention, holding `openapi.yaml`
and `swagger-ui.html`) gets copied in alongside it automatically. So one
`npm run build` produces one output directory containing the built UI, the
OpenAPI spec, and Swagger UI together, and `mvn package` bundles that whole
directory into the runnable jar. `App.java` serves it all from a single
static-file mapping at `/`.

```
ui/public/*            \
ui/src/*.jsx  --build-->  src/main/resources/public/  --mvn package-->  screenshot-catalog.jar
```

You only need Node installed when you're **changing the UI or the OpenAPI
spec**:

```
cd ui
npm install
npm run build      # rebuilds src/main/resources/public/
cd ..
mvn clean package  # bundles the new build into the jar
```

For live-reload while developing: `npm run dev` (CORS is already enabled
server-side for any host). Note dev mode runs on a different port than the
API, so `fetch("/api/...")` (relative, same-origin) won't reach it unless
you either proxy `/api` to the real backend in `vite.config.js`, or use the
settings panel to point at `http://localhost:7070` explicitly.

## Implementation notes

- **Grid view's "trend" needs a same-filename match in the previous
  release.** It looks up the previous release's screenshot with the exact
  same `fileName` in the same window to diff against. If a screenshot was
  renamed between releases, or this is the window's first release, you'll
  see "no baseline" rather than a stable/regression badge - that's correct
  behavior, not a bug.
- **Timeline has a variant picker.** When a window has multiple distinct
  file names (e.g. `login.png` + `login_dark.png`), a small chip row lets
  you choose which one's release-over-release history to view; the filmstrip,
  diffs, and compare view all follow that selection. Grid view (which shows
  one thumbnail per window, not a history) defaults to the base file
  (`variant: null`) when there's a choice, rather than exposing a picker
  there too.
- **Search is paginated** — the search results panel shows a real "Load
  more" button backed by `/api/search`'s `limit`/`offset`, not a single
  capped page.
- **API key auth is wired up** — settings panel has a field; sent as
  `X-API-Key` on JSON calls, and as a `key=` query param on image/report
  links (`<img>`/`<a>` tags can't carry headers). If you enable
  `SCREENSHOT_API_KEY` on the backend, put the same value in here.
- **Overlay mode is done.** Compare view has a Slider/Overlay toggle. In
  live mode, overlay fetches the real `/api/diff/image` (magenta-highlighted
  pixel diff) from the backend. In demo mode (no backend), overlay uses a
  CSS `mix-blend-mode: difference` trick on the two mock SVGs as a stand-in
  - clearly not a real pixel diff, labeled as such in the UI.
- **Thumbnails vs full resolution is a deliberate split, not an oversight.**
  Grid, timeline, and search results all pass `thumbnail` to `RealImage`,
  which routes them to `/api/images/{id}/thumbnail` instead of the raw file.
  Compare view and the lightbox never do - those exist specifically for
  close inspection, so they always request full resolution. If you add a
  new place that displays a screenshot, decide deliberately which one it
  needs rather than copying whichever's nearby.
- **Approve/reject doesn't live-update other open views.** Acting on a
  change in Compare view refetches that one diff, so its own status/score
  panel is immediately correct. It does *not* reach into `timelineTransitions`
  and repaint an already-loaded timeline's connector icons - revisit the
  timeline (reselect the window, or navigate away and back) to see those
  refreshed. Fine for the ordinary "click a connector, review it" flow;
  worth knowing if you build something that expects the whole UI to stay
  in sync automatically.
- **The scan panel polls, it doesn't push.** `POST /api/scan` returns 202
  immediately; the panel then polls `GET /api/scan/status` every second
  until it sees `COMPLETED` or `FAILED`. When a scan completes, it bumps a
  `catalogRefreshKey` that re-triggers the releases fetch, so newly-scanned
  releases/components show up without a manual page reload - but anything
  already loaded for a *specific* release/window (grid items, an open
  timeline) doesn't auto-refresh, same tradeoff as the approve/reject note
  above. If a scan is already running when you open the panel, `POST
  /api/scan` returns 409 and the panel just starts reflecting the real
  progress via polling rather than showing an error.
- **Compare view depends on `timelineFrames`, and Compare and Timeline
  share the effect that loads it.** This was a real bug, not just a design
  note: that effect used to clear `timelineFrames` whenever `view !==
  "timeline"`. Clicking a timeline connector calls `openCompareAt`, which
  sets `view: "compare"` - which re-triggered that same effect, saw
  `view !== "timeline"` was now true, and wiped the frames right as Compare
  view was about to read them. The result: clicking a connector looked like
  nothing happened, because by the time Compare rendered, `frames.length`
  was back to 0 and it showed the "open a timeline first" placeholder -
  even though you'd just come from one. Fixed by treating "timeline" and
  "compare" as the same case (both need the data); only clear when there's
  truly no component/window selected, and just skip re-fetching (without
  clearing) on Grid. If you add a fourth view that also needs timeline
  frames, extend that check rather than reintroducing a view-specific clear.
