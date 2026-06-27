package com.example.webboard.content.httpserver;

import com.example.webboard.content.persistence.BoardDatabase;
import com.example.webboard.content.registry.BoardContent;
import com.example.webboard.content.registry.BoardRegistry;

import com.example.webboard.content.mirror.SourceLabels;

import io.javalin.Javalin;

/**
 * ApiRoutes — HTTP endpoints for the browser dashboard.
 *
 * <ul>
 *   <li>{@code GET  /api/boards}                  — JSON array of all boards</li>
 *   <li>{@code GET  /api/boards/{name}}           — JSON content of named board (404 if missing)</li>
 *   <li>{@code PUT  /api/boards/{name}}           — rename: body {@code {"displayName":"..."}}</li>
 *   <li>{@code DELETE /api/boards/{name}}         — remove from dashboard (allowed any state)</li>
 *   <li>{@code GET  /api/boards/{name}/history}   — persisted history snapshots for the modal</li>
 *   <li>{@code GET  /api/source-labels}           — sourceType → 中文 label map</li>
 *   <li>{@code GET  /api/health}                  — liveness probe + WS connection count</li>
 * </ul>
 *
 * <p>Static UI assets (HTML/CSS/JS) are mounted separately by {@link HttpServer} under
 * {@code /} and {@code /static} — those are #3 scope.
 */
public final class ApiRoutes {

    private ApiRoutes() {}

    public static void register(Javalin app, BoardRegistry registry, WebSocketHub hub) {
        app.get("/api/boards", ctx -> {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (BoardContent b : registry.all()) {
                if (!first) sb.append(',');
                sb.append(JsonUtil.boardToJson(b));
                first = false;
            }
            sb.append(']');
            ctx.contentType("application/json");
            ctx.result(sb.toString());
        });

        // DELETE /api/boards/{name} -- manual removal from the dashboard.
        // Allowed in any state (live or stale). Note: deleting a board whose Display Link is
        // still active only removes it until the next mirror() refresh re-creates it; to
        // permanently stop a board, turn off the Web toggle on the Display Link in-game.
        // The previous 409-when-not-stale guard made the dashboard's delete button unusable
        // whenever a board was live (user report: "没有删除按钮").
        app.delete("/api/boards/{name}", ctx -> {
            String name = ctx.pathParam("name");
            BoardContent b = registry.get(name);
            if (b == null) {
                ctx.status(404);
                ctx.result("{\"error\":\"board not found: " + JsonUtil.quote(name) + "\"}");
                return;
            }
            registry.remove(name);
            if (BoardDatabase.get().isInitialized()) {
                BoardDatabase.get().markRemoved(name);
            }
            ctx.contentType("application/json");
            ctx.result("{\"removed\":" + JsonUtil.quote(name) + "}");
        });

        // PUT /api/boards/{name} -- set/clear the display name. Body: {"displayName":"..."}.
        // An empty string clears the override (falls back to the position-based key).
        // The registry.rename fires a Put event so the WS hub broadcasts the new label and the
        // DB upserts the displayName — no explicit setDisplayName call needed here.
        app.put("/api/boards/{name}", ctx -> {
            String name = ctx.pathParam("name");
            BoardContent b = registry.get(name);
            if (b == null) {
                ctx.status(404);
                ctx.result("{\"error\":\"board not found: " + JsonUtil.quote(name) + "\"}");
                return;
            }
            String body = ctx.body();
            String newDisplayName = JsonUtil.extractStringField(body, "displayName");
            if (newDisplayName != null && newDisplayName.isBlank()) newDisplayName = null;
            registry.rename(name, newDisplayName);
            // Persist explicitly too so the name survives even if no further content refresh
            // arrives (e.g. board is stale). The Put listener's upsert also covers this, but
            // setDisplayName is a no-op-safe belt-and-braces when the DB has the entry.
            if (BoardDatabase.get().isInitialized()) {
                BoardDatabase.get().setDisplayName(name, newDisplayName);
            }
            ctx.contentType("application/json");
            ctx.result("{\"name\":" + JsonUtil.quote(name)
                    + ",\"displayName\":" + JsonUtil.quote(newDisplayName) + "}");
        });

        app.get("/api/boards/{name}", ctx -> {
            String name = ctx.pathParam("name");
            BoardContent b = registry.get(name);
            if (b == null) {
                ctx.status(404);
                ctx.result("{\"error\":\"board not found: " + JsonUtil.quote(name) + "\"}");
                return;
            }
            ctx.contentType("application/json");
            ctx.result(JsonUtil.boardToJson(b));
        });

        // GET /api/boards/{name}/history -- persisted content snapshots for the dashboard modal.
        // Returns newest-last. Each entry: {"ts":<ms>,"lines":[...]}. Empty array if none.
        app.get("/api/boards/{name}/history", ctx -> {
            String name = ctx.pathParam("name");
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (var he : BoardDatabase.get().loadHistory(name)) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"ts\":").append(he.ts()).append(",\"lines\":[");
                boolean firstLine = true;
                for (String line : he.lines()) {
                    if (!firstLine) sb.append(',');
                    firstLine = false;
                    sb.append(JsonUtil.quote(line));
                }
                sb.append("]}");
            }
            sb.append(']');
            ctx.contentType("application/json");
            ctx.result(sb.toString());
        });

        app.get("/api/source-labels", ctx -> {
            ctx.contentType("application/json");
            ctx.result(SourceLabels.allLabelsAsJson());
        });

        app.get("/api/health", ctx -> {
            ctx.contentType("application/json");
            ctx.result("{\"status\":\"ok\",\"boards\":" + registry.size()
                    + ",\"wsConnections\":" + hub.connectionCount() + "}");
        });
    }
}