package com.example.webboard.content.train;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DepartureHistory — per-station ring buffer of recent arrival/departure events.
 *
 * <p>Filled either by {@code CrnBridge} (when CRN is installed and fires
 * {@code TrainArrivalAndDepartureEvent}) or by the dashboard's own polling of
 * {@link TrainSnapshot#navigationTarget()} transitions (degraded mode — detects when a train
 * newly arrives at or leaves a station by comparing consecutive snapshots).
 *
 * <p>Capped at {@link #MAX_PER_STATION} records per station to bound memory. Older entries
 * are evicted as new ones arrive. The dashboard queries the most-recent N for the timetable
 * panel and the Gantt chart strip.
 *
 * <p>Thread model: all methods synchronized — events arrive on the game thread, reads arrive
 * on HTTP threads, but the volume is low (a few events per minute per station).
 */
public final class DepartureHistory {

    /** Singleton for production use. Tests construct a fresh instance via the public ctor. */
    private static final DepartureHistory INSTANCE = new DepartureHistory();

    public static DepartureHistory get() {
        return INSTANCE;
    }

    /** Public constructor — visible for tests that need a fresh isolated instance. */
    public DepartureHistory() {}

    /** Maximum records kept per station. ~100 events × ~200 bytes ≈ 20KB per station. */
    public static final int MAX_PER_STATION = 100;

    private final Map<String, Deque<DepartureRecord>> byStation = new HashMap<>();

    /** Append a record to the station's ring buffer, evicting oldest if over capacity. */
    public synchronized void record(DepartureRecord rec) {
        if (rec == null) return;
        Deque<DepartureRecord> q = byStation.computeIfAbsent(rec.stationName(),
                k -> new ArrayDeque<>());
        q.addLast(rec);
        while (q.size() > MAX_PER_STATION) {
            q.removeFirst();
        }
    }

    /** Return the most-recent {@code limit} records for a station, newest first. */
    public synchronized List<DepartureRecord> recentAt(String stationName, int limit) {
        Deque<DepartureRecord> q = byStation.get(stationName);
        if (q == null || q.isEmpty()) return List.of();
        int n = Math.min(limit, q.size());
        // Walk from the tail (most recent) backwards.
        List<DepartureRecord> result = new ArrayList<>(n);
        var it = q.descendingIterator();
        for (int i = 0; i < n && it.hasNext(); i++) {
            result.add(it.next());
        }
        return result;
    }

    /** Return the most-recent {@code limit} records across all stations, newest first. */
    public synchronized List<DepartureRecord> allRecent(int limit) {
        List<DepartureRecord> all = new ArrayList<>();
        for (Deque<DepartureRecord> q : byStation.values()) {
            all.addAll(q);
        }
        // Sort by ts descending (newest first).
        all.sort((a, b) -> Long.compare(b.ts(), a.ts()));
        if (all.size() > limit) {
            all = new ArrayList<>(all.subList(0, limit));
        }
        return all;
    }

    /** Clear one station's history. */
    public synchronized void clearStation(String stationName) {
        byStation.remove(stationName);
    }

    /** Clear all stations. Called on server stop. */
    public synchronized void clearAll() {
        byStation.clear();
    }

    /** Total records across all stations. Useful for /api/health. */
    public synchronized int totalRecords() {
        int sum = 0;
        for (Deque<DepartureRecord> q : byStation.values()) sum += q.size();
        return sum;
    }

    /** Number of stations with at least one record. */
    public synchronized int stationCount() {
        return byStation.size();
    }
}
