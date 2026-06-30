package com.example.webboard.content.train;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * TrainMirrorService — in-memory holder of the live train + track-graph snapshots.
 *
 * <p>The MC game thread calls {@link #replaceAllTrains} and {@link #replaceGraph} on every
 * poll tick (10s for topology, 0.5s for trains). HTTP/WS threads read the snapshots via
 * {@link #allTrains()} / {@link #currentGraph()} and subscribe to {@link ChangeEvent}s for
 * push notifications.
 *
 * <p><b>Why split out from {@code TrainMirrorService.tick()}?</b> The state holder is pure
 * JDK and unit-testable; the tick() method calls Create APIs ({@code Create.RAILWAYS},
 * {@code TrackGraph}) and is only exercisable in a real MC environment. Splitting lets the
 * contract be tested without booting Minecraft.
 *
 * <p><b>Dedup</b>: when an incoming {@code replaceAllTrains} payload is value-equal to the
 * current set (records implement {@code equals()}), we skip the listener fan-out. This
 * matters because the 0.5s train poll often produces identical snapshots when trains are
 * idle, and without dedup the WS hub would broadcast a no-op every 500ms.
 *
 * <p><b>Thread model</b>: writes are synchronized (only the game thread writes); reads use
 * the {@link ConcurrentHashMap} and a {@code volatile} graph reference directly. Listener
 * fan-out uses a {@link CopyOnWriteArraySet} so HTTP-thread subscribe/unsubscribe can't
 * trip a ConcurrentModificationException during broadcast.
 */
public final class TrainMirrorService {

    private static final TrainMirrorService INSTANCE = new TrainMirrorService();

    public static TrainMirrorService get() {
        return INSTANCE;
    }

    /** Public constructor — visible for tests that need a fresh isolated instance. */
    public TrainMirrorService() {}

    private final ConcurrentHashMap<String, TrainSnapshot> trains = new ConcurrentHashMap<>();
    private final Set<Consumer<ChangeEvent>> listeners = new CopyOnWriteArraySet<>();
    private volatile TrackGraphSnapshot graph = TrackGraphSnapshot.empty();
    private volatile List<TrainSnapshot> lastBroadcastTrains = List.of();
    private volatile TrackGraphSnapshot lastBroadcastGraph = TrackGraphSnapshot.empty();

    /** Result of a mutating op, broadcast to listeners. */
    public sealed interface ChangeEvent {
        record TrainsReplaced(List<TrainSnapshot> trains) implements ChangeEvent {}
        record GraphReplaced(TrackGraphSnapshot graph) implements ChangeEvent {}
    }

    /** Subscribe to changes. Same listener registered twice fires twice. */
    public void addListener(Consumer<ChangeEvent> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<ChangeEvent> listener) {
        listeners.remove(listener);
    }

    /**
     * Atomically replace the live train set. Fires a {@link ChangeEvent.TrainsReplaced}
     * unless the new set is value-equal to the last broadcast (dedup — see class javadoc).
     */
    public synchronized void replaceAllTrains(List<TrainSnapshot> snapshots) {
        trains.clear();
        for (TrainSnapshot s : snapshots) {
            trains.put(s.trainId(), s);
        }
        List<TrainSnapshot> immutable = List.copyOf(snapshots);
        // Dedup: only fire when content actually changed.
        if (!immutable.equals(lastBroadcastTrains)) {
            lastBroadcastTrains = immutable;
            listeners.forEach(l -> l.accept(new ChangeEvent.TrainsReplaced(immutable)));
        }
    }

    /**
     * Atomically replace the topology snapshot. Fires {@link ChangeEvent.GraphReplaced}
     * unless value-equal to the last broadcast.
     */
    public synchronized void replaceGraph(TrackGraphSnapshot newGraph) {
        TrackGraphSnapshot graph = (newGraph == null) ? TrackGraphSnapshot.empty() : newGraph;
        this.graph = graph;
        if (!graph.equals(lastBroadcastGraph)) {
            lastBroadcastGraph = graph;
            listeners.forEach(l -> l.accept(new ChangeEvent.GraphReplaced(graph)));
        }
    }

    /** Lookup a train by id (returns null if missing). */
    public TrainSnapshot getTrain(String trainId) {
        return trains.get(trainId);
    }

    /** Immutable snapshot of all trains (safe to iterate without locking). */
    public List<TrainSnapshot> allTrains() {
        return List.copyOf(trains.values());
    }

    /** Current topology snapshot (never null — returns {@link TrackGraphSnapshot#empty()} at startup). */
    public TrackGraphSnapshot currentGraph() {
        return graph;
    }

    /** Number of trains currently tracked. */
    public int trainCount() {
        return trains.size();
    }

    /** Reset all state. Called on server stop so a fresh start doesn't show stale data. */
    public synchronized void close() {
        trains.clear();
        graph = TrackGraphSnapshot.empty();
        lastBroadcastTrains = List.of();
        lastBroadcastGraph = TrackGraphSnapshot.empty();
    }
}
