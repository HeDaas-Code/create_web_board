package com.example.webboard.content.train;

/**
 * TrainMetadata — user-set per-train configuration (separate from the live TrainSnapshot).
 *
 * <p>The dashboard persists metadata keyed by {@code trainId} (the same id Create assigns to
 * the train — stable across server restarts as long as the train isn't deleted). When a train
 * is removed in-game, the matching metadata becomes orphaned; the dashboard shows orphaned
 * metadata entries as "train missing" so the user can clean them up.
 *
 * <p>{@code color = -1} means "use the category's color" (default).
 */
public record TrainMetadata(
        String trainId,
        String trainName,
        String categoryId,
        String lineId,
        int color,
        String notes,
        long lastUpdatedMs
) {
    public static final int DEFAULT_COLOR = -1;

    public static TrainMetadata create(String trainId, String trainName) {
        return new TrainMetadata(trainId, trainName, null, null, DEFAULT_COLOR, "",
                System.currentTimeMillis());
    }

    /** Returns the effective color: own color if set (>= 0), else {@code fallbackColor}. */
    public int effectiveColor(int fallbackColor) {
        return color >= 0 ? color : fallbackColor;
    }
}
