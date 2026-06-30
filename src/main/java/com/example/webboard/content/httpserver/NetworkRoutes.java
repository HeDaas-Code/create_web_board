package com.example.webboard.content.httpserver;

import com.example.webboard.content.network.NetworkDefinition;
import com.example.webboard.content.network.NetworkDefinition.NetworkMember;
import com.example.webboard.content.network.NetworkStorage;

import io.javalin.Javalin;

import java.util.ArrayList;
import java.util.List;

/**
 * NetworkRoutes — REST endpoints for stress network CRUD.
 *
 * <ul>
 *   <li>{@code GET    /api/networks}        — list all networks</li>
 *   <li>{@code POST   /api/networks}        — create a network</li>
 *   <li>{@code PUT    /api/networks/{id}}   — update a network</li>
 *   <li>{@code DELETE /api/networks/{id}}   — delete a network</li>
 * </ul>
 *
 * <p>The frontend polls {@code GET /api/networks} every 5s (like {@code GET /api/boards}) to
 * stay in sync. Network changes are infrequent (user-driven), so no WebSocket push is needed.
 *
 * <p>Member values (current stress numbers) are NOT stored here — the frontend computes
 * aggregates locally from the board data it already receives via WebSocket.
 */
public final class NetworkRoutes {

    private NetworkRoutes() {}

    public static void register(Javalin app) {
        app.get("/api/networks", ctx -> {
            ctx.contentType("application/json");
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (NetworkDefinition net : NetworkStorage.get().all()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(JsonUtil.networkToJson(net));
            }
            sb.append(']');
            ctx.result(sb.toString());
        });

        app.post("/api/networks", ctx -> {
            if (!NetworkStorage.get().isInitialized()) {
                ctx.status(503);
                ctx.result("{\"error\":\"network storage not initialized\"}");
                return;
            }
            String body = ctx.body();
            String name = JsonUtil.extractStringField(body, "name");
            if (name == null || name.isBlank()) {
                ctx.status(400);
                ctx.result("{\"error\":\"name is required\"}");
                return;
            }
            List<NetworkMember> members = parseMembers(body);
            NetworkDefinition net = NetworkStorage.get().create(name, members);
            if (net == null) {
                ctx.status(500);
                ctx.result("{\"error\":\"failed to create network\"}");
                return;
            }
            ctx.contentType("application/json");
            ctx.result(JsonUtil.networkToJson(net));
        });

        app.put("/api/networks/{id}", ctx -> {
            String id = ctx.pathParam("id");
            if (!NetworkStorage.get().isInitialized()) {
                ctx.status(503);
                ctx.result("{\"error\":\"network storage not initialized\"}");
                return;
            }
            String body = ctx.body();
            String name = JsonUtil.extractStringField(body, "name");
            if (name == null || name.isBlank()) {
                ctx.status(400);
                ctx.result("{\"error\":\"name is required\"}");
                return;
            }
            List<NetworkMember> members = parseMembers(body);
            NetworkDefinition net = NetworkStorage.get().update(id, name, members);
            if (net == null) {
                ctx.status(404);
                ctx.result("{\"error\":\"network not found: " + JsonUtil.quote(id) + "\"}");
                return;
            }
            ctx.contentType("application/json");
            ctx.result(JsonUtil.networkToJson(net));
        });

        app.delete("/api/networks/{id}", ctx -> {
            String id = ctx.pathParam("id");
            if (!NetworkStorage.get().isInitialized()) {
                ctx.status(503);
                ctx.result("{\"error\":\"network storage not initialized\"}");
                return;
            }
            if (NetworkStorage.get().delete(id)) {
                ctx.contentType("application/json");
                ctx.result("{\"removed\":" + JsonUtil.quote(id) + "}");
            } else {
                ctx.status(404);
                ctx.result("{\"error\":\"network not found: " + JsonUtil.quote(id) + "\"}");
            }
        });
    }

    /**
     * Parse the "members" array from a request body. Each member object has:
     * boardName (required), role (default "producer"), label (optional), lineIndex (default 0).
     */
    private static List<NetworkMember> parseMembers(String body) {
        List<String> memberObjects = JsonUtil.extractObjectArrayField(body, "members");
        List<NetworkMember> members = new ArrayList<>();
        for (String obj : memberObjects) {
            String boardName = JsonUtil.extractStringField(obj, "boardName");
            if (boardName == null || boardName.isBlank()) continue;
            String role = JsonUtil.extractStringField(obj, "role");
            if (role == null || role.isBlank()) role = "producer";
            String label = JsonUtil.extractStringField(obj, "label");
            int lineIndex = JsonUtil.extractIntField(obj, "lineIndex", 0);
            members.add(new NetworkMember(boardName, role, label, lineIndex));
        }
        return members;
    }
}
