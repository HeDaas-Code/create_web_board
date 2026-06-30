package com.example.webboard.content.train;

import com.example.webboard.content.httpserver.JsonUtil;

import java.util.List;

/**
 * TrainJsonUtil — JSON serializers for the train-dashboard records.
 *
 * <p>Mirrors the pattern in {@code JsonUtil.boardToJson}: hand-rolled string builders that
 * avoid pulling in Jackson/Moshi (keeps the jar slim). All serializers delegate string
 * escaping to {@link JsonUtil#quote(String)} so we stay consistent with the rest of the API.
 */
public final class TrainJsonUtil {

    private TrainJsonUtil() {}

    /** Serialize a {@link TrainSnapshot} to its JSON object form. */
    public static String trainToJson(TrainSnapshot t) {
        StringBuilder sb = new StringBuilder("{\"trainId\":").append(JsonUtil.quote(t.trainId()))
                .append(",\"name\":").append(JsonUtil.quote(t.name()))
                .append(",\"dimension\":").append(JsonUtil.quote(t.dimension()))
                .append(",\"x\":").append(t.x())
                .append(",\"y\":").append(t.y())
                .append(",\"z\":").append(t.z())
                .append(",\"speed\":").append(t.speed())
                .append(",\"stopped\":").append(t.stopped())
                .append(",\"derailed\":").append(t.derailed())
                .append(",\"navigating\":").append(t.navigating())
                .append(",\"heading\":").append(JsonUtil.quote(t.heading()))
                .append(",\"navigationTarget\":").append(JsonUtil.quote(t.navigationTarget()))
                .append(",\"carriageCount\":").append(t.carriageCount())
                .append(",\"lastUpdatedMs\":").append(t.lastUpdatedMs())
                .append(",\"status\":").append(JsonUtil.quote(t.status()))
                .append(",\"stale\":").append(t.stale())
                .append('}');
        return sb.toString();
    }

    /** Serialize a list of {@link TrainSnapshot} as a JSON array. */
    public static String trainsToJson(List<TrainSnapshot> trains) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (TrainSnapshot t : trains) {
            if (!first) sb.append(',');
            first = false;
            sb.append(trainToJson(t));
        }
        sb.append(']');
        return sb.toString();
    }

    /** Serialize a {@link TrackGraphSnapshot} to its JSON object form. */
    public static String graphToJson(TrackGraphSnapshot g) {
        StringBuilder sb = new StringBuilder("{\"graphId\":").append(JsonUtil.quote(g.graphId()))
                .append(",\"lastUpdatedMs\":").append(g.lastUpdatedMs())
                .append(",\"stale\":").append(g.stale())
                .append(",\"nodes\":[");
        boolean first = true;
        for (TrackGraphSnapshot.TrackNodePos n : g.nodes()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"dimension\":").append(JsonUtil.quote(n.dimension()))
              .append(",\"x\":").append(n.x())
              .append(",\"y\":").append(n.y())
              .append(",\"z\":").append(n.z())
              .append('}');
        }
        sb.append("],\"edges\":[");
        first = true;
        for (TrackGraphSnapshot.TrackEdgeInfo e : g.edges()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"a\":{").append("\"dimension\":").append(JsonUtil.quote(e.a().dimension()))
              .append(",\"x\":").append(e.a().x())
              .append(",\"y\":").append(e.a().y())
              .append(",\"z\":").append(e.a().z())
              .append("},\"b\":{")
              .append("\"dimension\":").append(JsonUtil.quote(e.b().dimension()))
              .append(",\"x\":").append(e.b().x())
              .append(",\"y\":").append(e.b().y())
              .append(",\"z\":").append(e.b().z())
              .append("},\"length\":").append(e.length())
              .append('}');
        }
        sb.append("],\"stations\":[");
        first = true;
        for (StationInfo s : g.stations()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"name\":").append(JsonUtil.quote(s.name()))
              .append(",\"dimension\":").append(JsonUtil.quote(s.dimension()))
              .append(",\"x\":").append(s.x())
              .append(",\"y\":").append(s.y())
              .append(",\"z\":").append(s.z())
              .append(",\"type\":").append(JsonUtil.quote(s.type()))
              .append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    /** Serialize a {@link TrainCategory} to its JSON object form. */
    public static String categoryToJson(TrainCategory c) {
        StringBuilder sb = new StringBuilder("{\"id\":").append(JsonUtil.quote(c.id()))
                .append(",\"name\":").append(JsonUtil.quote(c.name()))
                .append(",\"color\":").append(c.color())
                .append(",\"freightType\":").append(JsonUtil.quote(c.freightType()))
                .append('}');
        return sb.toString();
    }

    public static String categoriesToJson(List<TrainCategory> list) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (TrainCategory c : list) {
            if (!first) sb.append(',');
            first = false;
            sb.append(categoryToJson(c));
        }
        sb.append(']');
        return sb.toString();
    }

    /** Serialize a {@link TrainLine} to its JSON object form. */
    public static String lineToJson(TrainLine l) {
        StringBuilder sb = new StringBuilder("{\"id\":").append(JsonUtil.quote(l.id()))
                .append(",\"name\":").append(JsonUtil.quote(l.name()))
                .append(",\"categoryId\":").append(JsonUtil.quote(l.categoryId()))
                .append(",\"color\":").append(l.color())
                .append(",\"stationNames\":[");
        boolean first = true;
        for (String s : l.stationNames()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(JsonUtil.quote(s));
        }
        sb.append("]}");
        return sb.toString();
    }

    public static String linesToJson(List<TrainLine> list) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (TrainLine l : list) {
            if (!first) sb.append(',');
            first = false;
            sb.append(lineToJson(l));
        }
        sb.append(']');
        return sb.toString();
    }

    /** Serialize a {@link StationTag} to its JSON object form. */
    public static String stationTagToJson(StationTag t) {
        StringBuilder sb = new StringBuilder("{\"id\":").append(JsonUtil.quote(t.id()))
                .append(",\"name\":").append(JsonUtil.quote(t.name()))
                .append(",\"type\":").append(JsonUtil.quote(t.type()))
                .append(",\"color\":").append(t.color())
                .append(",\"stationNames\":[");
        boolean first = true;
        for (String s : t.stationNames()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(JsonUtil.quote(s));
        }
        sb.append("]}");
        return sb.toString();
    }

    public static String stationTagsToJson(List<StationTag> list) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (StationTag t : list) {
            if (!first) sb.append(',');
            first = false;
            sb.append(stationTagToJson(t));
        }
        sb.append(']');
        return sb.toString();
    }

    /** Serialize a {@link TrainMetadata} to its JSON object form. */
    public static String metadataToJson(TrainMetadata m) {
        StringBuilder sb = new StringBuilder("{\"trainId\":").append(JsonUtil.quote(m.trainId()))
                .append(",\"trainName\":").append(JsonUtil.quote(m.trainName()))
                .append(",\"categoryId\":").append(JsonUtil.quote(m.categoryId()))
                .append(",\"lineId\":").append(JsonUtil.quote(m.lineId()))
                .append(",\"color\":").append(m.color())
                .append(",\"notes\":").append(JsonUtil.quote(m.notes()))
                .append(",\"lastUpdatedMs\":").append(m.lastUpdatedMs())
                .append('}');
        return sb.toString();
    }

    public static String metadataListToJson(List<TrainMetadata> list) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (TrainMetadata m : list) {
            if (!first) sb.append(',');
            first = false;
            sb.append(metadataToJson(m));
        }
        sb.append(']');
        return sb.toString();
    }

    /** Serialize a {@link DepartureRecord} to its JSON object form. */
    public static String departureToJson(DepartureRecord r) {
        StringBuilder sb = new StringBuilder("{\"ts\":").append(r.ts())
                .append(",\"trainId\":").append(JsonUtil.quote(r.trainId()))
                .append(",\"trainName\":").append(JsonUtil.quote(r.trainName()))
                .append(",\"stationName\":").append(JsonUtil.quote(r.stationName()))
                .append(",\"lineId\":").append(JsonUtil.quote(r.lineId()))
                .append(",\"direction\":").append(JsonUtil.quote(r.direction()))
                .append(",\"platform\":").append(JsonUtil.quote(r.platform()))
                .append('}');
        return sb.toString();
    }

    public static String departuresToJson(List<DepartureRecord> list) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (DepartureRecord r : list) {
            if (!first) sb.append(',');
            first = false;
            sb.append(departureToJson(r));
        }
        sb.append(']');
        return sb.toString();
    }

    /** Serialize a {@link RouteOption} to its JSON object form. */
    public static String routeOptionToJson(RouteOption r) {
        StringBuilder sb = new StringBuilder("{\"fromStation\":").append(JsonUtil.quote(r.fromStation()))
                .append(",\"toStation\":").append(JsonUtil.quote(r.toStation()))
                .append(",\"hops\":[");
        boolean first = true;
        for (String h : r.hops()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(JsonUtil.quote(h));
        }
        sb.append("],\"totalDistance\":").append(r.totalDistance())
          .append(",\"estimatedTimeMs\":").append(r.estimatedTimeMs())
          .append(",\"hopCount\":").append(r.hopCount())
          .append('}');
        return sb.toString();
    }

    public static String routeOptionsToJson(List<RouteOption> list) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (RouteOption r : list) {
            if (!first) sb.append(',');
            first = false;
            sb.append(routeOptionToJson(r));
        }
        sb.append(']');
        return sb.toString();
    }
}
