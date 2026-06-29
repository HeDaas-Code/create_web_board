package com.example.webboard.content.train.httpserver;

import com.example.webboard.content.httpserver.JsonUtil;
import com.example.webboard.content.train.CrnBridge;
import com.example.webboard.content.train.DepartureHistory;
import com.example.webboard.content.train.RouteOption;
import com.example.webboard.content.train.RouteSearchService;
import com.example.webboard.content.train.StationInfo;
import com.example.webboard.content.train.StationTag;
import com.example.webboard.content.train.TrackGraphSnapshot;
import com.example.webboard.content.train.TrainCategory;
import com.example.webboard.content.train.TrainJsonUtil;
import com.example.webboard.content.train.TrainLine;
import com.example.webboard.content.train.TrainMetadata;
import com.example.webboard.content.train.TrainMetadataStorage;
import com.example.webboard.content.train.TrainMirrorService;
import com.example.webboard.content.train.TrainSnapshot;

import io.javalin.Javalin;

import java.util.List;

/**
 * TrainRoutes — registers all train-dashboard HTTP endpoints on the Javalin app.
 *
 * <p>Consolidates the previously-planned {@code TrainCategoryRoutes}, {@code TrainLineRoutes},
 * {@code StationTagRoutes}, {@code RouteSearchRoutes}, {@code DepartureHistoryRoutes} into one
 * file: they all share the {@link TrainMetadataStorage} singleton and the route count is small
 * enough (~20 endpoints) that splitting adds file overhead without clarity gains.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li><b>Live data</b> (read-only, served from {@link TrainMirrorService}):
 *     <ul>
 *       <li>{@code GET /api/trains} — all live train snapshots</li>
 *       <li>{@code GET /api/trains/{id}} — single train snapshot (404 if missing)</li>
 *       <li>{@code GET /api/trains/graph} — current track-graph topology</li>
 *       <li>{@code GET /api/trains/health} — service status + CRN bridge status</li>
 *     </ul>
 *   </li>
 *   <li><b>Categories</b> (CRUD on {@link TrainMetadataStorage}):
 *     <ul>
 *       <li>{@code GET /api/train-categories}</li>
 *       <li>{@code POST /api/train-categories} — body {@code {"name":"...","color":0,"freightType":"freight"}}</li>
 *       <li>{@code PUT /api/train-categories/{id}}</li>
 *       <li>{@code DELETE /api/train-categories/{id}}</li>
 *     </ul>
 *   </li>
 *   <li><b>Lines</b> (CRUD): {@code GET/POST/PUT/DELETE /api/train-lines[/{id}]}</li>
 *   <li><b>Station tags</b> (CRUD): {@code GET/POST/PUT/DELETE /api/station-tags[/{id}]}</li>
 *   <li><b>Train metadata</b> (per-train user config):
 *     <ul>
 *       <li>{@code GET /api/train-metadata}</li>
 *       <li>{@code PUT /api/train-metadata/{trainId}} — upsert</li>
 *       <li>{@code DELETE /api/train-metadata/{trainId}}</li>
 *     </ul>
 *   </li>
 *   <li><b>Departures</b> (read-only, served from {@link DepartureHistory}):
 *     <ul>
 *       <li>{@code GET /api/departures?station=...&limit=...}</li>
 *       <li>{@code GET /api/departures/all?limit=...}</li>
 *     </ul>
 *   </li>
 *   <li><b>Route search</b>:
 *     <ul>
 *       <li>{@code GET /api/routes/search?from=...&to=...&maxResults=...}</li>
 *     </ul>
 *   </li>
 * </ul>
 */
public final class TrainRoutes {

    private TrainRoutes() {}

    public static void register(Javalin app) {
        registerLiveDataRoutes(app);
        registerCategoryRoutes(app);
        registerLineRoutes(app);
        registerStationTagRoutes(app);
        registerTrainMetadataRoutes(app);
        registerDepartureRoutes(app);
        registerRouteSearchRoutes(app);
    }

    // ---------- live data ----------

    private static void registerLiveDataRoutes(Javalin app) {
        app.get("/api/trains", ctx -> {
            ctx.contentType("application/json");
            ctx.result(TrainJsonUtil.trainsToJson(TrainMirrorService.get().allTrains()));
        });

        app.get("/api/trains/{id}", ctx -> {
            String id = ctx.pathParam("id");
            TrainSnapshot t = TrainMirrorService.get().getTrain(id);
            if (t == null) {
                ctx.status(404);
                ctx.result("{\"error\":\"train not found: " + JsonUtil.quote(id) + "\"}");
                return;
            }
            ctx.contentType("application/json");
            ctx.result(TrainJsonUtil.trainToJson(t));
        });

        app.get("/api/trains/graph", ctx -> {
            ctx.contentType("application/json");
            ctx.result(TrainJsonUtil.graphToJson(TrainMirrorService.get().currentGraph()));
        });

        app.get("/api/trains/health", ctx -> {
            TrainMirrorService svc = TrainMirrorService.get();
            ctx.contentType("application/json");
            ctx.result("{\"status\":\"ok\""
                    + ",\"trains\":" + svc.trainCount()
                    + ",\"graphNodes\":" + svc.currentGraph().nodes().size()
                    + ",\"graphStations\":" + svc.currentGraph().stations().size()
                    + ",\"crn\":\"" + CrnBridge.get().status() + "\""
                    + ",\"departures\":" + DepartureHistory.get().totalRecords()
                    + "}");
        });
    }

    // ---------- categories ----------

    private static void registerCategoryRoutes(Javalin app) {
        app.get("/api/train-categories", ctx -> {
            ctx.contentType("application/json");
            ctx.result(TrainJsonUtil.categoriesToJson(TrainMetadataStorage.get().allCategories()));
        });

        app.post("/api/train-categories", ctx -> {
            if (!TrainMetadataStorage.get().isInitialized()) { ctx.status(503); return; }
            String body = ctx.body();
            String name = JsonUtil.extractStringField(body, "name");
            int color = JsonUtil.extractIntField(body, "color", 0);
            String ftype = JsonUtil.extractStringField(body, "freightType");
            if (name == null || name.isBlank()) {
                ctx.status(400);
                ctx.result("{\"error\":\"name is required\"}");
                return;
            }
            TrainCategory c = TrainMetadataStorage.get().createCategory(name, color, ftype);
            ctx.contentType("application/json");
            ctx.result(TrainJsonUtil.categoryToJson(c));
        });

        app.put("/api/train-categories/{id}", ctx -> {
            if (!TrainMetadataStorage.get().isInitialized()) { ctx.status(503); return; }
            String id = ctx.pathParam("id");
            String body = ctx.body();
            String name = JsonUtil.extractStringField(body, "name");
            int color = JsonUtil.extractIntField(body, "color", 0);
            String ftype = JsonUtil.extractStringField(body, "freightType");
            TrainCategory c = TrainMetadataStorage.get().updateCategory(id, name, color, ftype);
            if (c == null) {
                ctx.status(404);
                ctx.result("{\"error\":\"category not found: " + JsonUtil.quote(id) + "\"}");
                return;
            }
            ctx.contentType("application/json");
            ctx.result(TrainJsonUtil.categoryToJson(c));
        });

        app.delete("/api/train-categories/{id}", ctx -> {
            String id = ctx.pathParam("id");
            boolean removed = TrainMetadataStorage.get().deleteCategory(id);
            if (!removed) {
                ctx.status(404);
                ctx.result("{\"error\":\"category not found: " + JsonUtil.quote(id) + "\"}");
                return;
            }
            ctx.contentType("application/json");
            ctx.result("{\"removed\":" + JsonUtil.quote(id) + "}");
        });
    }

    // ---------- lines ----------

    private static void registerLineRoutes(Javalin app) {
        app.get("/api/train-lines", ctx -> {
            ctx.contentType("application/json");
            ctx.result(TrainJsonUtil.linesToJson(TrainMetadataStorage.get().allLines()));
        });

        app.post("/api/train-lines", ctx -> {
            if (!TrainMetadataStorage.get().isInitialized()) { ctx.status(503); return; }
            String body = ctx.body();
            String name = JsonUtil.extractStringField(body, "name");
            String categoryId = JsonUtil.extractStringField(body, "categoryId");
            int color = JsonUtil.extractIntField(body, "color", 0);
            List<String> stations = JsonUtil.extractStringArrayField(body, "stationNames");
            if (name == null || name.isBlank()) {
                ctx.status(400);
                ctx.result("{\"error\":\"name is required\"}");
                return;
            }
            TrainLine l = TrainMetadataStorage.get().createLine(name, categoryId, color, stations);
            ctx.contentType("application/json");
            ctx.result(TrainJsonUtil.lineToJson(l));
        });

        app.put("/api/train-lines/{id}", ctx -> {
            if (!TrainMetadataStorage.get().isInitialized()) { ctx.status(503); return; }
            String id = ctx.pathParam("id");
            String body = ctx.body();
            String name = JsonUtil.extractStringField(body, "name");
            String categoryId = JsonUtil.extractStringField(body, "categoryId");
            int color = JsonUtil.extractIntField(body, "color", 0);
            List<String> stations = JsonUtil.extractStringArrayField(body, "stationNames");
            TrainLine l = TrainMetadataStorage.get().updateLine(id, name, categoryId, color, stations);
            if (l == null) {
                ctx.status(404);
                ctx.result("{\"error\":\"line not found: " + JsonUtil.quote(id) + "\"}");
                return;
            }
            ctx.contentType("application/json");
            ctx.result(TrainJsonUtil.lineToJson(l));
        });

        app.delete("/api/train-lines/{id}", ctx -> {
            String id = ctx.pathParam("id");
            boolean removed = TrainMetadataStorage.get().deleteLine(id);
            if (!removed) {
                ctx.status(404);
                ctx.result("{\"error\":\"line not found: " + JsonUtil.quote(id) + "\"}");
                return;
            }
            ctx.contentType("application/json");
            ctx.result("{\"removed\":" + JsonUtil.quote(id) + "}");
        });
    }

    // ---------- station tags ----------

    private static void registerStationTagRoutes(Javalin app) {
        app.get("/api/station-tags", ctx -> {
            ctx.contentType("application/json");
            ctx.result(TrainJsonUtil.stationTagsToJson(TrainMetadataStorage.get().allStationTags()));
        });

        app.post("/api/station-tags", ctx -> {
            if (!TrainMetadataStorage.get().isInitialized()) { ctx.status(503); return; }
            String body = ctx.body();
            String name = JsonUtil.extractStringField(body, "name");
            String type = JsonUtil.extractStringField(body, "type");
            int color = JsonUtil.extractIntField(body, "color", 0);
            if (name == null || name.isBlank()) {
                ctx.status(400);
                ctx.result("{\"error\":\"name is required\"}");
                return;
            }
            StationTag t = TrainMetadataStorage.get().createStationTag(name, type, color);
            ctx.contentType("application/json");
            ctx.result(TrainJsonUtil.stationTagToJson(t));
        });

        app.put("/api/station-tags/{id}", ctx -> {
            if (!TrainMetadataStorage.get().isInitialized()) { ctx.status(503); return; }
            String id = ctx.pathParam("id");
            String body = ctx.body();
            String name = JsonUtil.extractStringField(body, "name");
            String type = JsonUtil.extractStringField(body, "type");
            int color = JsonUtil.extractIntField(body, "color", 0);
            StationTag t = TrainMetadataStorage.get().updateStationTag(id, name, type, color);
            if (t == null) {
                ctx.status(404);
                ctx.result("{\"error\":\"station tag not found: " + JsonUtil.quote(id) + "\"}");
                return;
            }
            ctx.contentType("application/json");
            ctx.result(TrainJsonUtil.stationTagToJson(t));
        });

        app.delete("/api/station-tags/{id}", ctx -> {
            String id = ctx.pathParam("id");
            boolean removed = TrainMetadataStorage.get().deleteStationTag(id);
            if (!removed) {
                ctx.status(404);
                ctx.result("{\"error\":\"station tag not found: " + JsonUtil.quote(id) + "\"}");
                return;
            }
            ctx.contentType("application/json");
            ctx.result("{\"removed\":" + JsonUtil.quote(id) + "}");
        });
    }

    // ---------- train metadata ----------

    private static void registerTrainMetadataRoutes(Javalin app) {
        app.get("/api/train-metadata", ctx -> {
            ctx.contentType("application/json");
            ctx.result(TrainJsonUtil.metadataListToJson(TrainMetadataStorage.get().allTrainMetadata()));
        });

        // PUT /api/train-metadata/{trainId} -- upsert (create or replace).
        // Body: {"trainName":"...","categoryId":"...","lineId":"...","color":-1,"notes":"..."}
        app.put("/api/train-metadata/{trainId}", ctx -> {
            if (!TrainMetadataStorage.get().isInitialized()) { ctx.status(503); return; }
            String trainId = ctx.pathParam("trainId");
            String body = ctx.body();
            String trainName = JsonUtil.extractStringField(body, "trainName");
            String categoryId = JsonUtil.extractStringField(body, "categoryId");
            String lineId = JsonUtil.extractStringField(body, "lineId");
            int color = JsonUtil.extractIntField(body, "color", TrainMetadata.DEFAULT_COLOR);
            String notes = JsonUtil.extractStringField(body, "notes");
            TrainMetadata m = new TrainMetadata(trainId,
                    trainName == null ? "" : trainName,
                    categoryId, lineId, color,
                    notes == null ? "" : notes,
                    System.currentTimeMillis());
            TrainMetadata saved = TrainMetadataStorage.get().upsertMetadata(m);
            ctx.contentType("application/json");
            ctx.result(TrainJsonUtil.metadataToJson(saved));
        });

        app.delete("/api/train-metadata/{trainId}", ctx -> {
            String trainId = ctx.pathParam("trainId");
            boolean removed = TrainMetadataStorage.get().deleteMetadata(trainId);
            if (!removed) {
                ctx.status(404);
                ctx.result("{\"error\":\"train metadata not found: " + JsonUtil.quote(trainId) + "\"}");
                return;
            }
            ctx.contentType("application/json");
            ctx.result("{\"removed\":" + JsonUtil.quote(trainId) + "}");
        });
    }

    // ---------- departures ----------

    private static void registerDepartureRoutes(Javalin app) {
        // GET /api/departures?station=Central&limit=20
        app.get("/api/departures", ctx -> {
            String station = ctx.queryParam("station");
            int limit = parseLimit(ctx.queryParam("limit"), 20);
            ctx.contentType("application/json");
            if (station == null || station.isBlank()) {
                ctx.result(TrainJsonUtil.departuresToJson(DepartureHistory.get().allRecent(limit)));
            } else {
                ctx.result(TrainJsonUtil.departuresToJson(DepartureHistory.get().recentAt(station, limit)));
            }
        });

        // GET /api/departures/all?limit=50
        app.get("/api/departures/all", ctx -> {
            int limit = parseLimit(ctx.queryParam("limit"), 50);
            ctx.contentType("application/json");
            ctx.result(TrainJsonUtil.departuresToJson(DepartureHistory.get().allRecent(limit)));
        });
    }

    // ---------- route search ----------

    private static void registerRouteSearchRoutes(Javalin app) {
        // GET /api/routes/search?from=Central&to=Terminal&maxResults=3
        app.get("/api/routes/search", ctx -> {
            String from = ctx.queryParam("from");
            String to = ctx.queryParam("to");
            int maxResults = parseLimit(ctx.queryParam("maxResults"), 3);
            if (from == null || to == null || from.isBlank() || to.isBlank()) {
                ctx.status(400);
                ctx.result("{\"error\":\"'from' and 'to' query params are required\"}");
                return;
            }
            TrackGraphSnapshot graph = TrainMirrorService.get().currentGraph();
            List<RouteOption> routes = RouteSearchService.search(graph, from, to, maxResults);
            ctx.contentType("application/json");
            ctx.result(TrainJsonUtil.routeOptionsToJson(routes));
        });
    }

    // ---------- helpers ----------

    private static int parseLimit(String s, int defaultValue) {
        if (s == null) return defaultValue;
        try {
            int v = Integer.parseInt(s);
            return Math.max(1, Math.min(500, v));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
