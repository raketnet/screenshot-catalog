import { useState, useMemo, useEffect, useCallback } from "react";
import {
  Search, Settings2, LayoutGrid, Film, SlidersHorizontal, X, RefreshCw,
  Wifi, WifiOff, ChevronLeft, Loader2, ImageOff, FileText, Layers, Key, ZoomIn,
  Check, XCircle, Play, AlertTriangle, FolderSearch,
} from "lucide-react";

/* ---------------------------------------------------------------------- */
/* Deterministic PRNG - lets demo data regenerate identically every load   */
/* ---------------------------------------------------------------------- */
function xmur3(str) {
  let h = 1779033703 ^ str.length;
  for (let i = 0; i < str.length; i++) {
    h = Math.imul(h ^ str.charCodeAt(i), 3432918353);
    h = (h << 13) | (h >>> 19);
  }
  return function () {
    h = Math.imul(h ^ (h >>> 16), 2246822507);
    h = Math.imul(h ^ (h >>> 13), 3266489909);
    h ^= h >>> 16;
    return h >>> 0;
  };
}
function mulberry32(a) {
  return function () {
    a |= 0; a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
function seededRand(key) {
  return mulberry32(xmur3(key)());
}

/* Sorts version-ish strings ("1.9" before "1.10") numeric-segment by segment. */
function compareVersions(a, b) {
  const norm = (s) => (s || "").replace(/^v/i, "").split(".");
  const pa = norm(a), pb = norm(b);
  const len = Math.max(pa.length, pb.length);
  for (let i = 0; i < len; i++) {
    const xa = pa[i] ?? "0", xb = pb[i] ?? "0";
    if (/^\d+$/.test(xa) && /^\d+$/.test(xb)) {
      const diff = parseInt(xa, 10) - parseInt(xb, 10);
      if (diff !== 0) return diff;
    } else if (xa !== xb) {
      return xa < xb ? -1 : 1;
    }
  }
  return 0;
}

const enc = encodeURIComponent;

/** Appends ?key=/&key= to a plain URL (for <img src>/<a href>, which can't send headers). */
function withKey(url, apiKey) {
  if (!apiKey) return url;
  const sep = url.includes("?") ? "&" : "?";
  return `${url}${sep}key=${enc(apiKey)}`;
}

/* ---------------------------------------------------------------------- */
/* Demo dataset (used only when there's no live backend connection)        */
/* ---------------------------------------------------------------------- */
const VERSIONS = ["1.0.0", "1.1.0", "1.2.0", "1.3.0", "2.0.0", "2.1.0"];

const COMPONENTS = {
  Auth: ["LoginForm", "SignupForm", "ForgotPassword", "TwoFactor"],
  Checkout: ["CartSummary", "PaymentForm", "OrderConfirmation"],
  Dashboard: ["Overview", "AnalyticsPanel", "NotificationsDrawer"],
  Settings: ["ProfileEditor", "BillingPage", "TeamMembers"],
  Onboarding: ["WelcomeScreen", "ProductTour", "SetupWizard"],
};

function styleVariant(seedKey, variantIndex) {
  const rand = seededRand(seedKey + "|variant|" + variantIndex);
  return {
    bgHue: Math.floor(rand() * 360),
    headerHue: Math.floor(rand() * 360),
    accentHue: Math.floor(rand() * 360),
    buttonX: 30 + Math.floor(rand() * 240),
    buttonY: 150 + Math.floor(rand() * 55),
    barWidth: 70 + Math.floor(rand() * 170),
    lineSeed: Math.floor(rand() * 1000),
  };
}

function buildWindowSeries(component, window) {
  const seedKey = component + "::" + window;
  const rand = seededRand(seedKey + "|changepoints");
  const changePointCount = 1 + Math.floor(rand() * 2);
  const candidates = [];
  for (let i = 1; i < VERSIONS.length; i++) candidates.push(i);
  const changePoints = new Set();
  while (changePoints.size < changePointCount && candidates.length) {
    const idx = Math.floor(rand() * candidates.length);
    changePoints.add(candidates.splice(idx, 1)[0]);
  }
  let variantIndex = 0;
  return VERSIONS.map((version, i) => {
    if (changePoints.has(i)) variantIndex++;
    return { version, variantIndex, style: styleVariant(seedKey, variantIndex) };
  });
}

function demoTransitionScore(seedKey, from, to) {
  if (from.variantIndex === to.variantIndex) {
    const rand = seededRand(seedKey + "|noise|" + from.variantIndex);
    const pixelDiff = +(rand() * 0.6).toFixed(2);
    return { pixelDifferencePercentage: pixelDiff, ssimScore: +(1 - (pixelDiff / 100) * 0.3).toFixed(4), pHashDistance: 0, pHashSimilarityPercent: +(100 - pixelDiff * 0.15).toFixed(2), dimensionsMatch: true };
  }
  const rand = seededRand(seedKey + "|change|" + from.variantIndex + ">" + to.variantIndex);
  const pixelDiff = +(15 + rand() * 30).toFixed(2);
  const phashDist = 6 + Math.floor(rand() * 20);
  return { pixelDifferencePercentage: pixelDiff, ssimScore: +(0.9 - (pixelDiff / 100) * 0.5).toFixed(4), pHashDistance: phashDist, pHashSimilarityPercent: +(100 * (1 - phashDist / 63)).toFixed(2), dimensionsMatch: true };
}

function severity(score) {
  if (!score) return "stable";
  if (score.pixelDifferencePercentage == null) return "minor";
  if (score.pixelDifferencePercentage > 10) return "regression";
  if (score.pixelDifferencePercentage > 2) return "minor";
  return "stable";
}
const SEVERITY_COLOR = { stable: "#4FD1C5", minor: "#F0A868", regression: "#E8604C" };

function ScreenshotArt({ style, caption }) {
  const rand = seededRand("lines|" + style.lineSeed);
  const lines = Array.from({ length: 4 }, () => 24 + Math.floor(rand() * 180));
  return (
    <svg viewBox="0 0 400 260" width="100%" height="100%" preserveAspectRatio="xMidYMid slice">
      <rect x="0" y="0" width="400" height="260" fill={`hsl(${style.bgHue},30%,95%)`} />
      <rect x="0" y="0" width="400" height="34" fill={`hsl(${style.headerHue},40%,42%)`} />
      {[0, 1, 2].map((d) => <circle key={d} cx={16 + d * 14} cy="17" r="4" fill="rgba(255,255,255,0.6)" />)}
      {lines.map((w, i) => <rect key={i} x="30" y={58 + i * 20} width={w} height="8" rx="2" fill="rgba(20,23,28,0.12)" />)}
      <rect x={style.buttonX} y={style.buttonY} width={style.barWidth} height="30" rx="6" fill={`hsl(${style.accentHue},70%,50%)`} />
      <rect x="0" y="0" width="400" height="260" fill="none" stroke="rgba(20,23,28,0.15)" strokeWidth="2" />
      {caption && <text x="12" y="252" fontFamily="JetBrains Mono, monospace" fontSize="10" fill="rgba(20,23,28,0.45)">{caption}</text>}
    </svg>
  );
}

/** Real screenshot image via /api/images/{id}, with a fallback if it 404s. apiKey (if set) goes on the query string - <img> can't send headers.
 *  If onZoom is given, shows a small zoom button (stopPropagation'd, so it won't trigger a parent card's onClick) that opens the full-resolution lightbox. */
/** Real screenshot image - full resolution via /api/images/{id}, or a server-generated downscaled
 *  preview via /api/images/{id}/thumbnail when thumbnail is true (grid/timeline/search browsing -
 *  full resolution is reserved for the lightbox, compare view, and "open full size" links). */
function RealImage({ id, caption, apiKey, onZoom, thumbnail }) {
  const [failed, setFailed] = useState(false);
  useEffect(() => { setFailed(false); }, [id]);
  if (!id || failed) {
    return (
      <div style={{ display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", height: "100%", color: "#8B8F99", background: "#1C2028", gap: 8 }}>
        <ImageOff size={22} />
        <span style={{ fontSize: 11, fontFamily: "JetBrains Mono, monospace" }}>{caption || "no image"}</span>
      </div>
    );
  }
  const src = thumbnail ? withKey(`/api/images/${id}/thumbnail?maxDimension=480`, apiKey) : withKey(`/api/images/${id}`, apiKey);
  return (
    <div style={{ position: "relative", width: "100%", height: "100%" }}>
      <img src={src} onError={() => setFailed(true)} alt={caption || "screenshot"} style={{ width: "100%", height: "100%", objectFit: "cover", display: "block" }} />
      {onZoom && (
        <button
          onClick={(e) => { e.stopPropagation(); onZoom(id); }}
          title="View full size"
          style={styles.zoomButton}
        >
          <ZoomIn size={14} />
        </button>
      )}
    </div>
  );
}

/** Full-resolution overlay viewer. Closes on backdrop click, the × button, or Escape. */
function Lightbox({ id, apiKey, onClose }) {
  useEffect(() => {
    function onKey(e) { if (e.key === "Escape") onClose(); }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  if (!id) return null;
  return (
    <div style={styles.lightboxBackdrop} onClick={onClose}>
      <button style={styles.lightboxClose} onClick={onClose} title="Close (Esc)"><X size={20} /></button>
      <img
        src={withKey(`/api/images/${id}`, apiKey)}
        alt="full size screenshot"
        onClick={(e) => e.stopPropagation()}
        style={styles.lightboxImage}
      />
    </div>
  );
}

/** Trigger + live progress for POST /api/scan. Polling and the actual fetch call live in the
 *  root component (ContactSheet) - this is just the presentation. */
function ScanPanel({ scanRootPath, setScanRootPath, onStart, starting, startError, status }) {
  const running = status && status.status === "RUNNING";
  const done = status && status.status === "COMPLETED";
  const failed = status && status.status === "FAILED";

  return (
    <div style={styles.scanPanel}>
      <div style={{ display: "flex", alignItems: "center", gap: 6, marginBottom: 8 }}>
        <FolderSearch size={13} color="#8B8F99" />
        <span style={{ fontSize: 12, color: "#8B8F99", fontFamily: "JetBrains Mono, monospace" }}>Scan a Repository/ folder</span>
      </div>
      <div style={{ display: "flex", gap: 8 }}>
        <input
          value={scanRootPath}
          onChange={(e) => setScanRootPath(e.target.value)}
          placeholder="/path/to/Repository"
          disabled={running}
          style={styles.settingsInput}
        />
        <button onClick={onStart} disabled={starting || running || !scanRootPath.trim()} style={styles.primaryButton}>
          {running ? <Loader2 size={13} className="cs-spin" /> : <Play size={13} />}
          {running ? "Scanning…" : "Start Scan"}
        </button>
      </div>

      {startError && (
        <div style={{ marginTop: 8, fontSize: 12, color: "#E8604C", display: "flex", alignItems: "center", gap: 6 }}>
          <AlertTriangle size={13} /> {startError}
        </div>
      )}

      {status && (
        <div style={{ marginTop: 10, fontSize: 12, fontFamily: "JetBrains Mono, monospace" }}>
          {running && (
            <div style={{ color: "#F0A868" }}>
              ● scanning{status.currentRelease ? ` — release '${status.currentRelease}'` : ""}
              {status.currentComponent ? ` / '${status.currentComponent}'` : ""}
              <br />
              <span style={{ color: "#8B8F99" }}>
                {status.filesScanned || 0} scanned · {status.imported || 0} imported · {status.skipped || 0} skipped
              </span>
            </div>
          )}
          {done && status.result && (
            <div style={{ color: "#4FD1C5", display: "flex", alignItems: "flex-start", gap: 6 }}>
              <Check size={13} style={{ marginTop: 2 }} />
              <span>
                Done — scanned {status.result.filesScanned}, imported {status.result.imported}, skipped {status.result.skipped}
                {status.result.removedMissing > 0 && `, removed ${status.result.removedMissing}`}.
                {status.result.warnings && status.result.warnings.length > 0 &&
                  ` ${status.result.warnings.length} warning(s) - see server logs / GET /api/scan/status.`}
              </span>
            </div>
          )}
          {failed && (
            <div style={{ color: "#E8604C", display: "flex", alignItems: "center", gap: 6 }}>
              <AlertTriangle size={13} /> Scan failed: {status.errorMessage || "unknown error"}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/* ---------------------------------------------------------------------- */
/* Root app                                                                 */
/* ---------------------------------------------------------------------- */
export default function ContactSheet() {
  const [backendUrl, setBackendUrl] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [liveStatus, setLiveStatus] = useState("checking"); // checking | demo | live | error
  const [catalogRefreshKey, setCatalogRefreshKey] = useState(0);
  const [scanRootPath, setScanRootPath] = useState("");
  const [scanStatus, setScanStatus] = useState(null); // last-known GET /api/scan/status snapshot, or null if never checked
  const [scanStarting, setScanStarting] = useState(false);
  const [scanStartError, setScanStartError] = useState(null);

  const [settingsOpen, setSettingsOpen] = useState(false);
  const live = liveStatus === "live";

  const [query, setQuery] = useState("");
  const [searchResults, setSearchResults] = useState(null); // {items, total, limit, offset} | null
  const [searchLoadingMore, setSearchLoadingMore] = useState(false);

  const [releases, setReleases] = useState([]);
  const [selectedRelease, setSelectedRelease] = useState(null);
  const [liveComponents, setLiveComponents] = useState([]);
  const [liveWindows, setLiveWindows] = useState([]);

  const [selectedComponent, setSelectedComponent] = useState(Object.keys(COMPONENTS)[0]);
  const [selectedWindow, setSelectedWindow] = useState(COMPONENTS[Object.keys(COMPONENTS)[0]][0]);
  const [selectedFileName, setSelectedFileName] = useState(null); // variant picker - null = "any/first"
  const [view, setView] = useState("grid");

  const [gridItems, setGridItems] = useState([]);
  const [gridLoading, setGridLoading] = useState(false);

  const [timelineFrames, setTimelineFrames] = useState([]);
  const [timelineTransitions, setTimelineTransitions] = useState([]);
  const [timelineFileNames, setTimelineFileNames] = useState([]); // every distinct file for this window
  const [timelineLoading, setTimelineLoading] = useState(false);

  const [compareIds, setCompareIds] = useState([null, null]);
  const [compareDiff, setCompareDiff] = useState(null);
  const [sliderPos, setSliderPos] = useState(50);
  const [compareMode, setCompareMode] = useState("slider"); // slider | overlay
  const [lightboxId, setLightboxId] = useState(null); // screenshot id currently shown full-size, or null

  const [compareIdx, setCompareIdx] = useState([0, VERSIONS.length - 1]); // demo mode only

  /** Key-aware JSON fetch - attaches X-API-Key when apiKey is set. Used for all real /api/* JSON calls. */
  const apiFetch = useCallback(async (url) => {
    const headers = apiKey ? { "X-API-Key": apiKey } : {};
    const r = await fetch(url, { headers });
    if (!r.ok) throw new Error(`${r.status} ${r.statusText}`);
    return r.json();
  }, [apiKey]);

  const apiPost = useCallback(async (url, body) => {
    const headers = { "Content-Type": "application/json", ...(apiKey ? { "X-API-Key": apiKey } : {}) };
    const r = await fetch(url, { method: "POST", headers, body: JSON.stringify(body) });
    if (!r.ok) throw new Error(`${r.status} ${r.statusText}`);
    return r.json();
  }, [apiKey]);

  /** Starts a scan (async by default - the server returns 202 immediately and runs it in the
   *  background). A 409 means someone/something else already has one running - rather than
   *  treat that as an error, just start reflecting its real progress via polling. */
  async function startScan() {
    if (!scanRootPath.trim()) return;
    setScanStarting(true);
    setScanStartError(null);
    try {
      const headers = { "Content-Type": "application/json", ...(apiKey ? { "X-API-Key": apiKey } : {}) };
      const r = await fetch("/api/scan", { method: "POST", headers, body: JSON.stringify({ rootPath: scanRootPath }) });
      const body = await r.json().catch(() => null);
      if (r.status === 202 || r.status === 409) {
        setScanStatus({ status: "RUNNING", rootPath: scanRootPath, filesScanned: 0, imported: 0, skipped: 0 });
      } else {
        setScanStartError((body && body.error) || `${r.status} ${r.statusText}`);
      }
    } catch (e) {
      setScanStartError(String(e));
    } finally {
      setScanStarting(false);
    }
  }

  /* --- poll scan status while a scan is running; refresh the catalog once it completes --- */
  useEffect(() => {
    if (!scanStatus || scanStatus.status !== "RUNNING") return;
    let cancelled = false;
    const interval = setInterval(async () => {
      try {
        const snap = await apiFetch("/api/scan/status");
        if (cancelled) return;
        setScanStatus(snap);
        if (snap.status === "COMPLETED" || snap.status === "FAILED") {
          clearInterval(interval);
          if (snap.status === "COMPLETED") setCatalogRefreshKey((k) => k + 1);
        }
      } catch {
        // transient error while polling - just try again next tick
      }
    }, 1000);
    return () => { cancelled = true; clearInterval(interval); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [scanStatus && scanStatus.status === "RUNNING", apiFetch]);

  /* --- connect + load releases on mount (and whenever catalogRefreshKey bumps, e.g. after a scan) --- */
  useEffect(() => {
    apiFetch("/api/releases")
      .then((rs) => {
        setReleases(rs);
        setLiveStatus("live");
        if (rs.length) setSelectedRelease((prev) => prev && rs.includes(prev) ? prev : rs[rs.length - 1]);
      })
      .catch(() => setLiveStatus("demo"));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [catalogRefreshKey]);

  function testConnection() {
    if (!backendUrl.trim()) { setLiveStatus("demo"); return; }
    setLiveStatus("checking");
    apiFetch(backendUrl.replace(/\/$/, "") + "/api/releases")
      .then((rs) => { setReleases(rs); setLiveStatus("live"); if (rs.length) setSelectedRelease(rs[rs.length - 1]); })
      .catch(() => setLiveStatus("error"));
  }

  /* --- load components when release changes (live) --- */
  useEffect(() => {
    if (!live || !selectedRelease) return;
    apiFetch(`/api/releases/${enc(selectedRelease)}/components`)
      .then((cs) => { setLiveComponents(cs); if (cs.length && !cs.includes(selectedComponent)) setSelectedComponent(cs[0]); })
      .catch(() => setLiveComponents([]));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [live, selectedRelease, apiFetch]);

  /* --- load windows when component changes (live) --- */
  useEffect(() => {
    if (!live || !selectedRelease || !selectedComponent) return;
    apiFetch(`/api/releases/${enc(selectedRelease)}/components/${enc(selectedComponent)}/windows`)
      .then((ws) => { setLiveWindows(ws); if (ws.length && !ws.includes(selectedWindow)) setSelectedWindow(ws[0]); })
      .catch(() => setLiveWindows([]));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [live, selectedRelease, selectedComponent, apiFetch]);

  /* --- debounced free-text search across the whole catalog (live), paginated --- */
  useEffect(() => {
    if (!live || !query.trim()) { setSearchResults(null); return; }
    const handle = setTimeout(() => {
      apiFetch(`/api/search?q=${enc(query)}&limit=60&offset=0`).then(setSearchResults).catch(() => setSearchResults({ items: [], total: 0, limit: 60, offset: 0 }));
    }, 300);
    return () => clearTimeout(handle);
  }, [live, query, apiFetch]);

  function loadMoreSearchResults() {
    if (!searchResults) return;
    setSearchLoadingMore(true);
    const nextOffset = searchResults.offset + searchResults.limit;
    apiFetch(`/api/search?q=${enc(query)}&limit=${searchResults.limit}&offset=${nextOffset}`)
      .then((page) => setSearchResults((prev) => ({ ...page, items: [...prev.items, ...page.items] })))
      .finally(() => setSearchLoadingMore(false));
  }

  /* --- grid data: one representative screenshot per window in the selected release, + trend vs previous release --- */
  useEffect(() => {
    if (!live || view !== "grid" || !selectedRelease || !selectedComponent || liveWindows.length === 0) { setGridItems([]); return; }
    let cancelled = false;
    setGridLoading(true);
    const sortedReleases = [...releases].sort(compareVersions);
    const idx = sortedReleases.indexOf(selectedRelease);
    const prevRelease = idx > 0 ? sortedReleases[idx - 1] : null;

    (async () => {
      const results = await Promise.all(liveWindows.map(async (w) => {
        try {
          const page = await apiFetch(`/api/search?release=${enc(selectedRelease)}&component=${enc(selectedComponent)}&window=${enc(w)}&exact=true&limit=50`);
          const shots = page.items;
          if (!shots.length) return { window: w, screenshot: null };
          // Prefer the base file (no variant suffix) as the representative thumbnail, if present.
          const shot = shots.find((s) => !s.variant) || shots[0];
          let diff = null;
          if (prevRelease) {
            try {
              const prevPage = await apiFetch(`/api/search?release=${enc(prevRelease)}&component=${enc(selectedComponent)}&window=${enc(w)}&fileName=${enc(shot.fileName)}&exact=true`);
              if (prevPage.items.length) diff = await apiFetch(`/api/diff?leftId=${prevPage.items[0].id}&rightId=${shot.id}`);
            } catch { /* no baseline available - fine */ }
          }
          return { window: w, screenshot: shot, diff, variantCount: shots.length };
        } catch {
          return { window: w, screenshot: null, error: true };
        }
      }));
      if (!cancelled) { setGridItems(results); setGridLoading(false); }
    })();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [live, view, selectedRelease, selectedComponent, liveWindows, releases, apiFetch]);

  /* --- timeline data: this window's screenshots across every release, + pairwise diffs ---
     If the window has multiple distinct file names (variants, e.g. login.png / login_dark.png),
     selectedFileName picks which one's history to show; defaults to the first seen. */
  useEffect(() => {
    // Compare view depends on this same data (frames.length, the version dropdowns) - it's reached
    // by clicking a timeline connector, which switches view to "compare". Treating that as "not
    // timeline, so clear everything" wiped the very data Compare needed the instant you clicked -
    // frames.length dropped to 0 right as the view that reads it was rendering, showing the
    // "open a timeline first" placeholder even though you just came from one. Timeline and Compare
    // both need this fetched/kept; only Grid (and no component/window selected) don't.
    if (!live || !selectedComponent || !selectedWindow) {
      setTimelineFrames([]); setTimelineTransitions([]); setTimelineFileNames([]);
      return;
    }
    if (view !== "timeline" && view !== "compare") {
      return; // on Grid: leave whatever was last loaded alone, just don't fetch for it right now
    }
    let cancelled = false;
    setTimelineLoading(true);
    (async () => {
      try {
        const page = await apiFetch(`/api/search?component=${enc(selectedComponent)}&window=${enc(selectedWindow)}&exact=true&limit=500`);
        const shots = page.items;

        const distinctFileNames = [...new Set(shots.map((s) => s.fileName))].sort();
        const activeFileName = (selectedFileName && distinctFileNames.includes(selectedFileName))
            ? selectedFileName
            : distinctFileNames[0];

        const relevant = shots.filter((s) => s.fileName === activeFileName);
        const byRelease = new Map();
        for (const s of relevant) if (!byRelease.has(s.release)) byRelease.set(s.release, s);
        const frames = [...byRelease.entries()]
            .map(([release, screenshot]) => ({ release, screenshot }))
            .sort((a, b) => compareVersions(a.release, b.release));

        const transitionPairs = [];
        for (let i = 1; i < frames.length; i++) {
          transitionPairs.push([frames[i - 1].screenshot.id, frames[i].screenshot.id]);
        }
        const transitions = await Promise.all(transitionPairs.map(([leftId, rightId]) =>
          apiFetch(`/api/diff?leftId=${leftId}&rightId=${rightId}`).catch(() => null)
        ));
        if (!cancelled) {
          setTimelineFileNames(distinctFileNames);
          if (activeFileName !== selectedFileName) setSelectedFileName(activeFileName);
          setTimelineFrames(frames);
          setTimelineTransitions(transitions);
          if (frames.length >= 2) setCompareIds([frames[0].screenshot.id, frames[frames.length - 1].screenshot.id]);
        }
      } finally {
        if (!cancelled) setTimelineLoading(false);
      }
    })();
    return () => { cancelled = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [live, view, selectedComponent, selectedWindow, selectedFileName, apiFetch]);

  /* --- compare data: diff between the two currently selected screenshot ids --- */
  useEffect(() => {
    if (!live || view !== "compare" || !compareIds[0] || !compareIds[1]) { setCompareDiff(null); return; }
    apiFetch(`/api/diff?leftId=${compareIds[0]}&rightId=${compareIds[1]}`).then(setCompareDiff).catch(() => setCompareDiff(null));
  }, [live, view, compareIds, apiFetch]);

  /** Approve/reject/reset the currently-compared pair, then refresh the diff so the panel reflects it.
   *  Note: this doesn't retroactively update an already-loaded timeline's connector icons - revisit the
   *  timeline (or reselect the window) to see those refreshed. */
  const [acknowledging, setAcknowledging] = useState(false);
  async function acknowledgeChange(status, note) {
    const [leftId, rightId] = compareIds;
    if (!leftId || !rightId) return;
    setAcknowledging(true);
    try {
      await apiPost("/api/changes/status", { leftId, rightId, status, note: note || null });
      const refreshed = await apiFetch(`/api/diff?leftId=${leftId}&rightId=${rightId}`);
      setCompareDiff(refreshed);
    } catch {
      // leave the UI showing the previous status rather than a silent inconsistency
    } finally {
      setAcknowledging(false);
    }
  }

  function selectComponent(c) {
    setSelectedComponent(c);
    setSelectedFileName(null);
    if (live) { setSelectedWindow(null); }
    else setSelectedWindow(COMPONENTS[c][0]);
    setView("grid");
  }
  function openTimeline(windowName) {
    setSelectedWindow(windowName);
    setSelectedFileName(null);
    setView("timeline");
  }
  function openCompareAt(leftId, rightId) {
    setCompareIds([leftId, rightId]);
    setSliderPos(50);
    setView("compare");
  }
  function openSearchResult(r) {
    setQuery("");
    setSearchResults(null);
    setSelectedRelease(r.release);
    setSelectedComponent(r.component);
    setSelectedWindow(r.windowName);
    setSelectedFileName(r.fileName);
    setView("timeline");
  }

  const componentNames = live ? liveComponents : Object.keys(COMPONENTS);
  const filteredComponents = (!live && query) ? componentNames.filter((c) => c.toLowerCase().includes(query.toLowerCase())) : componentNames;
  const windowsForSelected = live ? liveWindows : (COMPONENTS[selectedComponent] || []);

  const sortedReleases = useMemo(() => [...releases].sort(compareVersions), [releases]);
  const previousRelease = useMemo(() => {
    if (!selectedRelease) return null;
    const idx = sortedReleases.indexOf(selectedRelease);
    return idx > 0 ? sortedReleases[idx - 1] : null;
  }, [sortedReleases, selectedRelease]);

  const demoSeries = useMemo(() => (live ? [] : buildWindowSeries(selectedComponent, selectedWindow)), [live, selectedComponent, selectedWindow]);
  const demoSeedKey = selectedComponent + "::" + selectedWindow;
  const demoTransitions = useMemo(() => {
    if (live) return [];
    const out = [];
    for (let i = 1; i < demoSeries.length; i++) out.push(demoTransitionScore(demoSeedKey, demoSeries[i - 1], demoSeries[i]));
    return out;
  }, [live, demoSeries, demoSeedKey]);

  return (
    <div style={styles.app}>
      <style>{`
        @import url('https://fonts.googleapis.com/css2?family=Fraunces:opsz,wght@9..144,500;9..144,600&family=Inter:wght@400;500;600&family=JetBrains+Mono:wght@400;500&display=swap');
        * { box-sizing: border-box; }
        ::selection { background: #4FD1C5; color: #14171C; }
        input[type=range].cs-slider { -webkit-appearance: none; appearance: none; background: transparent; }
        input[type=range].cs-slider::-webkit-slider-thumb { -webkit-appearance: none; width: 0; height: 0; }
        .cs-scroll::-webkit-scrollbar { width: 8px; height: 8px; }
        .cs-scroll::-webkit-scrollbar-thumb { background: #2A2F3A; border-radius: 4px; }
        @keyframes cs-spin { to { transform: rotate(360deg); } }
        .cs-spin { animation: cs-spin 1s linear infinite; }
      `}</style>

      <header style={styles.header}>
        <div style={{ display: "flex", alignItems: "baseline", gap: 10 }}>
          <span style={styles.wordmark}>Contact Sheet</span>
          <span style={styles.wordmarkSub}>screenshot intelligence</span>
        </div>

        {live && (
          <>
            <select value={selectedRelease || ""} onChange={(e) => setSelectedRelease(e.target.value)} style={styles.releaseSelect}>
              {sortedReleases.map((r) => <option key={r} value={r}>{r}</option>)}
            </select>
            {previousRelease && (
              <a
                href={withKey(`/api/reports/release?from=${enc(previousRelease)}&to=${enc(selectedRelease)}`, apiKey)}
                target="_blank" rel="noreferrer"
                style={styles.reportLink}
                title={`Release report: ${previousRelease} \u2192 ${selectedRelease}`}
              >
                <FileText size={13} /> Report
              </a>
            )}
          </>
        )}

        <div style={styles.searchWrap}>
          <Search size={14} color="#8B8F99" />
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={live ? "Search components, windows, files…" : "Search components…  (fuzzy — try 'auth' or 'check')"}
            style={styles.searchInput}
          />
        </div>

        <button style={styles.iconButton} onClick={() => setSettingsOpen((s) => !s)} title="Backend settings">
          {live ? <Wifi size={16} color="#4FD1C5" /> : <Settings2 size={16} color="#8B8F99" />}
        </button>
      </header>

      {settingsOpen && (
        <div style={styles.settingsPanel}>
          <div style={{ fontFamily: "JetBrains Mono, monospace", fontSize: 12, color: "#8B8F99", marginBottom: 8 }}>
            This page is served by the Screenshot Catalog API itself, so it's already checking the same origin —
            {live ? " and reached it, showing your real catalog." : " but couldn't reach it, so you're seeing demo data."} Only
            fill in a backend URL if you want to point at a <em>different</em> API instance.
          </div>
          <div style={{ display: "flex", gap: 8, marginBottom: 8 }}>
            <input value={backendUrl} onChange={(e) => setBackendUrl(e.target.value)} placeholder="http://localhost:7070 (optional)" style={styles.settingsInput} />
            <button style={styles.primaryButton} onClick={testConnection}><RefreshCw size={13} /> Connect</button>
            {backendUrl && <button style={styles.iconButton} onClick={() => { setBackendUrl(""); setLiveStatus("demo"); }}><X size={14} color="#8B8F99" /></button>}
          </div>
          <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
            <Key size={13} color="#8B8F99" />
            <input
              value={apiKey}
              onChange={(e) => setApiKey(e.target.value)}
              type="password"
              placeholder="API key (only needed if the server has SCREENSHOT_API_KEY set)"
              style={styles.settingsInput}
            />
          </div>
          <div style={{ marginTop: 8, fontSize: 12, fontFamily: "JetBrains Mono, monospace" }}>
            {liveStatus === "checking" && <span style={{ color: "#F0A868" }}>● checking…</span>}
            {liveStatus === "demo" && <span style={{ color: "#8B8F99" }}>● demo data</span>}
            {liveStatus === "live" && <span style={{ color: "#4FD1C5" }}>● connected — {releases.length} release{releases.length === 1 ? "" : "s"} found</span>}
            {liveStatus === "error" && (
              <span style={{ color: "#E8604C" }}>
                <WifiOff size={12} style={{ verticalAlign: "-2px", marginRight: 4 }} />
                couldn't reach that URL, or the API key was rejected (401). Falling back to demo data.
              </span>
            )}
          </div>
          {live && <ScanPanel scanRootPath={scanRootPath} setScanRootPath={setScanRootPath}
            onStart={startScan} starting={scanStarting} startError={scanStartError}
            status={scanStatus} />}
        </div>
      )}

      <div style={styles.body}>
        <nav style={styles.sidebar} className="cs-scroll">
          <div style={styles.sidebarLabel}>Components</div>
          {filteredComponents.map((c) => (
            <button key={c} onClick={() => selectComponent(c)} style={{ ...styles.componentItem, ...(c === selectedComponent ? styles.componentItemActive : {}) }}>
              {c}
              {!live && <span style={styles.componentCount}>{COMPONENTS[c].length}</span>}
            </button>
          ))}
          {filteredComponents.length === 0 && <div style={{ color: "#8B8F99", fontSize: 13, padding: "8px 4px" }}>No components{live ? " in this release" : ` match "${query}"`}</div>}
        </nav>

        <main style={styles.main}>
          {live && searchResults !== null ? (
            <SearchResultsView results={searchResults} query={query} onOpen={openSearchResult} onClear={() => setQuery("")} onLoadMore={loadMoreSearchResults} loadingMore={searchLoadingMore} apiKey={apiKey} onZoom={setLightboxId} />
          ) : (
            <>
              <div style={styles.viewSwitch}>
                <ViewTab icon={<LayoutGrid size={14} />} label="Grid" active={view === "grid"} onClick={() => setView("grid")} />
                <ViewTab icon={<Film size={14} />} label="Timeline" active={view === "timeline"} onClick={() => setView("timeline")} />
                <ViewTab icon={<SlidersHorizontal size={14} />} label="Compare" active={view === "compare"} onClick={() => setView("compare")} />
                <div style={{ flex: 1 }} />
                <div style={styles.breadcrumb}>
                  {selectedComponent} <ChevronLeft size={12} style={{ transform: "rotate(180deg)", margin: "0 4px" }} /> {selectedWindow || "—"}
                </div>
              </div>

              {view === "grid" && (
                live ? (
                  <LiveGridView items={gridItems} loading={gridLoading} onOpen={openTimeline} apiKey={apiKey} onZoom={setLightboxId} />
                ) : (
                  <GridView component={selectedComponent} windows={windowsForSelected} onOpen={openTimeline} />
                )
              )}

              {view === "timeline" && (
                live ? (
                  <LiveTimelineView
                    windows={windowsForSelected}
                    selectedWindow={selectedWindow}
                    onSelectWindow={setSelectedWindow}
                    frames={timelineFrames}
                    transitions={timelineTransitions}
                    loading={timelineLoading}
                    onCompare={openCompareAt}
                    fileNames={timelineFileNames}
                    selectedFileName={selectedFileName}
                    onSelectFileName={setSelectedFileName}
                    apiKey={apiKey}
                    onZoom={setLightboxId}
                  />
                ) : (
                  <TimelineView
                    component={selectedComponent}
                    windowName={selectedWindow}
                    windows={windowsForSelected}
                    onSelectWindow={setSelectedWindow}
                    series={demoSeries}
                    transitions={demoTransitions}
                    onCompare={(i1, i2) => { setCompareIdx([i1, i2]); setSliderPos(50); setView("compare"); }}
                  />
                )
              )}

              {view === "compare" && (
                live ? (
                  <LiveCompareView frames={timelineFrames} compareIds={compareIds} setCompareIds={setCompareIds} diff={compareDiff} sliderPos={sliderPos} setSliderPos={setSliderPos} compareMode={compareMode} setCompareMode={setCompareMode} apiKey={apiKey} onAcknowledge={acknowledgeChange} acknowledging={acknowledging} />
                ) : (
                  <CompareView series={demoSeries} compareIdx={compareIdx} setCompareIdx={setCompareIdx} sliderPos={sliderPos} setSliderPos={setSliderPos} seedKey={demoSeedKey} compareMode={compareMode} setCompareMode={setCompareMode} />
                )
              )}
            </>
          )}
        </main>
      </div>
      {live && <Lightbox id={lightboxId} apiKey={apiKey} onClose={() => setLightboxId(null)} />}
    </div>
  );
}

function ViewTab({ icon, label, active, onClick }) {
  return <button onClick={onClick} style={{ ...styles.viewTab, ...(active ? styles.viewTabActive : {}) }}>{icon} {label}</button>;
}

/* ---------------------------------------------------------------------- */
/* Live: search results (paginated)                                         */
/* ---------------------------------------------------------------------- */
function SearchResultsView({ results, query, onOpen, onClear, onLoadMore, loadingMore, apiKey, onZoom }) {
  const { items, total } = results;
  const hasMore = items.length < total;
  return (
    <div>
      <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 16 }}>
        <span style={{ fontSize: 13, color: "#8B8F99" }}>{items.length} of {total} result{total === 1 ? "" : "s"} for "{query}"</span>
        <button onClick={onClear} style={{ ...styles.windowChip, padding: "4px 10px" }}>Clear</button>
      </div>
      {items.length === 0 && <div style={{ color: "#8B8F99", fontSize: 13 }}>No matches. Try a different term, or run a scan first via <code>POST /api/scan</code>.</div>}
      <div style={styles.grid}>
        {items.map((r) => (
          <button key={r.id} style={styles.card} onClick={() => onOpen(r)}>
            <div style={styles.cardArt}><RealImage id={r.id} caption={r.fileName} apiKey={apiKey} onZoom={onZoom} thumbnail /></div>
            <div style={styles.cardFooter}>
              <span style={styles.cardTitle}>{r.windowName}</span>
              <span style={{ ...styles.badge, background: "#2A2F3A", color: "#8B8F99" }}>{r.release}</span>
            </div>
            <div style={{ padding: "0 12px 10px", fontSize: 11, color: "#8B8F99", fontFamily: "JetBrains Mono, monospace" }}>{r.component} / {r.fileName}</div>
          </button>
        ))}
      </div>
      {hasMore && (
        <div style={{ marginTop: 16 }}>
          <button onClick={onLoadMore} disabled={loadingMore} style={styles.primaryButton}>
            {loadingMore ? <Loader2 size={13} className="cs-spin" /> : null} Load more ({total - items.length} remaining)
          </button>
        </div>
      )}
    </div>
  );
}

/* ---------------------------------------------------------------------- */
/* Demo grid view                                                           */
/* ---------------------------------------------------------------------- */
function GridView({ component, windows, onOpen }) {
  return (
    <div style={styles.grid}>
      {windows.map((w) => {
        const series = buildWindowSeries(component, w);
        const last = series[series.length - 1];
        const prev = series[series.length - 2];
        const score = demoTransitionScore(component + "::" + w, prev, last);
        const sev = severity(score);
        return (
          <button key={w} style={styles.card} onClick={() => onOpen(w)}>
            <div style={styles.cardArt}><ScreenshotArt style={last.style} caption={`${w} · ${last.version}`} /></div>
            <div style={styles.cardFooter}>
              <span style={styles.cardTitle}>{w}</span>
              <span style={{ ...styles.badge, background: SEVERITY_COLOR[sev] + "22", color: SEVERITY_COLOR[sev] }}>{sev === "stable" ? "stable" : sev === "minor" ? "minor Δ" : "regression"}</span>
            </div>
          </button>
        );
      })}
    </div>
  );
}

/* ---------------------------------------------------------------------- */
/* Live grid view                                                           */
/* ---------------------------------------------------------------------- */
function LiveGridView({ items, loading, onOpen, apiKey, onZoom }) {
  if (loading && items.length === 0) return <LoadingRow label="Loading windows…" />;
  if (items.length === 0) return <div style={{ color: "#8B8F99", fontSize: 13 }}>No windows found for this release/component. Run a scan (<code>POST /api/scan</code>) first.</div>;
  return (
    <div style={styles.grid}>
      {items.map(({ window: w, screenshot, diff, error, variantCount }) => {
        const sev = severity(diff);
        return (
          <button key={w} style={styles.card} onClick={() => onOpen(w)}>
            <div style={styles.cardArt}>
              {error || !screenshot ? <RealImage id={null} caption={error ? "error" : "no screenshot"} /> : <RealImage id={screenshot.id} caption={screenshot.fileName} apiKey={apiKey} onZoom={onZoom} thumbnail />}
            </div>
            <div style={styles.cardFooter}>
              <span style={styles.cardTitle}>{w}</span>
              {diff && <span style={{ ...styles.badge, background: SEVERITY_COLOR[sev] + "22", color: SEVERITY_COLOR[sev] }}>{sev === "stable" ? "stable" : sev === "minor" ? "minor Δ" : "regression"}</span>}
              {!diff && screenshot && <span style={{ ...styles.badge, background: "#2A2F3A", color: "#8B8F99" }}>no baseline</span>}
            </div>
            {variantCount > 1 && <div style={{ padding: "0 12px 10px", fontSize: 10.5, color: "#8B8F99" }}>{variantCount} variants — showing base file</div>}
          </button>
        );
      })}
    </div>
  );
}

/* ---------------------------------------------------------------------- */
/* Demo timeline view                                                       */
/* ---------------------------------------------------------------------- */
function TimelineView({ component, windowName, windows, onSelectWindow, series, transitions, onCompare }) {
  return (
    <div>
      <div style={styles.windowPicker}>
        {windows.map((w) => <button key={w} onClick={() => onSelectWindow(w)} style={{ ...styles.windowChip, ...(w === windowName ? styles.windowChipActive : {}) }}>{w}</button>)}
      </div>
      <div style={styles.filmstripWrap} className="cs-scroll">
        <div style={styles.filmstrip}>
          {series.map((frame, i) => (
            <div key={frame.version} style={{ display: "flex", alignItems: "center" }}>
              <div style={styles.frame}>
                <div style={styles.sprocket} />
                <div style={styles.frameArt}><ScreenshotArt style={frame.style} /></div>
                <div style={styles.sprocket} />
                <div style={styles.frameLabel}>{frame.version}</div>
              </div>
              {i < transitions.length && <Connector score={transitions[i]} onClick={() => onCompare(i, i + 1)} />}
            </div>
          ))}
        </div>
      </div>
      <Legend />
    </div>
  );
}

/* ---------------------------------------------------------------------- */
/* Live timeline view (with variant picker when a window has >1 file)       */
/* ---------------------------------------------------------------------- */
function LiveTimelineView({ windows, selectedWindow, onSelectWindow, frames, transitions, loading, onCompare, fileNames, selectedFileName, onSelectFileName, apiKey, onZoom }) {
  return (
    <div>
      <div style={styles.windowPicker}>
        {windows.map((w) => <button key={w} onClick={() => onSelectWindow(w)} style={{ ...styles.windowChip, ...(w === selectedWindow ? styles.windowChipActive : {}) }}>{w}</button>)}
      </div>

      {fileNames.length > 1 && (
        <div style={{ marginBottom: 14 }}>
          <span style={{ fontSize: 11.5, color: "#8B8F99", marginRight: 8 }}>This window has {fileNames.length} files - showing:</span>
          {fileNames.map((f) => (
            <button key={f} onClick={() => onSelectFileName(f)} style={{ ...styles.variantChip, ...(f === selectedFileName ? styles.variantChipActive : {}) }}>
              {f}
            </button>
          ))}
        </div>
      )}

      {loading && <LoadingRow label="Loading timeline…" />}

      {!loading && frames.length === 0 && (
        <div style={{ color: "#8B8F99", fontSize: 13 }}>No screenshots found for this window across any release yet.</div>
      )}

      {!loading && frames.length > 0 && (
        <>
          <div style={styles.filmstripWrap} className="cs-scroll">
            <div style={styles.filmstrip}>
              {frames.map((frame, i) => (
                <div key={frame.release} style={{ display: "flex", alignItems: "center" }}>
                  <div style={styles.frame}>
                    <div style={styles.sprocket} />
                    <div style={styles.frameArt}><RealImage id={frame.screenshot.id} caption={frame.screenshot.fileName} apiKey={apiKey} onZoom={onZoom} thumbnail /></div>
                    <div style={styles.sprocket} />
                    <div style={styles.frameLabel}>{frame.release}</div>
                  </div>
                  {i < transitions.length && (
                    <Connector score={transitions[i]} onClick={() => onCompare(frames[i].screenshot.id, frames[i + 1].screenshot.id)} />
                  )}
                </div>
              ))}
            </div>
          </div>
          <Legend />
        </>
      )}
    </div>
  );
}

function Connector({ score, onClick }) {
  const sev = severity(score);
  const color = SEVERITY_COLOR[sev];
  const label = score ? (score.pixelDifferencePercentage != null ? `${score.pixelDifferencePercentage}%` : "n/a") : "…";
  const status = score?.status;
  return (
    <button onClick={onClick} style={styles.connectorWrap} title={statusTitle(status)}>
      <div style={{ ...styles.connectorLine, background: color }} />
      <div style={{ ...styles.connectorLabel, color }}>
        {label}
        {status === "APPROVED" && <span style={{ color: "#4FD1C5", marginLeft: 3 }}>✓</span>}
        {status === "REJECTED" && <span style={{ color: "#E8604C", marginLeft: 3 }}>✗</span>}
      </div>
      <div style={{ ...styles.connectorLine, background: color }} />
    </button>
  );
}

function statusTitle(status) {
  if (status === "APPROVED") return "Reviewed and approved - click to compare";
  if (status === "REJECTED") return "Reviewed and rejected - click to compare";
  return "Compare these two releases";
}

function Legend() {
  return (
    <div style={styles.legend}>
      <LegendDot color={SEVERITY_COLOR.stable} label="stable (< 2% pixel diff)" />
      <LegendDot color={SEVERITY_COLOR.minor} label="minor change (2–10%)" />
      <LegendDot color={SEVERITY_COLOR.regression} label="regression (> 10%)" />
      <span style={{ color: "#8B8F99", fontSize: 12, marginLeft: 8 }}>click a connector to open the slider compare</span>
    </div>
  );
}
function LegendDot({ color, label }) {
  return (
    <span style={{ display: "inline-flex", alignItems: "center", gap: 6, fontSize: 12, color: "#8B8F99", marginRight: 16 }}>
      <span style={{ width: 8, height: 8, borderRadius: 999, background: color, display: "inline-block" }} />
      {label}
    </span>
  );
}
function LoadingRow({ label }) {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 8, color: "#8B8F99", fontSize: 13, padding: "24px 0" }}>
      <Loader2 size={16} className="cs-spin" /> {label}
    </div>
  );
}

/* ---------------------------------------------------------------------- */
/* Demo compare view                                                        */
/* ---------------------------------------------------------------------- */
function CompareView({ series, compareIdx, setCompareIdx, sliderPos, setSliderPos, seedKey, compareMode, setCompareMode }) {
  const [i1, i2] = compareIdx;
  const left = series[i1], right = series[i2];
  const score = demoTransitionScore(seedKey, left, right);
  const sev = severity(score);
  return (
    <div>
      <div style={styles.compareControls}>
        <VersionSelect label="Before" versions={series.map((s) => s.version)} value={i1} onChange={(v) => setCompareIdx([v, i2])} />
        <span style={{ color: "#8B8F99" }}>vs</span>
        <VersionSelect label="After" versions={series.map((s) => s.version)} value={i2} onChange={(v) => setCompareIdx([i1, v])} />
        <div style={{ flex: 1 }} />
        <ModeToggle mode={compareMode} setMode={setCompareMode} />
      </div>
      {compareMode === "slider" ? (
        <SliderStage sliderPos={sliderPos} setSliderPos={setSliderPos}
          left={<ScreenshotArt style={left.style} caption={`before · ${left.version}`} />}
          right={<ScreenshotArt style={right.style} caption={`after · ${right.version}`} />} />
      ) : (
        <OverlayStage>
          <DemoOverlay left={left} right={right} />
        </OverlayStage>
      )}
      {compareMode === "overlay" && <OverlayHint demo />}
      <ScorePanel score={score} sev={sev} />
    </div>
  );
}

/* ---------------------------------------------------------------------- */
/* Live compare view                                                        */
/* ---------------------------------------------------------------------- */
function LiveCompareView({ frames, compareIds, setCompareIds, diff, sliderPos, setSliderPos, compareMode, setCompareMode, apiKey, onAcknowledge, acknowledging }) {
  const [leftId, rightId] = compareIds;
  if (frames.length < 2) {
    return <div style={{ color: "#8B8F99", fontSize: 13 }}>Open a window's timeline first and click a connector (or pick two releases there) to compare.</div>;
  }
  const sev = severity(diff);
  return (
    <div>
      <div style={styles.compareControls}>
        <VersionSelect label="Before" versions={frames.map((f) => f.release)} value={frames.findIndex((f) => f.screenshot.id === leftId)} onChange={(idx) => setCompareIds([frames[idx].screenshot.id, rightId])} />
        <span style={{ color: "#8B8F99" }}>vs</span>
        <VersionSelect label="After" versions={frames.map((f) => f.release)} value={frames.findIndex((f) => f.screenshot.id === rightId)} onChange={(idx) => setCompareIds([leftId, frames[idx].screenshot.id])} />
        <div style={{ flex: 1 }} />
        <ModeToggle mode={compareMode} setMode={setCompareMode} />
      </div>
      {compareMode === "slider" ? (
        <SliderStage sliderPos={sliderPos} setSliderPos={setSliderPos}
          left={<RealImage id={leftId} caption="before" apiKey={apiKey} />}
          right={<RealImage id={rightId} caption="after" apiKey={apiKey} />} />
      ) : (
        <OverlayStage>
          <DiffImage leftId={leftId} rightId={rightId} apiKey={apiKey} />
        </OverlayStage>
      )}
      {compareMode === "overlay" && <OverlayHint />}
      <div style={{ display: "flex", gap: 16, marginTop: 8 }}>
        <a href={withKey(`/api/images/${leftId}`, apiKey)} target="_blank" rel="noreferrer" style={styles.fullSizeLink}>
          <ZoomIn size={12} /> Open "before" full size
        </a>
        <a href={withKey(`/api/images/${rightId}`, apiKey)} target="_blank" rel="noreferrer" style={styles.fullSizeLink}>
          <ZoomIn size={12} /> Open "after" full size
        </a>
      </div>
      {diff ? <ScorePanel score={diff} sev={sev} /> : <LoadingRow label="Computing diff…" />}
      {diff && <ReviewPanel diff={diff} onAcknowledge={onAcknowledge} busy={acknowledging} />}
    </div>
  );
}

/** Approve/reject/reset controls for the pair currently shown in Compare view. */
function ReviewPanel({ diff, onAcknowledge, busy }) {
  const [note, setNote] = useState("");
  const status = diff.status || "PENDING";

  return (
    <div style={styles.reviewPanel}>
      <div style={{ display: "flex", alignItems: "center", gap: 10, flexWrap: "wrap" }}>
        <StatusPill status={status} />
        <div style={{ flex: 1 }} />
        {status !== "APPROVED" && (
          <button disabled={busy} onClick={() => onAcknowledge("APPROVED", note)} style={{ ...styles.reviewBtn, ...styles.reviewBtnApprove }}>
            <Check size={13} /> Approve
          </button>
        )}
        {status !== "REJECTED" && (
          <button disabled={busy} onClick={() => onAcknowledge("REJECTED", note)} style={{ ...styles.reviewBtn, ...styles.reviewBtnReject }}>
            <XCircle size={13} /> Reject
          </button>
        )}
        {status !== "PENDING" && (
          <button disabled={busy} onClick={() => onAcknowledge("PENDING", note)} style={styles.reviewBtn}>
            Reset to pending
          </button>
        )}
      </div>
      <input
        value={note}
        onChange={(e) => setNote(e.target.value)}
        placeholder="Optional note (e.g. 'intentional redesign' or 'needs fixing')"
        style={styles.reviewNoteInput}
      />
      {diff.reviewNote && <div style={{ fontSize: 12, color: "#8B8F99", marginTop: 6, fontStyle: "italic" }}>Last note: "{diff.reviewNote}"</div>}
    </div>
  );
}

function StatusPill({ status }) {
  const config = {
    PENDING: { color: "#8B8F99", bg: "#2A2F3A", label: "Pending review" },
    APPROVED: { color: "#4FD1C5", bg: "#4FD1C522", label: "Approved" },
    REJECTED: { color: "#E8604C", bg: "#E8604C22", label: "Rejected" },
  }[status] || { color: "#8B8F99", bg: "#2A2F3A", label: status };
  return <span style={{ ...styles.badge, background: config.bg, color: config.color, fontSize: 11.5 }}>{config.label}</span>;
}

function ModeToggle({ mode, setMode }) {
  return (
    <div style={{ display: "flex", gap: 4 }}>
      <button onClick={() => setMode("slider")} style={{ ...styles.modeToggleBtn, ...(mode === "slider" ? styles.modeToggleBtnActive : {}) }}>
        <SlidersHorizontal size={12} /> Slider
      </button>
      <button onClick={() => setMode("overlay")} style={{ ...styles.modeToggleBtn, ...(mode === "overlay" ? styles.modeToggleBtnActive : {}) }}>
        <Layers size={12} /> Overlay
      </button>
    </div>
  );
}

function OverlayStage({ children }) {
  return <div style={styles.sliderStage}>{children}</div>;
}

/** Real pixel-diff image from the backend: dimmed unchanged pixels, magenta-highlighted changes. */
function DiffImage({ leftId, rightId, apiKey }) {
  const [error, setError] = useState(null);
  useEffect(() => { setError(null); }, [leftId, rightId]);
  if (!leftId || !rightId) return null;
  if (error) {
    return (
      <div style={{ display: "flex", alignItems: "center", justifyContent: "center", height: "100%", color: "#8B8F99", fontSize: 12, textAlign: "center", padding: 16 }}>
        {error}
      </div>
    );
  }
  return (
    <img
      src={withKey(`/api/diff/image?leftId=${leftId}&rightId=${rightId}`, apiKey)}
      onError={() => setError("Couldn't render a pixel diff - the two images likely have different dimensions.")}
      alt="pixel diff"
      style={{ width: "100%", height: "100%", objectFit: "contain", display: "block", background: "#000" }}
    />
  );
}

/** Demo-only stand-in: no real backend diff to call, so approximate one with a CSS blend of the two SVGs. */
function DemoOverlay({ left, right }) {
  return (
    <div style={{ position: "relative", width: "100%", height: "100%", background: "#000" }}>
      <div style={{ position: "absolute", inset: 0 }}><ScreenshotArt style={left.style} /></div>
      <div style={{ position: "absolute", inset: 0, mixBlendMode: "difference" }}><ScreenshotArt style={right.style} /></div>
    </div>
  );
}

function OverlayHint({ demo }) {
  return (
    <div style={{ fontSize: 11, color: "#8B8F99", marginTop: 8 }}>
      {demo
        ? "Demo mode: this is a CSS blend of two mock images, not a real pixel diff - illustrative only."
        : "Bright/magenta areas changed; dimmed areas are unchanged between the two releases."}
    </div>
  );
}

function SliderStage({ sliderPos, setSliderPos, left, right }) {
  return (
    <div style={styles.sliderStage}>
      <div style={{ position: "absolute", inset: 0 }}>{left}</div>
      <div style={{ position: "absolute", inset: 0, clipPath: `inset(0 0 0 ${sliderPos}%)` }}>{right}</div>
      <div style={{ ...styles.sliderHandle, left: `${sliderPos}%` }}><div style={styles.sliderGrip} /></div>
      <input className="cs-slider" type="range" min={0} max={100} value={sliderPos} onChange={(e) => setSliderPos(Number(e.target.value))}
        style={{ position: "absolute", inset: 0, width: "100%", height: "100%", cursor: "ew-resize", opacity: 0 }} />
    </div>
  );
}

function VersionSelect({ label, versions, value, onChange }) {
  return (
    <label style={{ display: "flex", alignItems: "center", gap: 8, fontSize: 12, color: "#8B8F99" }}>
      {label}
      <select value={value} onChange={(e) => onChange(Number(e.target.value))} style={styles.select}>
        {versions.map((v, i) => <option key={v + i} value={i}>{v}</option>)}
      </select>
    </label>
  );
}

function ScorePanel({ score, sev }) {
  return (
    <div style={styles.scorePanel}>
      <ScoreStat label="pixel diff" value={score.pixelDifferencePercentage != null ? `${score.pixelDifferencePercentage}%` : "n/a"} color={SEVERITY_COLOR[sev]} />
      <ScoreStat label="SSIM" value={score.ssimScore != null ? score.ssimScore.toFixed(3) : "n/a"} color="#E8E6E1" />
      <ScoreStat label="pHash distance" value={score.pHashDistance != null ? `${score.pHashDistance} / 63` : "n/a"} color="#E8E6E1" />
      <ScoreStat label="pHash similarity" value={score.pHashSimilarityPercent != null ? `${score.pHashSimilarityPercent}%` : "n/a"} color="#E8E6E1" />
      <div style={{ flex: 1 }} />
      <span style={{ ...styles.badge, background: SEVERITY_COLOR[sev] + "22", color: SEVERITY_COLOR[sev] }}>{sev === "stable" ? "no visible change" : sev === "minor" ? "minor change" : "likely regression"}</span>
    </div>
  );
}
function ScoreStat({ label, value, color }) {
  return (
    <div style={{ textAlign: "center" }}>
      <div style={{ fontFamily: "JetBrains Mono, monospace", fontSize: 18, color }}>{value}</div>
      <div style={{ fontSize: 11, color: "#8B8F99", marginTop: 2 }}>{label}</div>
    </div>
  );
}

/* ---------------------------------------------------------------------- */
/* Styles                                                                    */
/* ---------------------------------------------------------------------- */
const styles = {
  app: { minHeight: "100vh", background: "#14171C", color: "#E8E6E1", fontFamily: "Inter, sans-serif", display: "flex", flexDirection: "column" },
  header: { display: "flex", alignItems: "center", gap: 20, padding: "14px 20px", borderBottom: "1px solid #2A2F3A", background: "#1C2028" },
  wordmark: { fontFamily: "Fraunces, serif", fontSize: 19, fontWeight: 600, letterSpacing: "-0.01em" },
  wordmarkSub: { fontFamily: "JetBrains Mono, monospace", fontSize: 11, color: "#8B8F99" },
  releaseSelect: { background: "#14171C", border: "1px solid #2A2F3A", borderRadius: 6, padding: "7px 10px", color: "#E8E6E1", fontFamily: "JetBrains Mono, monospace", fontSize: 12 },
  reportLink: { display: "flex", alignItems: "center", gap: 6, background: "#14171C", border: "1px solid #2A2F3A", borderRadius: 6, padding: "7px 12px", color: "#8B8F99", fontSize: 12, textDecoration: "none", whiteSpace: "nowrap" },
  searchWrap: { flex: 1, maxWidth: 420, display: "flex", alignItems: "center", gap: 8, background: "#14171C", border: "1px solid #2A2F3A", borderRadius: 6, padding: "7px 10px" },
  searchInput: { flex: 1, background: "transparent", border: "none", outline: "none", color: "#E8E6E1", fontSize: 13 },
  iconButton: { background: "#14171C", border: "1px solid #2A2F3A", borderRadius: 6, padding: 8, cursor: "pointer", display: "flex" },
  settingsPanel: { padding: "12px 20px", background: "#1C2028", borderBottom: "1px solid #2A2F3A" },
  scanPanel: { marginTop: 14, paddingTop: 14, borderTop: "1px solid #2A2F3A" },
  settingsInput: { flex: 1, maxWidth: 320, background: "#14171C", border: "1px solid #2A2F3A", borderRadius: 6, padding: "7px 10px", color: "#E8E6E1", fontFamily: "JetBrains Mono, monospace", fontSize: 12 },
  primaryButton: { display: "flex", alignItems: "center", gap: 6, background: "#4FD1C5", color: "#0B0D10", border: "none", borderRadius: 6, padding: "7px 12px", fontSize: 12, fontWeight: 600, cursor: "pointer" },
  body: { flex: 1, display: "flex", minHeight: 0 },
  sidebar: { width: 220, borderRight: "1px solid #2A2F3A", padding: "16px 10px", overflowY: "auto", flexShrink: 0 },
  sidebarLabel: { fontFamily: "JetBrains Mono, monospace", fontSize: 11, color: "#8B8F99", padding: "0 10px 8px", letterSpacing: "0.04em", textTransform: "uppercase" },
  componentItem: { display: "flex", justifyContent: "space-between", alignItems: "center", width: "100%", background: "transparent", border: "none", color: "#E8E6E1", padding: "9px 10px", borderRadius: 6, cursor: "pointer", fontSize: 13.5, textAlign: "left" },
  componentItemActive: { background: "#232833", color: "#4FD1C5" },
  componentCount: { fontFamily: "JetBrains Mono, monospace", fontSize: 11, color: "#8B8F99" },
  main: { flex: 1, padding: 20, overflowY: "auto", minWidth: 0 },
  viewSwitch: { display: "flex", alignItems: "center", gap: 8, marginBottom: 18 },
  viewTab: { display: "flex", alignItems: "center", gap: 6, background: "#1C2028", border: "1px solid #2A2F3A", color: "#8B8F99", borderRadius: 6, padding: "7px 12px", fontSize: 12.5, cursor: "pointer" },
  viewTabActive: { color: "#E8E6E1", borderColor: "#4FD1C5", background: "#1C2028" },
  breadcrumb: { fontFamily: "JetBrains Mono, monospace", fontSize: 12, color: "#8B8F99", display: "flex", alignItems: "center" },
  grid: { display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(340px, 1fr))", gap: 18 },
  card: { background: "#1C2028", border: "1px solid #2A2F3A", borderRadius: 10, overflow: "hidden", cursor: "pointer", padding: 0, textAlign: "left" },
  cardArt: { aspectRatio: "400 / 260", borderBottom: "1px solid #2A2F3A" },
  cardFooter: { display: "flex", justifyContent: "space-between", alignItems: "center", padding: "10px 12px" },
  cardTitle: { fontSize: 13, color: "#E8E6E1" },
  badge: { fontSize: 10.5, fontFamily: "JetBrains Mono, monospace", padding: "3px 8px", borderRadius: 999 },
  windowPicker: { display: "flex", gap: 8, marginBottom: 18, flexWrap: "wrap" },
  windowChip: { background: "#1C2028", border: "1px solid #2A2F3A", color: "#8B8F99", borderRadius: 999, padding: "6px 14px", fontSize: 12.5, cursor: "pointer" },
  windowChipActive: { color: "#14171C", background: "#E8E6E1", borderColor: "#E8E6E1" },
  variantChip: { background: "#1C2028", border: "1px solid #2A2F3A", color: "#8B8F99", borderRadius: 6, padding: "4px 10px", fontSize: 11.5, cursor: "pointer", marginRight: 6, fontFamily: "JetBrains Mono, monospace" },
  variantChipActive: { color: "#14171C", background: "#4FD1C5", borderColor: "#4FD1C5" },
  filmstripWrap: { overflowX: "auto", paddingBottom: 12, background: "#0F1115", borderRadius: 10, border: "1px solid #2A2F3A" },
  filmstrip: { display: "flex", alignItems: "center", padding: 20, width: "max-content" },
  frame: { display: "flex", flexDirection: "column", alignItems: "stretch", width: 280 },
  sprocket: { height: 6, background: "repeating-linear-gradient(90deg, #2A2F3A 0 6px, transparent 6px 12px)" },
  frameArt: { border: "1px solid #2A2F3A", borderRadius: 4, overflow: "hidden", aspectRatio: "400 / 260", margin: "3px 0" },
  frameLabel: { textAlign: "center", fontFamily: "JetBrains Mono, monospace", fontSize: 11, color: "#8B8F99", marginTop: 4 },
  connectorWrap: { display: "flex", flexDirection: "column", alignItems: "center", background: "transparent", border: "none", cursor: "pointer", width: 64, padding: 0 },
  connectorLine: { height: 2, width: 22 },
  connectorLabel: { fontFamily: "JetBrains Mono, monospace", fontSize: 11, padding: "3px 0" },
  legend: { marginTop: 14, display: "flex", alignItems: "center", flexWrap: "wrap" },
  compareControls: { display: "flex", alignItems: "center", gap: 14, marginBottom: 14 },
  modeToggleBtn: { display: "flex", alignItems: "center", gap: 5, background: "#1C2028", border: "1px solid #2A2F3A", color: "#8B8F99", borderRadius: 6, padding: "6px 10px", fontSize: 11.5, cursor: "pointer" },
  modeToggleBtnActive: { color: "#14171C", background: "#E8E6E1", borderColor: "#E8E6E1" },
  select: { background: "#1C2028", border: "1px solid #2A2F3A", color: "#E8E6E1", borderRadius: 6, padding: "5px 8px", fontFamily: "JetBrains Mono, monospace", fontSize: 12 },
  sliderStage: { position: "relative", width: "100%", maxWidth: 1040, aspectRatio: "400 / 260", border: "1px solid #2A2F3A", borderRadius: 10, overflow: "hidden" },
  sliderHandle: { position: "absolute", top: 0, bottom: 0, width: 2, background: "#E8E6E1", pointerEvents: "none" },
  sliderGrip: { position: "absolute", top: "50%", left: "50%", transform: "translate(-50%,-50%)", width: 26, height: 26, borderRadius: 999, background: "#E8E6E1", border: "3px solid #14171C" },
  scorePanel: { display: "flex", gap: 28, alignItems: "center", marginTop: 18, padding: "14px 18px", background: "#1C2028", border: "1px solid #2A2F3A", borderRadius: 10, maxWidth: 720 },
  reviewPanel: { marginTop: 12, padding: "14px 18px", background: "#1C2028", border: "1px solid #2A2F3A", borderRadius: 10, maxWidth: 720 },
  reviewBtn: { display: "flex", alignItems: "center", gap: 5, background: "#14171C", border: "1px solid #2A2F3A", color: "#E8E6E1", borderRadius: 6, padding: "6px 12px", fontSize: 12, cursor: "pointer" },
  reviewBtnApprove: { borderColor: "#4FD1C5", color: "#4FD1C5" },
  reviewBtnReject: { borderColor: "#E8604C", color: "#E8604C" },
  reviewNoteInput: { width: "100%", marginTop: 10, background: "#14171C", border: "1px solid #2A2F3A", borderRadius: 6, padding: "7px 10px", color: "#E8E6E1", fontSize: 12, boxSizing: "border-box" },
  zoomButton: { position: "absolute", top: 8, right: 8, display: "flex", alignItems: "center", justifyContent: "center", width: 28, height: 28, borderRadius: 6, background: "rgba(20,23,28,0.75)", border: "1px solid rgba(255,255,255,0.2)", color: "#E8E6E1", cursor: "pointer" },
  fullSizeLink: { display: "flex", alignItems: "center", gap: 5, fontSize: 12, color: "#8B8F99", textDecoration: "none" },
  lightboxBackdrop: { position: "fixed", inset: 0, background: "rgba(10,11,13,0.92)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 1000, cursor: "zoom-out" },
  lightboxImage: { maxWidth: "94vw", maxHeight: "94vh", objectFit: "contain", borderRadius: 6, boxShadow: "0 20px 60px rgba(0,0,0,0.5)", cursor: "default" },
  lightboxClose: { position: "absolute", top: 20, right: 24, display: "flex", alignItems: "center", justifyContent: "center", width: 40, height: 40, borderRadius: 999, background: "rgba(255,255,255,0.08)", border: "1px solid rgba(255,255,255,0.2)", color: "#E8E6E1", cursor: "pointer" },
};
