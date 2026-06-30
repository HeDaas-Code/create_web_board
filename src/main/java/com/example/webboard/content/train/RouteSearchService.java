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
 * <p><b>Multi-station search</b>: {@link #search(TrackGraphSnapshot, Set, Set, int)} accepts
 * sets of origin and destination station names. This supports CRN station tags — a tag like
 * "市中心" may group several Create stations, and the search finds the shortest path from any
 * origin to any destination. The single-station overload delegates with singleton sets.
 */
public final class RouteSearchService {

    /** Cap on candidate paths explored before giving up. Prevents pathological blowup. */
    static final int MAX_CANDIDATES = 1000;

    /** Typical train speed in blocks per second, used for the time estimate. */
    static final double TRAIN_SPEED_BPS = 8.0;

    private RouteSearchService() {}

    /**
     * Search up to {@code maxResults} simple paths from {@code fromStation} to {@code toStation}.
     * Delegates to {@link #search(TrackGraphSnapshot, Set, Set, int)} with singleton sets.
     */
    public static List<RouteOption> search(TrackGraphSnapshot graph,
                                           String fromStation, String toStation,
                                           int maxResults) {
        if (graph == null || graph.isEmpty()) return List.of();
        if (fromStation == null || toStation == null) return List.of();
        if (fromStation.equals(toStation)) return List.of();
        return search(graph, Set.of(fromStation), Set.of(toStation), maxResults);
    }

    /**
     * Search up to {@code maxResults} simple paths from any of {@code fromStations} to any of
     * {@code toStations}. Returns the shortest paths across all origin/destination combinations.
     *
     * @return sorted list (shortest first); empty if no path exists or inputs are invalid
     */
    public static List<RouteOption> search(TrackGraphSnapshot graph,
                                           Set<String> fromStations, Set<String> toStations,
                                           int maxResults) {
        if (graph == null || graph.isEmpty()) return List.of();
        if (fromStations == null || fromStations.isEmpty()) return List.of();
        if (toStations == null || toStations.isEmpty()) return List.of();
        if (maxResults <= 0) return List.of();

        // Build adjacency list keyed by node position (string form for hashing).
        Map<String, List<Neighbor>> adj = buildAdjacency(graph);

        // Resolve station names → node keys. Origins that don't resolve are skipped.
        Set<String> originKeys = new HashSet<>();
        Set<String> destKeys = new HashSet<>();
        for (String name : fromStations) {
            String key = findStationNodeKey(graph, name);
            if (key != null) originKeys.add(key);
        }
        for (String name : toStations) {
            String key = findStationNodeKey(graph, name);
            if (key != null) destKeys.add(key);
        }
        if (originKeys.isEmpty() || destKeys.isEmpty()) return List.of();
        // If origins and destinations overlap, remove the overlap from origins so we don't
        // search zero-length paths (same station is both origin and destination).
        originKeys.removeAll(destKeys);
        if (originKeys.isEmpty()) return List.of();

        // Bounded DFS for simple paths, from each origin.
        List<PathCandidate> candidates = new ArrayList<>();
        for (String originKey : originKeys) {
            Set<String> visited = new HashSet<>();
            visited.add(originKey);
            dfs(adj, originKey, destKeys, visited,
                    new ArrayList<>(List.of(originKey)), 0.0, candidates);
            if (candidates.size() >= MAX_CANDIDATES) break;
        }

        // Sort by total length, take top maxResults.
        candidates.sort(Comparator.comparingDouble(p -> p.length));
        int n = Math.min(maxResults, candidates.size());
        List<RouteOption> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            result.add(toRouteOption(graph, candidates.get(i)));
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

    private static void dfs(Map<String, List<Neighbor>> adj,
                            String current, Set<String> destKeys, Set<String> visited,
                            List<String> path, double length,
                            List<PathCandidate> out) {
        if (out.size() >= MAX_CANDIDATES) return;
        if (destKeys.contains(current)) {
            out.add(new PathCandidate(new ArrayList<>(path), length));
            return;
        }
        List<Neighbor> neighbors = adj.get(current);
        if (neighbors == null) return;
        for (Neighbor n : neighbors) {
            if (visited.contains(n.nodeKey)) continue;
            visited.add(n.nodeKey);
            path.add(n.nodeKey);
            dfs(adj, n.nodeKey, destKeys, visited, path, length + n.length, out);
            path.remove(path.size() - 1);
            visited.remove(n.nodeKey);
            if (out.size() >= MAX_CANDIDATES) return;
        }
    }

    private static RouteOption toRouteOption(TrackGraphSnapshot graph, PathCandidate c) {
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
        String actualFrom = hops.isEmpty() ? "" : hops.get(0);
        String actualTo = hops.isEmpty() ? "" : hops.get(hops.size() - 1);
        long estimatedMs = (long) ((c.length / TRAIN_SPEED_BPS) * 1000.0);
        return new RouteOption(actualFrom, actualTo, hops, c.length, estimatedMs);
    }

    private record Neighbor(String nodeKey, double length) {}

    private record PathCandidate(List<String> path, double length) {}
}
