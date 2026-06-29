package com.example.webboard.content.train;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * TrainPollerTest — exercises the pure timing + math helpers in {@link TrainPollerMath}.
 *
 * <p>The Create-API-touching parts of {@link TrainPoller} (pollTrains / pollGraph /
 * snapshotTrain) cannot be unit tested because they require a live Minecraft + Create
 * runtime. The cadence checks and cardinal-direction math are pure functions extracted
 * into {@link TrainPollerMath}, so they get full coverage here without any MC classpath.
 */
class TrainPollerTest {

    // ---------- cadence ----------

    @Test
    void trainsPollEvery10Ticks() {
        // Tick 10, 20, 30, ... should poll; others should not.
        for (long t = 1; t <= 100; t++) {
            boolean expected = (t % 10 == 0);
            assertEquals(expected, TrainPollerMath.shouldPollTrains(t),
                    "tick " + t + " should " + (expected ? "" : "not ") + "poll trains");
        }
    }

    @Test
    void graphPollsEvery200Ticks() {
        for (long t = 1; t <= 1000; t++) {
            boolean expected = (t % 200 == 0);
            assertEquals(expected, TrainPollerMath.shouldPollGraph(t),
                    "tick " + t + " should " + (expected ? "" : "not ") + "poll graph");
        }
    }

    @Test
    void graphPollImpliesTrainPoll() {
        // Every 200th tick is also divisible by 10, so both fire together.
        for (long t = 200; t <= 2000; t += 200) {
            assertTrue(TrainPollerMath.shouldPollTrains(t), "graph poll tick " + t + " should also poll trains");
            assertTrue(TrainPollerMath.shouldPollGraph(t));
        }
    }

    @Test
    void tickZeroPollsBoth() {
        // Edge case: tick 0 % anything == 0, so both fire. We never actually call with 0
        // (tickCounter starts at 1), but the contract should hold.
        assertTrue(TrainPollerMath.shouldPollTrains(0));
        assertTrue(TrainPollerMath.shouldPollGraph(0));
    }

    @Test
    void nonMultiplesDoNotPoll() {
        // Spot-check a few ticks that should never poll either.
        assertFalse(TrainPollerMath.shouldPollTrains(7));
        assertFalse(TrainPollerMath.shouldPollTrains(13));
        assertFalse(TrainPollerMath.shouldPollGraph(199));
        assertFalse(TrainPollerMath.shouldPollGraph(201));
    }

    @Test
    void constantsMatchCadence() {
        // The publicly exposed constants should agree with the helper decisions.
        assertEquals(10, TrainPollerMath.TRAIN_POLL_TICKS);
        assertEquals(200, TrainPollerMath.GRAPH_POLL_TICKS);
        assertTrue(TrainPollerMath.GRAPH_POLL_TICKS % TrainPollerMath.TRAIN_POLL_TICKS == 0,
                "graph interval must be a multiple of train interval");
    }

    // ---------- cardinal direction math ----------

    @Test
    void eastIsPositiveX() {
        assertEquals("E", TrainPollerMath.cardinalOf(1, 0));
        assertEquals("E", TrainPollerMath.cardinalOf(0.5, 0.01));  // tiny +z, mostly +x → E
    }

    @Test
    void westIsNegativeX() {
        assertEquals("W", TrainPollerMath.cardinalOf(-1, 0));
    }

    @Test
    void southIsPositiveZ() {
        assertEquals("S", TrainPollerMath.cardinalOf(0, 1));
    }

    @Test
    void northIsNegativeZ() {
        assertEquals("N", TrainPollerMath.cardinalOf(0, -1));
    }

    @Test
    void diagonals() {
        assertEquals("SE", TrainPollerMath.cardinalOf(1, 1));
        assertEquals("SW", TrainPollerMath.cardinalOf(-1, 1));
        assertEquals("NW", TrainPollerMath.cardinalOf(-1, -1));
        assertEquals("NE", TrainPollerMath.cardinalOf(1, -1));
    }

    @Test
    void zeroVectorFallsInEastSector() {
        // atan2(0,0) == 0 → 0° → East sector. Edge case but well-defined.
        assertEquals("E", TrainPollerMath.cardinalOf(0, 0));
    }

    @Test
    void boundaryAngles() {
        // Exactly 22.5° is the SE/E boundary; our half-open sector [22.5, 67.5) = SE.
        double rad = Math.toRadians(22.5);
        assertEquals("SE", TrainPollerMath.cardinalOf(Math.cos(rad), Math.sin(rad)));
        // 67.5° → boundary between SE and S, lands in S sector [67.5, 112.5)
        rad = Math.toRadians(67.5);
        assertEquals("S", TrainPollerMath.cardinalOf(Math.cos(rad), Math.sin(rad)));
    }

    @Test
    void allEightSectorsCovered() {
        // Walk 0° → 360° in 10° steps and confirm all 8 labels appear.
        boolean[] seen = new boolean[8];
        String[] labels = {"E", "SE", "S", "SW", "W", "NW", "N", "NE"};
        for (int deg = 0; deg < 360; deg += 10) {
            double rad = Math.toRadians(deg);
            String c = TrainPollerMath.cardinalOf(Math.cos(rad), Math.sin(rad));
            for (int i = 0; i < labels.length; i++) {
                if (labels[i].equals(c)) seen[i] = true;
            }
        }
        for (int i = 0; i < labels.length; i++) {
            assertTrue(seen[i], "cardinal " + labels[i] + " never hit in 0..360° sweep");
        }
    }

    @Test
    void magnitudeDoesNotAffectDirection() {
        // A direction vector scaled by any positive factor should yield the same cardinal.
        String base = TrainPollerMath.cardinalOf(3, 4);
        assertEquals(base, TrainPollerMath.cardinalOf(30, 40));
        assertEquals(base, TrainPollerMath.cardinalOf(0.3, 0.4));
    }
}
