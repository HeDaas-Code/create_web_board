package com.example.webboard.content.train;

/**
 * TrainPollerMath — pure-JDK timing + direction helpers extracted from {@link TrainPoller}.
 *
 * <p>These helpers have no Minecraft / Create dependencies, so they can be unit tested in
 * isolation via the local junit-standalone harness (no MC classpath required). {@link TrainPoller}
 * delegates to this class so the production code path is identical to the tested one.
 */
public final class TrainPollerMath {

    private TrainPollerMath() {}

    /** Train poll interval: every 10 ticks = 0.5s at 20 tps. */
    public static final int TRAIN_POLL_TICKS = 10;
    /** Topology poll interval: every 200 ticks = 10s at 20 tps. */
    public static final int GRAPH_POLL_TICKS = 200;

    /** Pure timing decision — should trains be polled this tick? */
    public static boolean shouldPollTrains(long tick) {
        return tick % TRAIN_POLL_TICKS == 0;
    }

    /** Pure timing decision — should topology be polled this tick? */
    public static boolean shouldPollGraph(long tick) {
        return tick % GRAPH_POLL_TICKS == 0;
    }

    /**
     * Map an (x,z) direction vector to one of 8 cardinal/intercardinal labels
     * (E, SE, S, SW, W, NW, N, NE). Angle 0° = +X = East, increasing clockwise toward +Z (South).
     */
    public static String cardinalOf(double dx, double dz) {
        double angle = Math.toDegrees(Math.atan2(dz, dx));
        if (angle < 0) angle += 360;
        // 8 sectors of 45°, anchored so that due East (0°) lands in the E sector.
        if (angle < 22.5 || angle >= 337.5) return "E";
        if (angle < 67.5)  return "SE";
        if (angle < 112.5) return "S";
        if (angle < 157.5) return "SW";
        if (angle < 202.5) return "W";
        if (angle < 247.5) return "NW";
        if (angle < 292.5) return "N";
        return "NE";
    }
}
