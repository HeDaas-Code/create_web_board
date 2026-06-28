# Create Web Board

A **Create 6.0.10** addon for **Minecraft 1.21.1** on **NeoForge**. Adds a per-Display-Link
"Web" toggle that mirrors **any** Create Display Source output to a local browser dashboard
in real time — no custom source needed, every existing source just works.

Current version: **0.7.0** · License: **MIT** · Sync: **server-required, client-optional**.

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

## For players

### Install

1. Install Minecraft 1.21.1 + [NeoForge 21.1.219](https://neoforged.net/).
2. Install [Create 6.0.10](https://www.curseforge.com/minecraft/mc-mods/create) and
   [Ponder 1.0.82](https://www.curseforge.com/minecraft/mc-mods/ponder).
3. Drop `create_web_board-1.21.1-*.jar` into your `mods/` folder.
4. Launch the game.

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
required, BOTH sides); `flywheel` and `ponder` optional.

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

Coverage: PNG codec round-trip (9 tests, no GL needed), TOML config loader edge cases
(8 tests), live Javalin HTTP+WS integration on an ephemeral port (12 tests), board registry
listener semantics (7 tests), and jar-content sanity checks asserting Javalin/Jetty/kotlin
classes are actually packaged (4 tests). ~40 tests total, all run in CI without a GPU.

### Docs

- [CLAUDE.md](CLAUDE.md) — agent collaboration rules (codegraph lookup, build/test, pitfalls).
- [docs/PRD.md](docs/PRD.md) — product requirements.
- [docs/adr/0001-javalin-embedded.md](docs/adr/0001-javalin-embedded.md) — why Javalin is embedded.
- [CHANGELOG.md](CHANGELOG.md) — version history. Release notes are sourced from this file.

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
