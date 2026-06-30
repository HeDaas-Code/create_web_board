package com.example.webboard.content.train;

import java.util.List;

/**
 * TrainLine — a logical line that groups multiple stations and trains.
 *
 * <p>Example: "Eastern Main Line" running through stations A → B → C, served by trains T1, T2.
 * The dashboard renders a line as a colored polyline through its stations on the abstract map.
 *
 * <p>{@code stationNames} is an ordered list of station names (matching {@link StationInfo#name()}).
 * Order matters — it defines the line's direction for timetable display.
 */
public record TrainLine(
        String id,
        String name,
        String categoryId,
        int color,
        List<String> stationNames
) {
    public TrainLine {
        stationNames = stationNames == null ? List.of() : List.copyOf(stationNames);
    }

    public static TrainLine create(String id, String name, String categoryId, int color, List<String> stations) {
        return new TrainLine(id, name, categoryId, color, stations);
    }

    /** Number of stations on this line. */
    public int stationCount() {
        return stationNames.size();
    }

    /** True if this line passes through the given station name. */
    public boolean serves(String stationName) {
        return stationNames.contains(stationName);
    }
}
