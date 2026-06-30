package com.example.webboard.content.train;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TrainMirrorServiceTest — exercises the live train snapshot store + change listeners.
 *
 * <p>The service itself is a thin concurrent holder; the real game-thread extraction lives
 * in {@code TrainMirrorService.tick()} which calls Create APIs and is not exercised here.
 * These tests cover the pure-JDK contract: atomic replace, lookup, listener fan-out, dedup.
 */
class TrainMirrorServiceTest {

    private TrainMirrorService service;

    @BeforeEach
    void setup() {
        // Always start with a fresh instance so tests are independent. The singleton's
        // state would otherwise leak across tests.
        service = new TrainMirrorService();
    }

    @Test
    void emptyService_returnsEmptyGraph_andEmptyTrains() {
        assertTrue(service.allTrains().isEmpty());
        assertNotNull(service.currentGraph());
        assertTrue(service.currentGraph().isEmpty());
    }

    @Test
    void replaceAllTrains_publishesAllSnapshots() {
        TrainSnapshot t1 = TrainSnapshot.of("t1", "Thomas", "minecraft:overworld",
                10, 64, 20, 0.5, false, false);
        TrainSnapshot t2 = TrainSnapshot.of("t2", "Percy", "minecraft:overworld",
                50, 64, 80, 0.0, true, false);
        service.replaceAllTrains(List.of(t1, t2));
        assertEquals(2, service.allTrains().size());
        assertEquals("Thomas", service.getTrain("t1").name());
        assertEquals("Percy", service.getTrain("t2").name());
    }

    @Test
    void replaceAllTrains_replacesEntireSet_atomically() {
        service.replaceAllTrains(List.of(
                TrainSnapshot.of("t1", "A", "minecraft:overworld", 0, 0, 0, 0, true, false)));
        service.replaceAllTrains(List.of(
                TrainSnapshot.of("t2", "B", "minecraft:overworld", 0, 0, 0, 0, true, false)));
        // t1 is gone after the second replace
        assertNull(service.getTrain("t1"));
        assertNotNull(service.getTrain("t2"));
        assertEquals(1, service.allTrains().size());
    }

    @Test
    void replaceGraph_publishesNewGraph() {
        TrackGraphSnapshot g = new TrackGraphSnapshot("g1",
                List.of(new TrackGraphSnapshot.TrackNodePos("minecraft:overworld", 0, 64, 0)),
                List.of(),
                List.of(StationInfo.station("Central", "minecraft:overworld", 0, 64, 0)),
                System.currentTimeMillis());
        service.replaceGraph(g);
        assertEquals("g1", service.currentGraph().graphId());
        assertEquals(1, service.currentGraph().stations().size());
        assertEquals("Central", service.currentGraph().stations().get(0).name());
    }

    @Test
    void replaceAllTrains_firesListener_oncePerCall() {
        AtomicInteger fired = new AtomicInteger();
        service.addListener(ev -> fired.incrementAndGet());
        service.replaceAllTrains(List.of(
                TrainSnapshot.of("t1", "A", "minecraft:overworld", 0, 0, 0, 0, true, false)));
        assertEquals(1, fired.get());
    }

    @Test
    void replaceAllTrains_dedup_suppressesListenerWhenUnchanged() {
        AtomicInteger fired = new AtomicInteger();
        service.addListener(ev -> fired.incrementAndGet());
        TrainSnapshot t = TrainSnapshot.of("t1", "A", "minecraft:overworld", 0, 0, 0, 0, true, false);
        service.replaceAllTrains(List.of(t));
        assertEquals(1, fired.get());
        // Same content again — listener must NOT fire.
        service.replaceAllTrains(List.of(t));
        assertEquals(1, fired.get());
    }

    @Test
    void replaceGraph_firesListener_oncePerCall() {
        AtomicInteger fired = new AtomicInteger();
        service.addListener(ev -> {
            if (ev instanceof TrainMirrorService.ChangeEvent.GraphReplaced) fired.incrementAndGet();
        });
        TrackGraphSnapshot g = new TrackGraphSnapshot("g1", List.of(), List.of(), List.of(),
                System.currentTimeMillis());
        service.replaceGraph(g);
        assertEquals(1, fired.get());
        // Dedup: same graph (no nodes/edges) doesn't fire again
        service.replaceGraph(g);
        assertEquals(1, fired.get());
    }

    @Test
    void close_clearsState() {
        service.replaceAllTrains(List.of(
                TrainSnapshot.of("t1", "A", "minecraft:overworld", 0, 0, 0, 0, true, false)));
        service.replaceGraph(new TrackGraphSnapshot("g1", List.of(), List.of(), List.of(),
                System.currentTimeMillis()));
        service.close();
        assertTrue(service.allTrains().isEmpty());
        assertTrue(service.currentGraph().isEmpty());
    }

    @Test
    void allTrains_isImmutable() {
        service.replaceAllTrains(List.of(
                TrainSnapshot.of("t1", "A", "minecraft:overworld", 0, 0, 0, 0, true, false)));
        List<TrainSnapshot> all = service.allTrains();
        assertThrows(UnsupportedOperationException.class, () -> all.add(
                TrainSnapshot.of("t2", "B", "minecraft:overworld", 0, 0, 0, 0, true, false)));
    }

    @Test
    void replaceAllTrains_withEmptyList_clearsAll() {
        service.replaceAllTrains(List.of(
                TrainSnapshot.of("t1", "A", "minecraft:overworld", 0, 0, 0, 0, true, false)));
        service.replaceAllTrains(List.of());
        assertTrue(service.allTrains().isEmpty());
    }
}
