# Create Web Board — PRD

## What & Why

A **Create 6.0.10** addon that bridges **Display Link** (Create's in-game dashboard block) to a **local browser dashboard**. Place a Display Link, set its source to "Web Board", and the same content appears in real-time in your browser at `http://localhost:8080/`.

Each Display Link becomes a named "board" in the sidebar — the board name is whatever the player has named the Display Link block (anvil / name tag).

## For whom

- Single-player Create players who want their factories' stats visible on a second monitor / phone
- Server admins debugging complex Create setups
- Streamers showcasing factory automation to chat (board screen share)

## Non-goals (v1)

- No public/multi-user hosting (localhost only)
- No history persistence (live view, in-memory)
- No mobile-first layout (desktop only)
- No auth/RBAC
- No historical data charts (live-only)

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│ MC 1.21.1 + NeoForge 21.1.219 (single player or dedicated server)  │
│                                                                     │
│  ┌────────────────────────────────────────────┐                     │
│  │ Create 6.0.10 (mod)                        │                     │
│  │                                            │                     │
│  │  DisplayLinkBlockEntity  (placed in world) │                     │
│  │   └─ activeSource: DisplaySource           │                     │
│  │       └─ WebDisplaySource  ← our class     │                     │
│  │            └─ tick() pulls data, pushes    │                     │
│  │               to BoardRegistry             │                     │
│  └──────────┬─────────────────────────────────┘                     │
│             │ BoardRegistry (in-memory map)                         │
│             ▼                                                       │
│  ┌─────────────────────────────────────────────┐                    │
│  │ Embedded HTTP server (Javalin 6.3)          │                    │
│  │   GET  /              → index.html          │                    │
│  │   GET  /api/boards    → board list (JSON)   │                    │
│  │   GET  /api/boards/X  → board X content     │                    │
│  │   WS   /ws            → live updates         │                    │
│  │   GET  /static/*      → css/js              │                    │
│  │   bind: 127.0.0.1:8080 (localhost only)     │                    │
│  └─────────────────────────────────────────────┘                    │
└──────────────────────────┬──────────────────────────────────────────┘
                           │ HTTP / WS
                           ▼
              ┌────────────────────────┐
              │ Browser dashboard      │
              │ (localhost:8080)       │
              │  - sidebar: board list │
              │  - main: live content  │
              └────────────────────────┘
```

## Tech stack

| Layer | Tech | Version | Why |
|---|---|---|---|
| MC mod loader | NeoForge | 21.1.219 | User's pack uses it |
| MC version | Minecraft | 1.21.1 | matches user's pack |
| Create | `com.simibubi.create:create-1.21.1` | 6.0.10-280 | codegraph pinned to commit `ac0c444d9` |
| Registrate | `com.tterrag.registrate:Registrate` | MC1.21-1.3.0+67 | Required for fluent block/item/source registration |
| HTTP server | Javalin | 6.3.0 | Small (~250KB), single-jar, WebSocket support |
| JSON | (built into Javalin) | n/a | DTO encoding for API responses |
| Frontend | Vanilla HTML/JS/CSS | — | Zero build step, served directly from mod jar |

## Module layout

```
com.example.webboard
├── CreateWebBoard                  # @Mod entry, event bus subscription
├── content/
│   ├── displaysource/
│   │   └── WebDisplaySource        # DisplaySource impl — pulls data from source, stores in BoardRegistry
│   ├── registry/
│   │   ├── BoardRegistry           # Singleton: Map<DisplayLinkName, BoardContent>
│   │   └── BoardContent            # POJO: lines[], sourceType, lastUpdated
│   └── httpserver/
│       ├── HttpServer              # Lifecycle: start/stop on FMLCommonSetupEvent/FMLClientSetupEvent
│       ├── routes/
│       │   ├── ApiRoutes           # /api/boards, /api/boards/{name}
│       │   └── WebSocketRoutes     # /ws — broadcasts BoardRegistry deltas
│       └── config/
│           └── ServerConfig        # Host, port, authToken (from create_web_board.toml)
└── ...
```

## Resource layout

```
src/main/resources/
├── META-INF/neoforge.mods.toml     # mod metadata, deps on create + neoforge
├── pack.mcmeta
└── assets/create_web_board/
    └── web/                         # served by embedded HTTP server, NOT loaded as MC assets
        ├── index.html
        ├── app.js
        └── style.css
```

## Issues

| # | Title | Scope | Depends on |
|---|---|---|---|
| 1 | Scaffold + WebDisplaySource registration | Mod entry, Registrate init, WebDisplaySource class, mods.toml deps, gradle build green, CI green | — |
| 2 | HTTP server + BoardRegistry + WebSocket | BoardRegistry singleton, Javalin server start/stop, /api/boards routes, /ws websocket, broadcast on DisplaySource tick | #1 |
| 3 | Web UI (HTML/JS/CSS) | index.html (sidebar + main), app.js (WS client + DOM update), style.css (Create orange theme) | #2 |
| 4 | Config (TOML) + integration test | create_web_board.toml + ServerConfig, end-to-end test | #3 |

## Out of scope (deliberately deferred)

- Multiple MC instances on one host (each binds to its own port, no conflict resolution)
- HTTPS / TLS (localhost only — wire sniffing is your own problem)
- Source filtering / board categories
- Historical data / charts / trends
- Mobile-responsive UI
- Auth tokens beyond optional single shared secret

## Open questions

1. **Board identification**: When a Display Link has no custom name, what should we show in the sidebar?
   - A: Use block entity position (e.g. "Board @ 124, 64, -312")
   - B: Auto-name as "Board N" (counter)
   - C: Force players to name them (warn in UI)
   - Default: A (most informative)
2. **Tick rate**: How often does WebDisplaySource sample and broadcast?
   - Every 20 ticks (1 second, matches DisplayLink's tick) — sensible default
   - Configurable? Default: yes, in ServerConfig.tickRateHz
3. **Multiple sources per board?**: Each Display Link has exactly one active source. Out of scope: a single board pulling from multiple Display Links.

## Success criteria

A player with this mod installed can:
1. Place a Display Link
2. Set source to "Web Board"
3. Open browser at `http://localhost:8080/`
4. See "Display Link" in sidebar (default name)
5. See board content update live as the source changes
6. Rename the Display Link via anvil → sidebar updates without page refresh