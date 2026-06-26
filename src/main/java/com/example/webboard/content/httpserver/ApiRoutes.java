package com.example.webboard.content.httpserver;

import com.example.webboard.content.registry.BoardContent;
import com.example.webboard.content.registry.BoardRegistry;

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
                sb.append(WebSocketHub.toJson(b));
                first = false;
            }
            sb.append(']');
            ctx.contentType("application/json");
            ctx.result(sb.toString());
        });

        app.get("/api/boards/{name}", ctx -> {
            String name = ctx.pathParam("name");
            BoardContent b = registry.get(name);
            if (b == null) {
                ctx.status(404);
                ctx.result("{\"error\":\"board not found: " + WebSocketHub.quote(name) + "\"}");
                return;
            }
            ctx.contentType("application/json");
            ctx.result(WebSocketHub.toJson(b));
        });

        app.get("/api/health", ctx -> {
            ctx.contentType("application/json");
            ctx.result("{\"status\":\"ok\",\"boards\":" + registry.size()
                    + ",\"wsConnections\":" + hub.connectionCount() + "}");
        });
    }
}