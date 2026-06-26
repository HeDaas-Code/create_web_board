# Create Web Board

A **Create 6.0.10** addon for **Minecraft 1.21.1** on **NeoForge**. Adds a Display Link source that mirrors each link to a local browser dashboard in real time.

## What it does

1. Place a Display Link (from Create) anywhere
2. Right-click → configure → set source to **Web Board**
3. Open `http://localhost:8080/` in any browser
4. See all Display Links in the sidebar, click one to see its live content

Each Display Link becomes a named "board". The board name comes from the block's custom name (anvil / name tag).

## For players

### Install

1. Install Minecraft 1.21.1 + [NeoForge 21.1.219](https://neoforged.net/)
2. Install [Create 6.0.10](https://www.curseforge.com/minecraft/mc-mods/create) and [Ponder 1.0.82](https://www.curseforge.com/minecraft/mc-mods/ponder)
3. Drop `create_web_board-1.21.1-*.jar` into your `mods/` folder
4. Launch the game

### Use

1. Craft a Display Link (`Mechanical Crafter` + `Redstone Link` from Create)
2. Place it
3. Right-click → "Configure" → choose source **Web Board**
4. Note the default URL: `http://localhost:8080/`

### Configure

Edit `config/create_web_board.toml` (created on first launch):

```toml
[server]
enabled = true
host = "127.0.0.1"
port = 8080
authToken = ""    # leave empty for localhost-only; set to require header

[ui]
title = "Create Web Board"
```

Restart the world after editing.

## For developers

- PRD: [docs/PRD.md](docs/PRD.md)
- Architecture & workflow for AI agents: [CLAUDE.md](CLAUDE.md)
- Issue #1 spec: [ISSUE_1.md](ISSUE_1.md)
- CodeGraph indices (Create 6.0.10 + NeoForge 1.21.1) live at `~/Project/MC_MOD_DEV/.codegraph/`

## Build

```bash
gradle build --no-daemon --stacktrace
```

Output: `build/libs/create_web_board-1.21.1-0.1.0.jar`

## License

MIT

## Status

**v0.1.0** — In development. Issue #1 (scaffold + WebDisplaySource registration) is the current scope. See GitHub Issues for the roadmap.