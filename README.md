# Create Web Board

[English](README.md) · [简体中文](README.zh-CN.md)

A **Create 6.0.10** addon for **Minecraft 1.21.1** on **NeoForge**. Adds a per-Display-Link
"Web" toggle that mirrors **any** Create Display Source output to a local browser dashboard
in real time — no custom source needed, every existing source just works.

Current version: **0.7.1** · License: **MIT** · Sync: **server-required, client-optional**.

## What it does

1. Place a Display Link (from Create) anywhere and pick any source (time of day, kinetic
   stress, boiler, train status, item throughput, ...).
2. Right-click the link screen → flip the new **Web: ON/OFF** button.
3. Open `http://localhost:8080/` in any browser on the same machine.
4. See all toggled-on links live in the sidebar, build **stress networks** that aggregate
   producers/consumers/storage, browse history with trend charts, and tag/annotate boards.

The mod does **not** register its own DisplaySource. It uses a Mixin to wrap
`DisplaySource#transferData` and mirror whatever the underlying source already produces.
The toggle is a single NBT boolean (`WebSynced`) on the link's `sourceConfig`, synced via
Create's own configuration packet — no custom networking.

As of **0.7.1**, the dashboard also ships a **train dispatch map** (`/trains.html`) that
mirrors Create's railway network in real time — live train positions on a hand-drawn SVG
map, route search, and arrival/departure records. When [Create Railways Navigator](https://www.curseforge.com/minecraft/mc-mods/create-railways-navigator)
(CRN) is installed, categories / lines / station tags are synced from CRN read-only and
route search resolves station tags to multiple underlying Create stations.

## For players

### Install

1. Install Minecraft 1.21.1 + [NeoForge 21.1.219](https://neoforged.net/).
2. Install [Create 6.0.10](https://www.curseforge.com/minecraft/mc-mods/create) and
   [Ponder 1.0.82](https://www.curseforge.com/minecraft/mc-mods/ponder).
3. (Optional, recommended for the train map) Install
   [Create Railways Navigator 0.9.0+](https://www.curseforge.com/minecraft/mc-mods/create-railways-navigator)
   — enables CRN-synced categories / lines / station tags and tag-based route search.
4. Drop `create_web_board-1.21.1-*.jar` into your `mods/` folder.
5. Launch the game.

**Server vs. client**: the mod is **server-required, client-optional**. A dedicated server
must have it installed; clients without it can still join (the toggle button simply won't
appear in their link screen). On single-player or a listen server, the client jar also
renders item icons and uploads them to the dashboard (see *Icon rendering* below).

### Use

1. Craft a Display Link, place it, choose any Create source.
2. Right-click → flip **Web: ON**.
3. Visit `http://localhost:8080/`.

### Dashboard features

- **Boards view** — one card per Display Link. Shows effective name, live/offline badge,
  source-type chip (translated to Chinese, e.g. `应力` / `锅炉状态` / `列车状态`), last-updated
  time, tags, associated product icons, and live text lines. Cards go grey ~5 s after the
  link is destroyed or stops reporting.
- **Networks view** — combine multiple boards into a unified **stress network**. Each member
  is assigned a role (`producer` / `consumer` / `storage`) and a `lineIndex` telling the
  dashboard which line of the board's output to extract the numeric value from. The network
  card shows live aggregates: total production, total consumption, surplus
  (= production − consumption), and total storage, with a member breakdown below.
- **Search & grouping** — filter by name/source/member; group boards by tag.
- **Board detail modal** — rename, edit tags, pick associated product items, view raw text,
  and browse the **history timeline** with a hand-drawn SVG trend chart.
- **Network detail modal** — network name, live aggregate stats, a trend chart that
  aggregates every member's history by timestamp (production = green, consumption = orange,
  surplus = blue), and a member table.
- **Network editor** — add/remove boards, set role/label/lineIndex per member.
- **Enhanced trend charts** — every chart includes a **mean line**, a **change-rate
  annotation** (per-second delta over the window), and **anomaly markers** (points more than
  2σ from the mean, when there are ≥6 samples). Pure SVG, no JS chart library, works offline.
- **Real-time** — WebSocket push on every change, plus 5 s REST polling fallback and
  exponential-backoff reconnect.
- **Train dispatch map** (`/trains.html`, new in 0.7.1) — a separate page that mirrors the
  Create railway network in real time. Single-page layout with a hand-drawn SVG map and a
  collapsible sidebar:
  - **Map pane** — live train positions, track graph nodes/edges, and stations, all on one
    hand-drawn SVG (no map library — keeps the jar slim). Train positions refresh every
    0.5 s, topology every 10 s.
  - **Train list & detail** — online trains with status/speed/heading; click one to see
    carriages, navigation target, and per-train user metadata (display name, category,
    line, color, notes).
  - **Route search** — bounded-depth k-shortest-paths DFS over the live track graph. When
    CRN is present, the from/to selectors use **station tags** (which group multiple
    underlying Create stations); search expands a tag to all its stations and finds the
    shortest path from any origin to any destination.
  - **Departure records** — arrivals/departures auto-detected by diffing each train's
    `navigating` flag between poll cycles (works with or without CRN). Each station keeps
    a 100-entry ring buffer; the section auto-refreshes every 5 s while open.
  - **Metadata** (read-only) — categories / lines / station tags, synced from CRN's
    `GlobalSettings` every 10 s via reflection. Managed in-game through CRN, not on the
    web page. Falls back to local storage when CRN is absent.

### Configure

Optional: create `config/webboard-server.toml` to override host/port:

```toml
[server]
host = "127.0.0.1"   # bind address. Keep this localhost — the dashboard has no auth.
port = 8080
maxWsConnections = 16
```

See `src/main/resources/assets/create_web_board/webboard-server.toml.example` for a
fully-commented template. Missing file → defaults. Port can also be overridden at launch
with `-Dwebboard.port=<N>` (useful for running multiple instances or tests).

**Security warning**: the dashboard has **no authentication**. Bind to `127.0.0.1` only.
Never expose port 8080 to a public network.

### Icon rendering

The dashboard can show real item icons (JEI-style 32×32 PNGs, rendered through the full
`ItemRenderer` pipeline including tint and 3D model transforms — not raw texture files).
Two paths:

- **Listen server / single-player**: the client renders every registered item to an offscreen
  FBO and uploads PNGs to the server on join. This also temporarily disables Iris shaderpack
  during the batch (Iris replaces vanilla render shaders and would otherwise produce garbage
  in the offscreen pass), then re-enables it.
- **Dedicated server**: run `/webboard export-icons` on a client with the mod installed. It
  renders the full item set and writes `webboard-icons.zip` to the game directory. Drop that
  zip into the server's `config/` folder and the dashboard loads icons from it on next start.

Icons are cached at `config/webboard-icons/` (or `config/webboard-icons.zip`) and served to
browsers via `GET /api/item-icon/{itemId}` with a 60 s cache header.

## For developers & integrators

### HTTP API

All endpoints under `http://localhost:8080`. JSON in/out unless noted.

**Boards**

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/boards` | Array of all board snapshots |
| GET | `/api/boards/{name}` | Single board; 404 if missing |
| PUT | `/api/boards/{name}` | Set display name (body `{"displayName":"..."}`, empty clears) |
| DELETE | `/api/boards/{name}` | Remove from dashboard (link still online will reappear on next refresh) |
| GET | `/api/boards/{name}/history` | History snapshots, newest-last: `[{"ts":<ms>,"lines":[...]}]` |
| PUT | `/api/boards/{name}/tags` | Replace tags (body `{"tags":["a","b"]}`) |
| PUT | `/api/boards/{name}/items` | Replace associated product item ids (body `{"itemIds":[...]}`) |

**Networks**

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/networks` | List all stress networks |
| POST | `/api/networks` | Create (body `{"name":"...","members":[{boardName,role,label,lineIndex}, ...]}`) |
| PUT | `/api/networks/{id}` | Update name + members |
| DELETE | `/api/networks/{id}` | Delete |

`role` ∈ `producer` (default) / `consumer` / `storage`. `lineIndex` defaults to 0.

**Trains** (live data, read-only — served from `TrainMirrorService`)

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/trains` | All live train snapshots (position, speed, heading, status, navigation target, carriages) |
| GET | `/api/trains/by-id/{id}` | Single train snapshot; 404 if missing |
| GET | `/api/trains/graph` | Current track-graph topology (nodes, edges, stations) |
| GET | `/api/trains/health` | `{"status":"ok","trains":N,"crn":"detected"/"absent","crnLines":M,"departures":K}` |
| GET | `/api/routes/search?from=...&to=...&maxResults=...` | Bounded-depth k-shortest-paths DFS. `from`/`to` accept a CRN station tag name (expanded to all its stations) or a raw Create station name. Returns hops, total distance, estimated travel time. |
| GET | `/api/departures?station=...&limit=...` | Recent arrivals/departures at one station (omit `station` for all) |
| GET | `/api/departures/all?limit=...` | Recent arrivals/departures across all stations |

**Train metadata** (categories / lines / tags read-only from CRN; per-train config writable)

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/train-categories` | Train categories (from CRN `GlobalSettings`; falls back to local storage) |
| GET | `/api/train-lines` | Train lines (from CRN; falls back to local) |
| GET | `/api/station-tags` | Station tags (from CRN; falls back to local). Each tag carries `stationNames[]` — the Create station names it groups. |
| GET | `/api/train-metadata` | Per-train user config for all trains |
| PUT | `/api/train-metadata/{trainId}` | Upsert per-train config (body `{"displayName","categoryId","lineId","color","notes"}`) |
| DELETE | `/api/train-metadata/{trainId}` | Delete per-train config |

**Misc**

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/source-labels` | sourceType id → localized label map |
| GET | `/api/health` | `{"status":"ok","boards":N,"wsConnections":M}` |
| GET | `/api/item-icon/{itemId}` | Item icon PNG (60 s cache); 404 if none |
| POST | `/api/item-icon/{itemId}` | Upload a rendered PNG (body = raw PNG, name via `X-Item-Name` URL-encoded header) |
| GET | `/api/icon-pack/status` | `{"count":N,"initialized":bool}` |
| POST | `/api/items/names` | Batch resolve item ids → names (body `{"items":[...]}`) |
| GET | `/api/items/search?q=...&limit=50` | Search items by id or name substring |

**WebSocket**: `ws://localhost:8080/ws`. On connect receives
`{"type":"snapshot","boards":[...]}`. Server pushes `{"type":"update","board":{...}}` (also
used for stale transitions) and `{"type":"remove","name":"..."}`.

### Persistence files

All paths relative to the server working directory.

| File | Format | Purpose |
|---|---|---|
| `config/webboard-boards.json` | JSON | Board snapshots + history (200-entry cap per board) + tags + item ids. Debounced 5 s flush, atomic temp-file move. |
| `config/webboard-networks.json` | JSON | Stress network definitions. Written immediately on every CRUD (atomic move). |
| `config/webboard-trains.json` | JSON | Per-train user metadata (displayName, categoryId, lineId, color, notes). Written immediately on every CRUD (atomic move). Categories / lines / station tags are also persisted here as a local fallback for when CRN is absent. |
| `config/webboard-icons/` | dir | Rendered item icon PNGs + `names.json`. 3 s flush. |
| `config/webboard-icons.zip` | zip | Optional offline icon pack for dedicated servers. |
| `config/webboard-server.toml` | TOML | Optional host/port/maxWsConnections override. |

Removed boards are kept in `webboard-boards.json` with `status: "removed"` for analytics;
`GET /api/boards` skips them.

### Mod loading & sync

`META-INF/neoforge.mods.toml` declares `displayTest = "IGNORE_SERVER_VERSION"`. Effect: the
server must have the mod installed, but clients without it can still join without being
kicked for a version mismatch. The toggle button only renders on clients that have the mod.

Dependencies: `minecraft [1.21.1,1.22)`, `neoforge [21.1.219,)`, `create [6.0.10,)` (all
required, BOTH sides); `flywheel`, `ponder`, and `create_railways_navigator [0.9.0,)` optional.
CRN is detected at server start via `ModList.get().isLoaded("createrailwaysnavigator")` and
bridged through reflection (`CrnBridge`) — when present, the train dispatch map syncs CRN's
categories / lines / station tags read-only; when absent, it degrades to Create-only data
plus locally-curated metadata.

### Build

```bash
gradle build --no-daemon --stacktrace
```

Output: `build/libs/create_web_board-1.21.1-<version>.jar`. Javalin, Jetty, and
kotlin-stdlib are jarJar'd into `META-INF/jarjar/` — the mod is self-contained, no extra
runtime deps.

### Test

```bash
gradle test --no-daemon
```

Coverage: PNG codec round-trip (no GL needed), TOML config loader edge cases, live Javalin
HTTP+WS integration on an ephemeral port, board registry listener semantics, jar-content
sanity checks asserting Javalin/Jetty/kotlin classes are actually packaged, train metadata
storage CRUD + persistence round-trip, route search BFS correctness over snapshot graphs,
train poller tick-cadence and 8-wind compass bearing math, and departure history ring-buffer
semantics. **71 tests** total, all run in CI without a GPU.

### Docs

- [CLAUDE.md](CLAUDE.md) — agent collaboration rules (codegraph lookup, build/test, pitfalls).
- [docs/PRD.md](docs/PRD.md) — product requirements.
- [docs/research/train-dispatch-map-prd.md](docs/research/train-dispatch-map-prd.md) — train dispatch map design (0.7.1).
- [docs/adr/0001-javalin-embedded.md](docs/adr/0001-javalin-embedded.md) — why Javalin is embedded.
- [CHANGELOG.md](CHANGELOG.md) — version history. Release notes are sourced from this file.

## Roadmap

The core dashboard (boards, networks, train dispatch map) is stable as of 0.7.1. The
following **auxiliary features** are planned to improve in-game discoverability and
onboarding — none are implemented yet; this section documents the intent so contributors
can pick them up.

### Ponder tutorials (hold W to ponder)

Create ships a [Ponder](https://www.curseforge.com/minecraft/mc-mods/ponder) system that
shows in-game tutorial scenes when the player holds W on an item/block. The plan is to
register Ponder scenes for the Web toggle and the train dispatch map so players learn the
mod without leaving the game.

Ponder is already declared as an optional dependency (`ponder [1.0.82,)` in
`neoforge.mods.toml`) and wired in `build.gradle` (non-jarJar — players install it
standalone), but **no scenes are registered yet**. Planned scenes:

- *Enabling the Web toggle on a Display Link* — right-click a Display Link, flip the
  **Web: ON** button, watch the board appear on the dashboard.
- *Reading the train dispatch map* — open `/trains.html`, interpret live train positions,
  run a tag-based route search, and read the departure records.

A new `client/ponder/` package will host `PonderStoryBoard` / `PonderScene` registrations,
wired from `CreateWebBoard`'s constructor via `PonderRegistrationHelper.create(MOD_ID)`.
Ponder scenes are client-only and gracefully no-op when Ponder is absent.

### In-game startup notification (bottom-right toast)

Today nothing in-game tells the player where to find the dashboard — the URL only appears
*inside* the web page after they already opened it. Planned: a one-time toast (or chat
message) shown on world join that reports the bound dashboard URL (e.g.
`http://localhost:8080/`).

The existing `ClientPlayerNetworkEvent.LoggingIn` hook in `ClientIconEvents` is the natural
injection point. It will read the effective host/port from `ServerConfig` /
`config/webboard-server.toml` so the message reflects the actual bind address, and will
only fire on worlds where the local HTTP server is reachable (single-player / listen
server).

### In-game mod config (NeoForge ModConfigSpec)

Configuration is currently a hand-written TOML file (`config/webboard-server.toml`) parsed
by `ConfigLoader`, with no in-game UI — players must edit files by hand. Planned: migrate
to NeoForge's `ModConfigSpec` and register a config screen so players can toggle
host / port / maxWsConnections from the Mods menu.

This requires a `[[config]]` entry in `neoforge.mods.toml` and a
`IConfigScreenFactory` / `ConfigScreen` registration in `CreateWebBoard`. The existing
`ConfigLoader` / `ServerConfig` will be kept as the persistence layer underneath the spec,
so the file format stays backward-compatible.

## Release policy

**Versions are not bumped per feature.** A new release is cut only when the maintainer
explicitly says so. Between releases, new code lands on `main`, CI verifies it via the
`build` workflow, but **no tag is pushed and no GitHub Release is published** until the
maintainer requests it.

Each release's notes are pulled from the matching `## [x.y.z]` entry in
[CHANGELOG.md](CHANGELOG.md) — see the *Keep a Changelog* format there. To cut a release:

1. Add a `## [x.y.z] - YYYY-MM-DD` entry to `CHANGELOG.md` describing what changed.
2. Bump `mod_version` in `gradle.properties` to match.
3. Commit, then `git tag v<x.y.z>` and `git push origin v<x.y.z>`.
4. The `release` workflow builds the jar and creates the GitHub Release using the changelog
   entry as the body.

## License

MIT. See `gradle.properties` (`mod_license = MIT`).
