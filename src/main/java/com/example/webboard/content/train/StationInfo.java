package com.example.webboard.content.train;

/**
 * StationInfo — one station (or signal) on the Create track graph.
 *
 * <p>Stations come from {@code Create.RAILWAYS.trackGraphs.*.stations} — they are the named
 * nodes a train can stop at. Signals and redstone links are also "nodes" but are not stations.
 *
 * <p>{@code type} distinguishes the three node categories for the dashboard:
 * {@code "station"}, {@code "signal"}, {@code "redstone"}.
 */
public record StationInfo(
        String name,
        String dimension,
        int x, int y, int z,
        String type
) {
    public static StationInfo station(String name, String dimension, int x, int y, int z) {
        return new StationInfo(name, dimension, x, y, z, "station");
    }

    public static StationInfo signal(String dimension, int x, int y, int z) {
        return new StationInfo("", dimension, x, y, z, "signal");
    }

    public boolean isStation() {
        return "station".equals(type);
    }
}
