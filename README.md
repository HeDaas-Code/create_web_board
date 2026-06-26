# Create Web Board

A **Create 6.0.10** addon for **Minecraft 1.21.1** on **NeoForge**. Adds **two** Display Link
sources that mirror every linked block to a local browser dashboard in real time.

## What it does

1. Place a Display Link (from Create) anywhere
2. Right-click → configure → set source to **Web Board** or **Block Entity Summary**
3. Open `http://localhost:8080/` in any browser
4. See all Display Links in the sidebar, click one to see its live content

| Source | Output |
|---|---|
| **Web Board** | Placeholder — single line, useful as a baseline for custom sources |
| **Block Entity Summary** | Real read of source BE: block name, position, BE type registry id |

Both sources write to the same dashboard, so you can mix and match in one place.

## For players

### Install

1. Install Minecraft 1.21.1 + [NeoForge 21.1.219](https://neoforged.net/)
2. Install [Create 6.0.10](https://www.curseforge.com/minecraft/mc-mods/create) and
   [Ponder 1.0.82](https://www.curseforge.com/minecraft/mc-mods/ponder)
3. Drop `create_web_board-1.21.1-*.jar` into your `mods/` folder
4. Launch the game

### Use

1. Craft a Display Link (`Mechanical Crafter` + `Redstone Link` from Create)
2. Place it
3. Right-click → "Configure" → choose source **Web Board** or **Block Entity Summary**
4. Note the default URL: `http://localhost:8080/`

### Configure

Optional: create `config/webboard-server.toml` to override host/port:

```toml
[server]
host = "127.0.0.1"   # NEVER set to 0.0.0.0 — the dashboard has no auth
port = 8080
maxWsConnections = 16
```

See `src/main/resources/assets/create_web_board/webboard-server.toml.example` for a
fully-commented template. Missing file → defaults.

You can also override the port at launch with `-Dwebboard.port=<N>`.

## For developers

- **PRD**: [docs/PRD.md](docs/PRD.md)
- **Architecture & workflow for AI agents**: [CLAUDE.md](CLAUDE.md)
- **ADRs**: [docs/adr/](docs/adr/) — currently ADR-0001 covers the Javalin embed decision
- **CodeGraph indices** (Create 6.0.10 + NeoForge 1.21.1) live at `~/Project/MC_MOD_DEV/.codegraph/`

### Add a new DisplaySource

1. Subclass `com.simibubi.create.api.behaviour.display.DisplaySource`
2. Implement `provideText(DisplayLinkContext, DisplayTargetStats) -> List<MutableComponent>`
3. Optionally override `getPassiveRefreshTicks()` (default 100 = 5s; 20 = 1s feels live)
4. Register in `CreateWebBoard#<init>`:
   ```java
   REGISTRATE.displaySource("my_id", MySource::new).register();
   ```
5. Add an i18n key in `assets/create_web_board/lang/en_us.json`:
   `create_web_board.display_source.my_id` → "Human-readable name"
6. Inside `provideText`, mirror to the web dashboard:
   ```java
   BoardRegistry.get().put(BoardContent.of(name, "create_web_board:my_id", plainLines));
   ```
7. Add tests in `src/test/java/...`

See `BlockEntitySummaryDisplaySource` for a worked example (3-line summary, stable BE API only).

## Build

```bash
gradle build --no-daemon --stacktrace
```

Output: `build/libs/create_web_board-1.21.1-0.1.0.jar`

## Test

```bash
gradle test --no-daemon
```

20/20 PASS locally and in CI. Tests are standalone — no MC runtime needed (they
construct `HttpServer` directly with a real Javalin+Jetty instance on an ephemeral port).
CI: see `.github/workflows/build.yml`.

## License

MIT

## Status

**v0.1.0** — Released.

- ✅ WebDisplaySource (ID: `web_board`) — placeholder source
- ✅ BlockEntitySummaryDisplaySource (ID: `be_summary`) — real BE read
- ✅ Embedded HTTP server (Javalin 6) with `/api/boards`, `/api/boards/{name}`, `/api/health`
- ✅ WebSocket broadcast on registry change (`/ws` endpoint)
- ✅ Static web UI (sidebar + main + status bar) with auto-reconnect
- ✅ TOML config + 20 integration/unit tests

Roadmap: see GitHub Issues.
