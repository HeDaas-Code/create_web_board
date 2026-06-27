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
 *   <li>{@code GET /api/boards}        — JSON array of all boards</li>
 *   <li>{@code GET /api/boards/{name}} — JSON content of named board (404 if missing)</li>
 *   <li>{@code GET /api/health}        — quick liveness probe + connection count</li>
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
        // The board must be stale (no heartbeat for 30s); otherwise returns 409 Conflict.
        app.delete("/api/boards/{name}", ctx -> {
            String name = ctx.pathParam("name");
            BoardContent b = registry.get(name);
            if (b == null) {
                ctx.status(404);
                ctx.result("{\"error\":\"board not found: " + JsonUtil.quote(name) + "\"}");
                return;
            }
            if (!b.stale()) {
                ctx.status(409);
                ctx.result("{\"error\":\"board is not stale; cannot remove active board: " + JsonUtil.quote(name) + "\"}");
                return;
            }
            registry.remove(name);
            if (BoardDatabase.get().isInitialized()) {
                BoardDatabase.get().markRemoved(name);
            }
            ctx.contentType("application/json");
            ctx.result("{\"removed\":" + JsonUtil.quote(name) + "}");
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