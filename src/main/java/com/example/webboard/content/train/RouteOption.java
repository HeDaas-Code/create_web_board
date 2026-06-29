package com.example.webboard.content.train;

import java.util.List;

/**
 * RouteOption — one candidate route between two stations returned by {@link RouteSearchService}.
 *
 * <p>{@code hops} is the ordered list of station names the train passes through (origin first,
 * destination last). {@code totalDistance} is the sum of edge lengths in blocks.
 * {@code estimatedTimeMs} is a rough estimate (distance / typical train speed) — used by the
 * dashboard's Gantt strip, not authoritative.
 */
public record RouteOption(
        String fromStation,
        String toStation,
        List<String> hops,
        double totalDistance,
        long estimatedTimeMs
) {
    public RouteOption {
        hops = hops == null ? List.of() : List.copyOf(hops);
    }

    public int hopCount() {
        return hops.size();
    }
}
