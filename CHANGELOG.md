# Changelog

All notable changes to **Create Web Board** are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this file is the source of
truth for GitHub Release notes — the `release` workflow extracts the matching `## [x.y.z]`
entry as the release body.

**Release policy**: versions are not bumped per feature. A new entry is added and a tag
pushed only when the maintainer explicitly requests a release. See the *Release policy*
section of [README.md](README.md).

---

## [0.7.1] - 2026-06-29

### Added
- **Train dispatch map** (`/trains.html`): a new dashboard page that mirrors the
  Create railway network in real time and lets the operator curate train metadata.
  Hand-drawn SVG map (no map library — keeps the jar slim), 6 tabs:
  地图 (map) · 分类 (categories) · 线路 (lines) · 标签 (station tags) · 路径 (route search) · 到发 (departures).
- **TrainPoller**: a server-tick listener that mirrors `Create.RAILWAYS` into MC-free
  snapshots on a dual frequency — trains every 10 ticks (0.5 s), topology every
  200 ticks (10 s). All Create API calls are wrapped in try/catch so a railway
  hiccup can never crash a game tick.
- **Live data REST API** (read-only, served from `TrainMirrorService`):
  - `GET /api/trains`, `GET /api/trains/by-id/{id}` — live train snapshots (position,
    speed, heading, status, navigation target, carriage count).
  - `GET /api/trains/graph` — current track-graph topology (nodes, edges, stations).
  - `GET /api/trains/health` — service status + CRN bridge status + CRN line count + counters.
- **Metadata REST API** (read-only, synced from CRN when present):
  - `GET /api/train-categories` — train categories (from CRN `GlobalSettings`).
  - `GET /api/train-lines` — train lines (from CRN `GlobalSettings`).
  - `GET /api/station-tags` — station tags (from CRN `GlobalSettings`).
  - `PUT/DELETE /api/train-metadata/{trainId}` — per-train user config
    (displayName, categoryId, lineId, color, notes), persisted to
    `config/webboard-trains.json`.
- **Route search**: `GET /api/routes/search?from=...&to=...&maxResults=...` runs a
  bounded-depth k-shortest-paths DFS over the live track graph and returns hops,
  total distance, and an estimated travel time.
- **Departure history**: `GET /api/departures?station=...&limit=...` and
  `GET /api/departures/all?limit=...`. Each station keeps a 100-entry ring buffer.
- **CRN soft-dependency bridge** (`CrnBridge`): detects Create Railways Navigator
  via `ModList.get().isLoaded()` and syncs its `GlobalSettings` (categories, lines,
  station tags) via reflection every 10 s. The dashboard serves these read-only —
  categories/lines/tags are managed in-game through CRN, not on the web page.
  Degrades gracefully to Create-only data when CRN is absent.
- **15 new unit tests** for `TrainPollerMath` (poll-tick cadence + 8-wind compass
  bearing), bringing the suite to **60 tests** total.

### Changed
- `HttpServer` now registers `TrainRoutes` and serves `/trains.html` + `/trains.js`.
- `ServerLifecycle` wires the train module lifecycle: `TrainMetadataStorage.init`,
  `CrnBridge.syncMetadata`, `TrainPoller.enable` on server start; the reverse
  on stop.
- `neoforge.mods.toml` declares `create_railways_navigator` as an optional
  dependency so users discover the integration.

### Fixed
- **Route shadowing bug**: `/api/trains/{id}` (path-param) was greedily matching
  the sibling literal routes `/api/trains/graph` and `/api/trains/health`,
  returning `{"error":"train not found: \"health\""}` (404) instead of the health
  / topology JSON. The single-train endpoint is now `/api/trains/by-id/{id}` so
  the path-param can no longer shadow the literal sub-paths. Reported in v0.7.1
  field testing ("CRN 离线" + empty map symptom).
- **Map misalignment (trains vs topology)**: `TrackNodeLocation` stores coordinates
  at 2× resolution (Create's half-block precision design), but train positions from
  `TravellingPoint.getPosition()` are real world coords. Nodes/edges/stations now
  divide by 2 so they align with trains on the SVG map.
- **CRN always showing "未安装"**: detection used `Class.forName` on a wrong package
  path (`cn.creatrailwaysnavigator` instead of `de.mrjulsen.crn`). Switched to
  `ModList.get().isLoaded("createrailwaysnavigator")` — the standard NeoForge way,
  independent of CRN's internal class layout.
- **Categories/lines/tags now read-only on web**: previously had web CRUD forms that
  duplicated CRN's in-game management, causing data inconsistency. Removed all
  POST/PUT/DELETE endpoints and frontend forms; data is synced from CRN's
  `GlobalSettings` every 10 s via reflection.
- **"铁道" stat replaced with "线路"**: the edge count was misleading. The health
  endpoint now reports `crnLines` (CRN train line count) and the topbar shows
  线路 instead of 铁道.
- Main dashboard topbar had no link to the new dispatch map page; added a
  "调度图" button (train icon). Reverse link already existed in `trains.html`.

## [0.7.0] - 2026-06-28

### Added
- **Stress networks**: combine multiple Display Link boards into a unified network view.
  Each member is assigned a role (`producer` / `consumer` / `storage`) and a `lineIndex`
  telling the dashboard which output line to extract the numeric value from.
- Network card shows live aggregates — total production, total consumption, surplus
  (production − consumption), and total storage — with a per-member breakdown.
- Network CRUD API: `POST/PUT/DELETE /api/networks`, persisted to
  `config/webboard-networks.json` (atomic write on every change).
- Dashboard **networks view** with view toggle, network detail modal (aggregate stats +
  member table), network editor (role/label/lineIndex per member), and board picker.
- **Enhanced trend charts**: mean line, change-rate annotation (per-second delta), and
  anomaly markers (points >2σ from mean when ≥6 samples). Reused by both board and
  network charts. Pure SVG, no chart library.

### Fixed
- `NetworkStorage` parser: explicit casts on `Object[]` returns so the recursive-descent
  JSON parser compiles under the NeoForge 1.21.1 javac (CI build #42 → #43).

## [0.6.7] - 2026-06-28

### Changed
- Mod sync is now **server-required, client-optional**: `displayTest = "IGNORE_SERVER_VERSION"`
  in `neoforge.mods.toml`. Clients without the mod can join a server that has it, without
  being kicked for a version mismatch. The Web toggle button only renders on clients that
  have the mod installed.

## [0.6.6] - 2026-06-28

### Fixed
- **Icon rendering blank under Iris shaderpack**: Iris replaces vanilla `RenderType`
  shaders and produced garbage in the offscreen FBO pass. The client now temporarily
  disables the active shaderpack (via reflection on `IrisApi`) for the duration of the
  icon batch, then re-enables it. No-op when Iris is absent.
- **Z-frustum clipping**: `setOrtho(0,32,32,0,1000,21000)` makes visible z ∈ [-21000,-1000],
  but `GuiGraphics.renderItem` translates to z≈+150 (outside near plane → fully clipped →
  blank). Fixed by `pose.translate(0,0,-8000)` to push items into the middle of the frustum.

## [0.6.5] - 2026-06-28

### Fixed
- **Crash on icon render ("max stack size of 16 reached")**: `popMatrix` was not in a
  `finally` block, so an exception inside `renderItem` leaked the model-view stack and
  crashed the next render. Wrapped the render block in try/finally.
- **Depth buffer not cleared**: only `GL_COLOR_BUFFER_BIT` was cleared between items,
  leaving stale depth that failed all fragments → transparent icons. Now clears
  `GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT`.

## [0.6.4] - 2026-06-27

### Fixed
- Use the correct 1.21.1 GL API names for offscreen rendering.
- Extracted the PNG codec (`IconPngCodec`) into a GL-free class so it can be unit-tested
  in CI without a GPU.

## [0.6.3] - 2026-06-27

### Fixed
- Complete the GL state setup (projection, lighting, scissor) so FBO renders are not blank.

## [0.6.2] - 2026-06-27

### Fixed
- Purge stale blank-icon cache left over from the v0.6.0 renderer bug so previously-blank
  icons get re-rendered correctly.

## [0.6.1] - 2026-06-27

### Fixed
- Use `VertexSorting.ORTHOGRAPHIC_Z` (the correct constant name in 1.21.1) for the icon
  projection matrix.

## [0.6.0] - 2026-06-27

### Added
- **JEI-style rendered item icons**: the client renders every registered item to a 32×32
  RGBA PNG through the full `ItemRenderer` pipeline (tint, 3D model transforms — not raw
  textures) and uploads them to the server on join. Icons are shown on board cards and in
  the product-item picker, with Chinese localized names.
- `/webboard export-icons` client command: renders the full item set and writes
  `webboard-icons.zip` for offline use on dedicated servers.
- `GET /api/item-icon/{itemId}`, `POST /api/item-icon/{itemId}`,
  `GET /api/icon-pack/status`, `POST /api/items/names`, `GET /api/items/search` endpoints.
- `IconPackStorage` persists icons to `config/webboard-icons/` (or `config/webboard-icons.zip`).

## [0.5.0] - 2026-06-27

### Added
- **Board tags**: free-text labels, persisted across restarts, editable in the board modal.
  Tags drive the dashboard's group-by-tag view.
- **Associated product items**: pick item ids to associate with a board; thumbnails render
  on the card and in the modal. New item-picker modal with search.
- `PUT /api/boards/{name}/tags` and `PUT /api/boards/{name}/items` endpoints.

## [0.4.1] - 2026-06-27

### Added
- **SVG history trend chart** in the board detail modal: hand-drawn line chart of the
  board's persisted history, pure SVG (no chart library), works offline.

## [0.4.0] - 2026-06-27

### Added
- **Board detail modal**: rename a board (display name), browse raw text, view history
  snapshots, and delete a board from the dashboard.
- `GET /api/boards/{name}/history` endpoint (newest-last `[{ts, lines}, ...]`).

### Fixed
- "Always offline" bug where boards never transitioned back to live after a reconnect.

## [0.3.2] - 2026-06-27

### Fixed
- Destroyed Display Links now show as offline on the dashboard within ~5 s, via the stale
  scanner (30 s threshold) combined with the 5 s WebSocket scan loop.

## [0.3.1] - 2026-06-27

### Changed
- **Replaced SQLite with JSON-file persistence**: NeoForge 1.21.1 does not bundle the
  SQLite JDBC driver, so `jdbc:sqlite:` failed at runtime (`No suitable driver found`,
  issue #9). Boards now persist to `config/webboard-boards.json` with a debounced 5 s
  atomic flush. Zero external deps.

### Fixed
- `BoardDatabase.init()` now catches `Exception` (not just `SQLException`) so a corrupt
  store doesn't kill server startup.

## [0.3.0] - 2026-06-27

### Added
- Board persistence (originally SQLite — see 0.3.1), stale detection (30 s threshold), and
  Chinese source-type labels (`SourceLabels` maps 29 Create + 4 addon source ids to
  localized names; `GET /api/source-labels`).

## [0.2.5] - 2026-06-27

### Fixed
- Dashboard root `/` returned 404 because Javalin `staticFiles` didn't serve `/index.html`
  at the root. Switched to explicit routes for `/`, `/index.html`, `/style.css`, `/app.js`.

## [0.2.4] - 2026-06-27

### Changed
- Redesigned the dashboard as a Feishu-style kanban card grid (replacing the old
  sidebar+main layout).

## [0.2.3] - 2026-06-27

### Changed
- Dedup mirror refreshes so identical content doesn't re-broadcast.
- Move WebSocket broadcast off the game thread onto a single-thread daemon
  (`web-board-ws-sender`) to avoid blocking ticks.

## [0.2.2] - 2026-06-27

### Fixed
- Mixin no longer shadows inherited `guiLeft`/`guiTop`; recomputes the Web toggle button
  position instead (issue #8).

## [0.2.1] - 2026-06-27

### Fixed
- Mixin `DisplayLinkScreenMixin` now `extends Screen` to bypass `@Shadow` on the inherited
  `addRenderableWidget` call (issue #7, refMap loading failure crash).

## [0.2.0] - 2026-06-27

### Changed
- **Architectural shift**: instead of registering custom DisplaySources (`web_board`,
  `be_summary`), the mod now uses a Mixin on `DisplaySource#transferData` to mirror **any**
  Create source. A per-link **Web: ON/OFF** toggle (NBT key `WebSynced` on the link's
  `sourceConfig`) controls mirroring, synced via Create's own configuration packet.
- Dashboard UI redesign accompanying the toggle.

### Removed
- `WebDisplaySource` and `BlockEntitySummaryDisplaySource` (replaced by the Mixin wrapper).

## [0.1.2] - 2026-06-27

### Fixed
- Embed `kotlin-stdlib` via jarJar so Javalin's `module-info` (`requires kotlin.stdlib`)
  resolves at runtime (issue #6).

## [0.1.1] - 2026-06-27

### Fixed
- Dependency/packaging fix for the initial jar.

## [0.1.0] - 2026-06-27

### Added
- Initial release.
- `WebDisplaySource` and `BlockEntitySummaryDisplaySource` (later replaced in 0.2.0).
- Embedded Javalin 6 HTTP server with `/api/boards`, `/api/boards/{name}`, `/api/health`.
- WebSocket broadcast on registry change (`/ws`).
- Static web UI (sidebar + main + status bar) with auto-reconnect.
- TOML config (`config/webboard-server.toml`) and 20 integration/unit tests.

---

<!-- Release notes template (copy when cutting a new version):

## [x.y.z] - YYYY-MM-DD

### Added
- ...

### Changed
- ...

### Deprecated
- ...

### Removed
- ...

### Fixed
- ...

### Security
- ...

-->
