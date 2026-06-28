# CLAUDE.md — 项目级 Agent 协作规范

This document tells **Claude Code (CC)** how to work in this project.
**Read this before touching any code.** Hermes Agent (the user-side assistant) is the **project manager**; CC is the **lead engineer**.

---

## 0. Your role vs. Hermes Agent's role

| | Hermes Agent (in WeChat) | **You, Claude Code (CLI)** |
|---|---|---|
| Function | **Project manager** — translates user requests into tasks, dispatches work, verifies CI status, reports back to user | **Engineer** — writes code, runs builds, fixes bugs |
| Sees full context (this conversation, memory, prior sessions) | ✅ | ❌ — you only see what's in this repo |
| Loads matt-pocock engineering skills via Hermes' `mattpocock` umbrella | ✅ | ❌ — you don't have these skills loaded |
| Has the user's intent in natural language | ✅ | ❌ — you only see concrete task instructions |
| Encodes the workflow you should follow | via this file | this file IS the encoding |

**Practical rule**: when the user asks for a feature, Hermes dispatches you with a concrete task spec + relevant pointers into this file. You execute. You do NOT do scope-exploration or feature-design — those are already done.

---

## 1. Engineering workflow — the matt-pocock stages, mapped to your tooling

Hermes dispatches tasks in **stages**. Each stage maps to a CC-native approach + a deliverable.

| matt-pocock stage | What you do (CC) | Deliverable |
|---|---|---|
| **grill-me** | (Hermes does this — don't run yourself) | n/a — you receive an already-grilled spec |
| **to-prd** | (Hermes does this — you receive the PRD as your task spec) | n/a |
| **to-issues** | (Hermes splits into issues; you receive a single concrete issue) | n/a |
| **triage** | (Hermes triages; you receive priority + type) | n/a |
| **prototype** | Throwaway spike to validate an API/approach — branch `prototype/<topic>`, no CI gate, README explains what was learned | README.md in the prototype dir |
| **tdd** | **Default mode for new features.** Red→green→refactor. Tests-first. | New/updated tests + green `./gradlew build` |
| **diagnose** | 4-phase: reproduce → minimize → hypothesize → instrument → fix. **Use when fixing bugs.** | Root-cause commit with failing→passing test |
| **improve-codebase-architecture** | (Hermes may dispatch you for this on occasion) | Refactor PR with the original tests still green |
| **grill-with-docs** | (Hermes does this — you only need to keep `CONTEXT.md` + `docs/adr/` up to date if asked) | Update CONTRIBUTING.md / docs/adr/<NNNN>-*.md |
| **handoff** | (Hermes does this — you do not) | n/a |

**Default workflow on every feature/bugfix task**: receive task → **tdd** (write failing test → make it green → refactor) or **diagnose** (repro bug → minimize → root-cause → fix). No skipping. **Tests are non-negotiable.**

---

## 2. Code indexing — codegraph is mandatory

For every task that touches Create / NeoForge / MC APIs, you **must consult the codegraph index first** before writing code. This avoids hallucinating API signatures (a recurring failure mode — see `~/.hermes/skills/game-modding/create-addon-dev/references/verified-api-signatures.md` for the error trail).

### 2.1. When to consult codegraph

- ✅ Touching `com.simibubi.create.*` (Create internals)
- ✅ Touching `net.neoforged.*` (NeoForge internals)
- ✅ Subclassing `KineticBlockEntity` / `ProcessingRecipe` / `BlockEntityBehaviour` / `DisplaySource`
- ✅ Calling `Registrate` / `CreateRegistrate` fluent chains
- ✅ Wiring up mixin or accessor
- ❌ NOT needed for: vanilla MC API (`net.minecraft.*`), standard Java, project-local code

### 2.2. How to consult codegraph

The index is built from Create + NeoForge source already cloned into the project workspace (managed by Hermes — **do not re-clone**).

| Repo | Path | Pinned to | Why |
|---|---|---|---|
| `Creators-of-Create/Create` | `~/Project/MC_MOD_DEV/.codegraph/create/` → symlink to `../_refs/Create/` | commit **`ac0c444d9`** (Create 6.0.10 boundary, gradle.properties says `mod_version = 6.0.10`) | All Create 6.0.10 internals (kinetics, processing recipes, behaviours, DisplaySource) |
| `neoforged/NeoForge` | `~/Project/MC_MOD_DEV/.codegraph/neoforge/` → symlink to `../_refs/NeoForge/` | branch `1.21.1`, HEAD `2f185f98` | NeoForge 21.1.219 internals (DeferredRegister, event bus, mod loader) |

**Verify the index is alive** (run BEFORE any codegraph call):

```bash
readlink ~/Project/MC_MOD_DEV/.codegraph/create           # should resolve to ../_refs/Create
cd ~/Project/MC_MOD_DEV/.codegraph/create && git rev-parse HEAD   # MUST print ac0c444d9...
cd ~/Project/MC_MOD_DEV/.codegraph/create && grep mod_version gradle.properties   # MUST print: mod_version = 6.0.10

readlink ~/Project/MC_MOD_DEV/.codegraph/neoforge
cd ~/Project/MC_MOD_DEV/.codegraph/neoforge && git rev-parse HEAD   # MUST print 2f185f98...
```

If readlink/HEAD/mod_version checks fail → **stop and tell Hermes**. The index is stale or missing; writing code against it will silently target the wrong version.

**Per-task lookup** — use the `codegraph` skill's stdio MCP wrapper (preferred for batched calls), OR `codegraph_search` / `codegraph_node` / `codegraph_explore` CLI:

```bash
# Find a class/interface
codegraph_search "DisplaySource" --kind class --path ~/Project/MC_MOD_DEV/.codegraph/create/

# Read the exact signature of a method (use codegraph_node, NOT read_file — codegraph gives you the index's symbols + callers)
codegraph_node --file "src/main/java/com/simibubi/create/api/behaviour/display/DisplaySource.java"

# Blast radius: who else depends on this class?
codegraph_explore --query "src/main/java/com/simibubi/create/content/redstone/displayLink/source/BoilerDisplaySource.java" --path ~/Project/MC_MOD_DEV/.codegraph/create/

# DeferredRegister usage
codegraph_search "DeferredRegister" --kind class --path ~/Project/MC_MOD_DEV/.codegraph/neoforge/
```

**Rule**: if you find yourself about to write `import com.simibubi.create.X.Y.Z` or `import net.neoforged.neoforge.X.Y.Z` and you have not run `codegraph_search` for `X.Y.Z` in this session, **stop and run it first**. Verify the class actually exists at the version you're targeting. This is the #1 cause of "looks-right code that won't compile."

**Out-of-index methods**: if codegraph returns "no results" for a method you need (mixin-injected, obfuscated, or runtime-generated), fall back to `grep -rn 'methodName' ~/Project/MC_MOD_DEV/.codegraph/create/src/main/java/` and document the fallback in your commit message.

### 2.3. Cross-repo bug diagnosis (CBC × Sable pattern)

When a bug spans two repos (e.g. "mod A crashes against mod B"):

1. `codegraph_search` in repo A for the crash class → read the class
2. `codegraph_search` in repo B for the interface whose method is unresolved → read the interface
3. `git log -- <file>` in repo A → check if fix exists in source
4. Compare fix commit date vs modrinth release date → "fix exists but not published yet" is a common outcome

Document this in the bug-fix commit message. The pattern is encoded in `mc-mod-compat` skill Pitfall 21.

---

## 3. Build & test commands (verified 2026-06-26)

```bash
# Local build (cache hit ~5–10s; cold ~3min)
gradle build --no-daemon --stacktrace

# Run the full test suite (currently empty — write tests as you add features)
gradle test --no-daemon

# Datagen (only if you added blockstate/provider classes)
gradle runData

# Spot-check the jar's metadata
unzip -p build/libs/create_web_board-1.21.1-*.jar META-INF/neoforge.mods.toml
```

**CI runs `./gradlew build --no-daemon --stacktrace`** on every push to `main` / `master` / `dev` / `feature/**` / `fix/**`, every PR, weekly Mon 08:00 UTC, and on manual dispatch. The `build.yml` asserts that the packaged jar's `META-INF/neoforge.mods.toml` declares `create` + `neoforge` deps.

**You MUST keep CI green.** If you push and CI fails, fix it. Do not merge PRs with red CI.

---

## 4. Stack pins (do not bump without justification)

`gradle.properties` is the single source of truth:

| | Version | Comment |
|---|---|---|
| `minecraft_version` | 1.21.1 | matches user's pack |
| `neo_version` | 21.1.219 | NeoForge for 1.21.1 |
| `create_version` | 6.0.10-280 | Create 6.0.10 (codegraph pinned to commit `ac0c444d9`) |
| `ponder_version` | 1.0.82 | Ponder docs |
| `flywheel_version` | 1.0.6 | NOT currently a dep |
| `registrate_version` | MC1.21-1.3.0+67 | jarJar-ed |
| `javalin_version` | 6.3.0 | HTTP server (issue #2+) |
| `slf4j_version` | 2.0.9 | Match NeoForge's strict pin; no slf4j-simple (MC Log4j2 is the impl) |

**When bumping any version**: re-verify `./gradlew build` passes locally first, then update `gradle.properties`, then push. CI will catch upstream API drift.

---

## 5. Project layout

```
src/main/java/com/example/webboard/
├── CreateWebBoard.java              # @Mod entry point — DO NOT rename MOD_ID
├── content/
│   ├── displaysource/               # Issue #1: WebDisplaySource
│   ├── httpserver/                  # Issue #2: BoardRegistry, HTTP routes
│   └── ...
└── ...

src/main/resources/
├── META-INF/neoforge.mods.toml      # mod metadata, deps on create + neoforge
├── assets/create_web_board/web/     # Issue #3: index.html, app.js, style.css
└── pack.mcmeta
```

**Add new features under the matching package**: a new DisplaySource → `content/displaysource/`. A new HTTP route → `content/httpserver/`. A new web asset → `assets/create_web_board/web/`.

**DO NOT**:
- rename `MOD_ID` (will break every downstream player's install)
- move `CreateWebBoard.java` (CI assertions depend on the package layout)
- touch `.github/workflows/build.yml` without explicit Hermes instruction (CI gates depend on it)
- introduce slf4j-simple (conflicts with NeoForge strict 2.0.9 pin)

---

## 6. Git workflow

- **Default branch**: `main`. Feature branches: `feature/<topic>` or `feature/issue-N-short-desc`. Bug branches: `fix/<topic>`.
- **Commit style**: imperative subject, blank line, body explaining *why* (not *what*). Match the existing commits in `git log`.
- **One commit per logical change**. Tests + code in the same commit.
- **No force-pushes to `main`**. Force-push on `feature/*` is fine before review.
- **PR description must include**: what changed, why, what tests were added, what CI run verifies it.
- **Do not commit** `build/`, `.gradle/`, `*.jar` (already in `.gitignore`).

### 6.1 Releases — DO NOT cut a release without explicit maintainer approval

**Versions are not bumped per feature.** A release is cut only when the maintainer
explicitly says so (e.g. "publish this", "cut a release", "ship v0.8.0"). The
intervals between releases are intentionally long — multiple features accumulate
under the same version until the maintainer decides to ship.

When you land new code:

- ✅ Commit to `main` (or a feature branch → PR). The `build` workflow verifies it.
- ✅ Keep `mod_version` in `gradle.properties` as-is unless told to bump it.
- ❌ **Do NOT** `git tag v*` or push a tag. Tags trigger the `release` workflow,
  which publishes a public GitHub Release with a downloadable jar — irreversible.
- ❌ **Do NOT** bump `mod_version` on your own initiative because "a feature was added".
- ❌ **Do NOT** add a `## [x.y.z]` entry to `CHANGELOG.md` speculatively.

When the maintainer **does** ask for a release, the required sequence is:

1. Add a `## [x.y.z] - YYYY-MM-DD` entry to `CHANGELOG.md` describing every change
   since the last release (use the Added/Changed/Fixed/etc. categories). This entry
   becomes the GitHub Release body — the `release` workflow extracts it automatically,
   and the job fails loudly if the entry is missing.
2. Bump `mod_version` in `gradle.properties` to the same `x.y.z`.
3. Commit both changes together.
4. `git tag v<x.y.z>` and `git push origin v<x.y.z>`. The `release` workflow builds
   the jar and creates the GitHub Release using the changelog entry as the body.

If unsure whether something counts as "the maintainer asked for a release", **ask**.
A phrase like "优化一下仓库" or "fix this bug" is a code change, NOT a release request.

---

## 7. Known pitfalls

1. **DisplaySource registration** — use `CreateRegistrate.displaySource("name", Supplier<T>)` (verified in codegraph at `src/main/java/com/simibubi/create/foundation/data/CreateRegistrate.java:170`). Reference implementations: `BoilerDisplaySource` / `ComputerDisplaySource` at `content/redstone/displayLink/source/`.

2. **SLF4J version conflict** — NeoForge pins `strictly 2.0.9`, Javalin/Jetty want ≥2.0.13. Do NOT add `slf4j-simple` — MC's Log4j2 is the impl; Jetty falls back to NOP logger when SLF4J has no binding. Some non-critical debug logs will be silently dropped; that's acceptable for v1.

3. **Javalin on MC classpath** — Javalin 6 bundles Jetty 11 + websocket-api. If `gradle runClient` startup crashes with `ClassNotFoundException` / `NoClassDefFoundError` on `sun.net.httpserver.*` or jetty module issues, fall back to **NanoHTTPD** (single-jar, no Jetty, ~250KB). Document the swap in commit message.

4. **Registrate fluent chain** — `Registrate.object("id")` etc. Do NOT use `Registrate.recipeType/recipeSerializer` (those helpers don't exist on Registrate 1.3.0 — verified in codegraph).

5. **Groovy DSL** — `transitive = false` (NOT `isTransitive = false`). Verified in worked-example build.gradle.

6. **NeoForge JDK requirement** — CI runner defaults to JDK 17; `setup-java@v4` with `java-version: '21'` is already in build.yml. Do NOT remove it.

7. **Web UI asset path** — files under `src/main/resources/assets/create_web_board/web/` are NOT mod-loadable assets. They're served by the embedded HTTP server at runtime. Do NOT register them with the resource system — just read them from the mod jar at startup.

---

## 8. Task completion checklist

Before reporting back to Hermes:

- [ ] Local `gradle build --no-daemon --stacktrace` is GREEN
- [ ] If new public API: at least one unit test exercising the happy path
- [ ] Commit messages are imperative + explain why
- [ ] If you touched dependencies: re-ran `gradle dependencyInsight --configuration runtimeClasspath` and confirmed no conflicts
- [ ] If you touched CI: re-verified build.yml syntax via `actionlint` (if available) or by reading
- [ ] If you discovered a new pitfall: documented it in `references/pitfalls.md` (create the dir if missing)
- [ ] Reported back to Hermes with: build status, files changed, commit SHAs, any new pitfalls

---

## 9. When you're stuck

If a task spec is ambiguous (e.g. "make WebDisplaySource do something useful" but no spec on what), **stop and ask Hermes**. Do not guess.

If a codegraph lookup fails for an API you're sure exists, the index may be stale. Re-run `codegraph init` in the relevant repo, document the re-init in your commit message.

If a build fails with a "Dependency requires at least JVM runtime version 21" error, check `.github/workflows/build.yml` for `actions/setup-java@v4` with `java-version: '21'`. If missing, **fix it locally first, then push** — do NOT wait for CI to fail.