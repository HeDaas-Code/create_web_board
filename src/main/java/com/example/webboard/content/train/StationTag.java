package com.example.webboard.content.train;

/**
 * StationTag — user-defined label for a station (e.g. "Interchange", "Terminal", "Cargo Hub").
 *
 * <p>Different from {@link TrainLine}: a station may belong to multiple lines AND carry
 * multiple tags. Tags drive the dashboard's filter UI (show only "terminal" stations, etc.).
 *
 * <p>{@code type} is free-form; we don't constrain it because CRN allows user-defined types.
 */
public record StationTag(
        String id,
        String name,
        String type,
        int color
) {
    public static StationTag create(String id, String name, String type, int color) {
        return new StationTag(id, name, type == null ? "" : type, color);
    }
}
