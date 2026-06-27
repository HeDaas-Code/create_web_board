package com.example.webboard.content.httpserver;

import com.example.webboard.content.persistence.BoardDatabase;
import com.example.webboard.content.registry.BoardContent;
import com.example.webboard.content.registry.BoardRegistry;

import com.example.webboard.content.items.ItemIconService;
import com.example.webboard.content.mirror.SourceLabels;

import io.javalin.Javalin;

import java.util.List;

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

        // GET /api/item-icon/{itemId} -- serve an item's texture PNG for dashboard thumbnails.
        // itemId is the full registry id (e.g. "minecraft:iron_ingot"), URL-encoded by the
        // client. The ItemIconService reads the PNG straight from the classpath (every mod jar),
        // so Create + all addon textures are reachable without a client resource manager. 404
        // when no texture resolves; the client then shows a fallback glyph.
        app.get("/api/item-icon/{itemId}", ctx -> {
            String itemId = ctx.pathParam("itemId");
            byte[] png = ItemIconService.get().getIcon(itemId);
            if (png == null) {
                ctx.status(404);
                return;
            }
            ctx.contentType("image/png");
            // Cache-control: item textures are immutable for a game session, so let the browser
            // cache aggressively to avoid re-fetching on every card render.
            ctx.header("Cache-Control", "public, max-age=3600");
            ctx.result(png);
        });

        // GET /api/items/search?q=...&limit=... -- searchable item catalog for the product
        // picker. Returns [{"id":"minecraft:iron_ingot","name":"iron_ingot"}, ...]. The catalog
        // is built once by iterating BuiltInRegistries.ITEM, so it covers vanilla + every mod.
        app.get("/api/items/search", ctx -> {
            String q = ctx.queryParam("q");
            int limit = 50;
            String limitStr = ctx.queryParam("limit");
            if (limitStr != null) {
                try { limit = Math.max(1, Math.min(200, Integer.parseInt(limitStr))); }
                catch (NumberFormatException ignored) { /* keep default */ }
            }
            var results = ItemIconService.get().search(q, limit);
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (var info : results) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"id\":").append(JsonUtil.quote(info.id()))
                        .append(",\"name\":").append(JsonUtil.quote(info.name())).append('}');
            }
            sb.append(']');
            ctx.contentType("application/json");
            ctx.result(sb.toString());
        });

        // PUT /api/boards/{name}/tags -- replace a board's tag set. Body: {"tags":["a","b"]}.
        // Tags are free-text labels the dashboard clusters boards by. Empty array clears them.
        // setTags fires a Put event so the WS hub rebroadcasts; we also persist for restarts.
        app.put("/api/boards/{name}/tags", ctx -> {
            String name = ctx.pathParam("name");
            BoardContent b = registry.get(name);
            if (b == null) {
                ctx.status(404);
                ctx.result("{\"error\":\"board not found: " + JsonUtil.quote(name) + "\"}");
                return;
            }
            List<String> tags = JsonUtil.extractStringArrayField(ctx.body(), "tags");
            registry.setTags(name, tags);
            if (BoardDatabase.get().isInitialized()) {
                BoardDatabase.get().setTags(name, tags);
            }
            ctx.contentType("application/json");
            ctx.result("{\"name\":" + JsonUtil.quote(name) + ",\"tags\":" + jsonStrArray(tags) + "}");
        });

        // PUT /api/boards/{name}/items -- replace a board's product item ids.
        // Body: {"itemIds":["minecraft:iron_ingot"]}. Empty array clears. Multi-select: a board
        // can represent a line producing several items. setItems fires a Put for WS rebroadcast.
        app.put("/api/boards/{name}/items", ctx -> {
            String name = ctx.pathParam("name");
            BoardContent b = registry.get(name);
            if (b == null) {
                ctx.status(404);
                ctx.result("{\"error\":\"board not found: " + JsonUtil.quote(name) + "\"}");
                return;
            }
            List<String> itemIds = JsonUtil.extractStringArrayField(ctx.body(), "itemIds");
            registry.setItems(name, itemIds);
            if (BoardDatabase.get().isInitialized()) {
                BoardDatabase.get().setItems(name, itemIds);
            }
            ctx.contentType("application/json");
            ctx.result("{\"name\":" + JsonUtil.quote(name) + ",\"itemIds\":" + jsonStrArray(itemIds) + "}");
        });

        app.get("/api/health", ctx -> {
            ctx.contentType("application/json");
            ctx.result("{\"status\":\"ok\",\"boards\":" + registry.size()
                    + ",\"wsConnections\":" + hub.connectionCount() + "}");
        });
    }

    /** Serialize a List<String> as a JSON array of quoted strings. */
    private static String jsonStrArray(List<String> list) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String s : list) {
            if (!first) sb.append(',');
            first = false;
            sb.append(JsonUtil.quote(s));
        }
        sb.append(']');
        return sb.toString();
    }
}