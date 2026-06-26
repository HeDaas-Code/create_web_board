# ADR-0001: Embedding Javalin 6 in a NeoForge mod for the in-process HTTP dashboard

**Status**: Accepted (2026-06-26)
**Decider**: Hermes (PM)
**Context**: Issue #2 of create-web-board

## Context and problem statement

`create-web_board` mirrors every Create 6.0.10 Display Link to a local browser
dashboard. The browser-side update channel needs to be reachable from a web tab
on the operator's machine, with sub-second latency and zero external dependencies.

Options considered:

1. **MC-side RCON / chat broadcast to an external HTTP server** — operator must
   run something outside the game. Out of scope (no auto-start).
2. **Minecraft's own network protocol** — no general HTTP path; chat packets
   can't carry arbitrary web payloads.
3. **Embed an HTTP server in the mod** — start on `ServerStartedEvent`, stop on
   `ServerStoppingEvent`, bound to `127.0.0.1` only.

We chose (3). This ADR records the library choice and the trade-offs that fell
out of it.

## Decision

Embed **Javalin 6.3.0** (Jetty 11 underneath) as the HTTP + WebSocket server,
loaded via `io.javalin:javalin:6.3.0` on the `implementation` configuration
(NOT jarJar — see ADR-0002 when written; for v1 we accept the user-classpath
exposure).

## Why Javalin and not alternatives

| Library | Why not |
|---|---|
| Spring Boot | ~30MB; 5s+ cold start; over-kill for 3 endpoints |
| Helidon / Quarkus | smaller than Spring but still require a fat jar, conflicting with MC classpath |
| Undertow | raw, lots of code for routes+WS |
| NanoHTTPD | tiny but no first-class WebSocket |
| Spark Java | unmaintained since 2021 |
| **Javalin 6** | small (~700KB), fluent Kotlin-style Java API, WS support, no annotation processor, works on Jetty 11 which is the modern baseline |

## Consequences

### Positive

- Single-process: no port forwarding, no extra logs to tail
- WebSocket works out of the box (`/ws` endpoint, see WebSocketHub)
- Static file serving from classpath at `/` and `/static/*` (issue #3)
- 3 REST endpoints (`/api/boards`, `/api/boards/{name}`, `/api/health`) trivial
  to write
- No native dependencies; works on every OS MC supports

### Negative

- **Classpath leakage**: Javalin + Jetty 11 + Jakarta Servlet API end up on the
  user's MC classpath. If the user has another mod that pulls a conflicting
  Jetty/Servlet version, both mods fail at class-load time. ADR-0002 will
  explore jarJar-ing Javalin for v0.2.
- **SLF4J binding battle**: Jetty 11 expects `slf4j-api 2.0.9`, Javalin expects
  `2.0.16`, NeoForge strictly pins `2.0.9`. Resolution: `configurations.all {
  resolutionStrategy { force 'org.slf4j:slf4j-api:2.0.9' } }` + do NOT add
  `slf4j-simple` (MC's bundled Log4j2 binds SLF4J at runtime; adding simple
  would create a second binding and log double-output). See build.gradle
  comment block.
- **Javalin 6 + static files quirk**: `staticFiles.add()` cannot be merged —
  the same directory mounted at two hostedPaths (`/` for index.html and
  `/static/*` for explicit asset URLs) requires two `add()` calls. See
  HttpServer.java comment. Also, the root entry needs
  `skipFileFunction = req -> false` or `/` 404s when `index.html` is the only
  file.
- **One HTTP server per MC server instance**: a user running multiple MC
  servers on the same host will hit port conflicts if they all default to
  8080. Mitigated by `webboard-server.toml` and `-Dwebboard.port=<N>` system
  property override (issue #4).

## Verification

- Local build: `gradle test` → 20/20 PASS in 6s (CI: 1m22s)
- WS end-to-end test: real Javalin+Jetty, real `HttpClient.newWebSocketBuilder()`,
  real snapshot receipt (`HttpServerIT.websocketConnects_andReceivesSnapshot`)
- Runtime verification (`runClient`) is deferred — sandbox has no GPU. Local
  verification on a real MC client is the operator's job, listed as
  contribution step in README.

## Alternatives revisited later

If Javalin's classpath leakage causes real user complaints, fall back to
NanoHTTPD and hand-roll the WebSocket — ~300 more lines, but a single tiny jar
in the mod's `dependencies` block.
