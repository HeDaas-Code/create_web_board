package com.example.webboard.content.train;

/**
 * TrainSnapshot — immutable live state of one Create train at a point in time.
 *
 * <p>Built on the MC game thread by {@link TrainMirrorService} from {@code Create.RAILWAYS}
 * graph traversal, then published to HTTP/WS readers. The HTTP layer never touches Create
 * classes — it only reads these records, so unit tests need no MC classpath.
 *
 * <p>Fields mirror what the dashboard needs to render a train marker + tooltip:
 * position, speed, status, next station, schedule destination.
 */
public record TrainSnapshot(
        String trainId,
        String name,
        String dimension,
        int x, int y, int z,
        double speed,
        boolean stopped,
        boolean derailed,
        boolean navigating,
        String heading,
        String navigationTarget,
        int carriageCount,
        long lastUpdatedMs
) {
    /**
     * Build a snapshot with only the mandatory fields. Convenience for tests + the
     * common case where the schedule hasn't been resolved yet.
     */
    public static TrainSnapshot of(String trainId, String name, String dimension,
                                   int x, int y, int z, double speed, boolean stopped,
                                   boolean derailed) {
        return new TrainSnapshot(trainId, name, dimension, x, y, z, speed, stopped,
                derailed, false, null, null, 1, System.currentTimeMillis());
    }

    /** A train is "offline" if no update has been received in 15s. */
    public boolean stale() {
        return lastUpdatedMs > 0 && System.currentTimeMillis() - lastUpdatedMs > 15_000;
    }

    /** Status string for the UI: one of {@code running}, {@code stopped}, {@code derailed}, {@code offline}. */
    public String status() {
        if (stale()) return "offline";
        if (derailed) return "derailed";
        if (stopped) return "stopped";
        return "running";
    }
}
