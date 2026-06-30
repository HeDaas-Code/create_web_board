package com.example.webboard.content.train;

import java.util.List;

/**
 * TrackGraphSnapshot — topology of all Create track graphs at a point in time.
 *
 * <p>One snapshot covers every dimension's track graph (Create merges them into a single
 * map keyed by graph id). The dashboard uses {@link #nodes} and {@link #edges} to draw the
 * real-coordinate map; {@link #stations} is the subset of nodes that are named stations
 * (where trains can stop).
 *
 * <p>Nodes are intentionally a flat list — Create's track graph is a sparse location-keyed
 * map, and the dashboard needs to iterate over all of them anyway. Edge endpoints reference
 * the same {@code (dimension, x, y, z)} tuples as nodes; we don't use synthetic ids because
 * Create's nodes are themselves addressed by location.
 */
public record TrackGraphSnapshot(
        String graphId,
        List<TrackNodePos> nodes,
        List<TrackEdgeInfo> edges,
        List<StationInfo> stations,
        long lastUpdatedMs
) {
    /** Empty snapshot used at startup before the first game-tick poll completes. */
    public static TrackGraphSnapshot empty() {
        return new TrackGraphSnapshot("", List.of(), List.of(), List.of(), 0L);
    }

    public boolean isEmpty() {
        return nodes.isEmpty() && edges.isEmpty() && stations.isEmpty();
    }

    /** A graph is "stale" if no refresh has come in 30s (the topology poll cadence is 10s). */
    public boolean stale() {
        return lastUpdatedMs > 0 && System.currentTimeMillis() - lastUpdatedMs > 30_000;
    }

    /** One node's location. */
    public record TrackNodePos(String dimension, int x, int y, int z) {
        public String key() {
            return dimension + "@" + x + "," + y + "," + z;
        }
    }

    /** One edge between two nodes (undirected). Length is in blocks. */
    public record TrackEdgeInfo(TrackNodePos a, TrackNodePos b, double length) {}
}
