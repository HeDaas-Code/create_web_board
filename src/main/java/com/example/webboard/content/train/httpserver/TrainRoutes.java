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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
 *       <li>{@code GET /api/trains/by-id/{id}} — single train snapshot (404 if missing)</li>
 *       <li>{@code GET /api/trains/graph} — current track-graph topology</li>
 *       <li>{@code GET /api/trains/health} — service status + CRN bridge status</li>
 *     </ul>
 *   </li>
 *   <li><b>Categories</b> (read-only — synced from CRN when present, fallback to
 *       {@link TrainMetadataStorage} when absent):
 *     <ul>
 *       <li>{@code GET /api/train-categories}</li>
 *     </ul>
 *   </li>
 *   <li><b>Lines</b> (read-only — synced from CRN): {@code GET /api/train-lines}</li>
 *   <li><b>Station tags</b> (read-only — synced from CRN): {@code GET /api/station-tags}</li>
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

        // NOTE: path is /api/trains/by-id/{id} (not /api/trains/{id}) to avoid the path-param
        // route shadowing the sibling literal routes /api/trains/graph and /api/trains/health.
        // Javalin matches {id} greedily, so /api/trains/health was being interpreted as
        // "fetch train with id=health" → 404. Reported in v0.7.1 field testing.
        app.get("/api/trains/by-id/{id}", ctx -> {
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
            CrnBridge crn = CrnBridge.get();
            ctx.contentType("application/json");
            ctx.result("{\"status\":\"ok\""
                    + ",\"trains\":" + svc.trainCount()
                    + ",\"graphNodes\":" + svc.currentGraph().nodes().size()
                    + ",\"graphStations\":" + svc.currentGraph().stations().size()
                    + ",\"crn\":\"" + crn.status() + "\""
                    + ",\"crnLines\":" + crn.lineCount()
                    + ",\"departures\":" + DepartureHistory.get().totalRecords()
                    + "}");
        });
    }

    // ---------- categories (read-only when CRN present) ----------

    private static void registerCategoryRoutes(Javalin app) {
        // GET only — categories are managed in-game via CRN, not on the web dashboard.
        // When CRN is present, serve from CrnBridge's synced cache. When absent, fall back
        // to the local TrainMetadataStorage (useful for no-CRN testing setups).
        app.get("/api/train-categories", ctx -> {
            CrnBridge crn = CrnBridge.get();
            List<TrainCategory> cats = crn.isPresent()
                    ? crn.categories()
                    : TrainMetadataStorage.get().allCategories();
            ctx.contentType("application/json");
            ctx.result(TrainJsonUtil.categoriesToJson(cats));
        });
    }

    // ---------- lines (read-only when CRN present) ----------

    private static void registerLineRoutes(Javalin app) {
        app.get("/api/train-lines", ctx -> {
            CrnBridge crn = CrnBridge.get();
            List<TrainLine> lines = crn.isPresent()
                    ? crn.lines()
                    : TrainMetadataStorage.get().allLines();
            ctx.contentType("application/json");
            ctx.result(TrainJsonUtil.linesToJson(lines));
        });
    }

    // ---------- station tags (read-only when CRN present) ----------

    private static void registerStationTagRoutes(Javalin app) {
        app.get("/api/station-tags", ctx -> {
            CrnBridge crn = CrnBridge.get();
            List<StationTag> tags = crn.isPresent()
                    ? crn.stationTags()
                    : TrainMetadataStorage.get().allStationTags();
            ctx.contentType("application/json");
            ctx.result(TrainJsonUtil.stationTagsToJson(tags));
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
        // The from/to params accept either a CRN station tag name (expanded to all stations in
        // the tag) or a raw Create station name. Tag names take priority — this matches the
        // dashboard UX where the dropdown shows tag names when CRN is present.
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
            Set<String> fromStations = resolveStationSet(from);
            Set<String> toStations = resolveStationSet(to);
            List<RouteOption> routes = RouteSearchService.search(graph, fromStations, toStations, maxResults);
            ctx.contentType("application/json");
            ctx.result(TrainJsonUtil.routeOptionsToJson(routes));
        });
    }

    /**
     * Resolve a search param to a set of Create station names. If the param matches a CRN
     * station tag name, expand to all stations grouped under that tag. Otherwise treat the
     * param as a direct Create station name (singleton set).
     */
    private static Set<String> resolveStationSet(String param) {
        for (StationTag tag : CrnBridge.get().stationTags()) {
            if (param.equals(tag.name()) && !tag.stationNames().isEmpty()) {
                return new HashSet<>(tag.stationNames());
            }
        }
        return Set.of(param);
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
