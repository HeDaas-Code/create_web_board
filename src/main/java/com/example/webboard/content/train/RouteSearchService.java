package com.example.webboard.content.train;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RouteSearchService — pure-JDK K-shortest-paths search over a {@link TrackGraphSnapshot}.
 *
 * <p>Runs entirely on the snapshot's nodes/edges/stations — no Create classes needed, so it's
 * unit-testable without booting MC. The dashboard calls this from
 * {@code GET /api/routes/search?from=...&to=...&maxResults=...} and renders the results as a
 * list of candidate routes with their hop sequences and total lengths.
 *
 * <p><b>Algorithm</b>: a bounded DFS that enumerates simple paths (no repeated nodes) from
 * origin to destination, capped at {@link #MAX_CANDIDATES} candidates. The candidates are
 * then sorted by total length and the top {@code maxResults} are returned. For typical MC
 * train graphs (10–30 stations, mostly linear with a few loops) this completes in &lt;1ms;
 * pathological dense graphs are bounded by the candidate cap.
 *
 * <p>Station-name → node-position resolution uses the snapshot's {@code stations} list: a
 * station name maps to the node at the same {@code (dimension, x, y, z)}. Edges that pass
 * through non-station nodes still count toward the route's hop list — only the station names
 * are returned in {@link RouteOption#hops()}.
 */
public final class RouteSearchService {

    /** Cap on candidate paths explored before giving up. Prevents pathological blowup. */
    static final int MAX_CANDIDATES = 1000;

    /** Typical train speed in blocks per second, used for the time estimate. */
    static final double TRAIN_SPEED_BPS = 8.0;

    private RouteSearchService() {}

    /**
     * Search up to {@code maxResults} simple paths from {@code fromStation} to {@code toStation}.
     *
     * @return sorted list (shortest first); empty if no path exists or inputs are invalid
     */
    public static List<RouteOption> search(TrackGraphSnapshot graph,
                                           String fromStation, String toStation,
                                           int maxResults) {
        if (graph == null || graph.isEmpty()) return List.of();
        if (fromStation == null || toStation == null) return List.of();
        if (fromStation.equals(toStation)) return List.of();
        if (maxResults <= 0) return List.of();

        // Build adjacency list keyed by node position (string form for hashing).
        Map<String, List<Neighbor>> adj = buildAdjacency(graph);
        // Resolve station names → node positions.
        String originKey = findStationNodeKey(graph, fromStation);
        String destKey = findStationNodeKey(graph, toStation);
        if (originKey == null || destKey == null) return List.of();

        // Bounded DFS for simple paths.
        List<PathCandidate> candidates = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        visited.add(originKey);
        dfs(graph, adj, originKey, destKey, visited, new ArrayList<>(List.of(originKey)),
                0.0, candidates);

        // Sort by total length, take top maxResults.
        candidates.sort(Comparator.comparingDouble(p -> p.length));
        int n = Math.min(maxResults, candidates.size());
        List<RouteOption> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            PathCandidate c = candidates.get(i);
            result.add(toRouteOption(graph, fromStation, toStation, c));
        }
        return result;
    }

    private static Map<String, List<Neighbor>> buildAdjacency(TrackGraphSnapshot graph) {
        Map<String, List<Neighbor>> adj = new HashMap<>();
        for (TrackGraphSnapshot.TrackEdgeInfo e : graph.edges()) {
            String a = e.a().key();
            String b = e.b().key();
            adj.computeIfAbsent(a, k -> new ArrayList<>()).add(new Neighbor(b, e.length()));
            adj.computeIfAbsent(b, k -> new ArrayList<>()).add(new Neighbor(a, e.length()));
        }
        return adj;
    }

    private static String findStationNodeKey(TrackGraphSnapshot graph, String stationName) {
        for (StationInfo s : graph.stations()) {
            if (stationName.equals(s.name())) {
                return s.dimension() + "@" + s.x() + "," + s.y() + "," + s.z();
            }
        }
        return null;
    }

    private static void dfs(TrackGraphSnapshot graph, Map<String, List<Neighbor>> adj,
                            String current, String dest, Set<String> visited,
                            List<String> path, double length,
                            List<PathCandidate> out) {
        if (out.size() >= MAX_CANDIDATES) return;
        if (current.equals(dest)) {
            out.add(new PathCandidate(new ArrayList<>(path), length));
            return;
        }
        List<Neighbor> neighbors = adj.get(current);
        if (neighbors == null) return;
        for (Neighbor n : neighbors) {
            if (visited.contains(n.nodeKey)) continue;
            visited.add(n.nodeKey);
            path.add(n.nodeKey);
            dfs(graph, adj, n.nodeKey, dest, visited, path, length + n.length, out);
            path.remove(path.size() - 1);
            visited.remove(n.nodeKey);
            if (out.size() >= MAX_CANDIDATES) return;
        }
    }

    private static RouteOption toRouteOption(TrackGraphSnapshot graph,
                                             String fromStation, String toStation,
                                             PathCandidate c) {
        // Convert node keys back to station names; skip non-station nodes.
        Map<String, String> nodeKeyToStationName = new HashMap<>();
        for (StationInfo s : graph.stations()) {
            nodeKeyToStationName.put(s.dimension() + "@" + s.x() + "," + s.y() + "," + s.z(),
                    s.name());
        }
        List<String> hops = new ArrayList<>();
        for (String key : c.path) {
            String name = nodeKeyToStationName.get(key);
            if (name != null) hops.add(name);
        }
        long estimatedMs = (long) ((c.length / TRAIN_SPEED_BPS) * 1000.0);
        return new RouteOption(fromStation, toStation, hops, c.length, estimatedMs);
    }

    private record Neighbor(String nodeKey, double length) {}

    private record PathCandidate(List<String> path, double length) {}
}
