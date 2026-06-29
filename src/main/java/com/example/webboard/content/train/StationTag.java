package com.example.webboard.content.train;

import java.util.List;

/**
 * StationTag — user-defined label for a station (e.g. "Interchange", "Terminal", "Cargo Hub").
 *
 * <p>Different from {@link TrainLine}: a station may belong to multiple lines AND carry
 * multiple tags. Tags drive the dashboard's filter UI (show only "terminal" stations, etc.).
 *
 * <p>{@code stationNames} is the list of Create {@code GlobalStation.name} strings grouped
 * under this tag. When CRN is present, this is populated from CRN's
 * {@code StationTag.getAllStationNames()} via reflection — it lets the route search resolve
 * a tag name to the actual graph nodes. When CRN is absent (local fallback storage), this
 * is always empty because local tags have no station mapping.
 *
 * <p>{@code type} is free-form; we don't constrain it because CRN allows user-defined types.
 */
public record StationTag(
        String id,
        String name,
        String type,
        int color,
        List<String> stationNames
) {
    public StationTag {
        stationNames = stationNames == null ? List.of() : List.copyOf(stationNames);
    }

    /** Factory for local storage (no station mapping — stationNames defaults to empty). */
    public static StationTag create(String id, String name, String type, int color) {
        return new StationTag(id, name, type == null ? "" : type, color, List.of());
    }

    /** Factory for CRN-sourced tags (carries the station name mapping). */
    public static StationTag create(String id, String name, String type, int color,
                                    List<String> stationNames) {
        return new StationTag(id, name, type == null ? "" : type, color, stationNames);
    }
}
