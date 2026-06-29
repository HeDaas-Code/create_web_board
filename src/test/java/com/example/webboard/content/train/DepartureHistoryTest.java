package com.example.webboard.content.train;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DepartureHistoryTest — exercises the per-station ring buffer of arrival/departure events.
 */
class DepartureHistoryTest {

    private DepartureHistory history;

    @BeforeEach
    void setup() {
        history = new DepartureHistory();
    }

    @Test
    void empty_returnsEmptyList() {
        assertTrue(history.recentAt("Central", 10).isEmpty());
    }

    @Test
    void record_addsToStationQueue() {
        history.record(DepartureRecord.arrival(1000, "t1", "Thomas", "Central", null, "1"));
        history.record(DepartureRecord.departure(2000, "t1", "Thomas", "Central", null, "1"));
        List<DepartureRecord> recs = history.recentAt("Central", 10);
        assertEquals(2, recs.size());
    }

    @Test
    void recentAt_returnsMostRecentFirst() {
        history.record(DepartureRecord.arrival(1000, "t1", "A", "S", null, "1"));
        history.record(DepartureRecord.arrival(2000, "t2", "B", "S", null, "1"));
        history.record(DepartureRecord.arrival(3000, "t3", "C", "S", null, "1"));
        List<DepartureRecord> recs = history.recentAt("S", 2);
        assertEquals(2, recs.size());
        // Most recent first
        assertEquals("C", recs.get(0).trainName());
        assertEquals("B", recs.get(1).trainName());
    }

    @Test
    void ringBuffer_evictsOldest_whenCapacityExceeded() {
        // Add 110 records — should keep only the last 100
        for (int i = 0; i < 110; i++) {
            history.record(DepartureRecord.arrival(i, "t" + i, "T" + i, "S", null, "1"));
        }
        List<DepartureRecord> recs = history.recentAt("S", 200);
        assertEquals(100, recs.size());
        // Most recent first: ts=109 should be at index 0
        assertEquals(109, recs.get(0).ts());
        // Oldest kept should be ts=10 (we evicted ts=0..9)
        assertEquals(10, recs.get(99).ts());
    }

    @Test
    void stationsAreIndependent() {
        history.record(DepartureRecord.arrival(1000, "t1", "A", "S1", null, "1"));
        history.record(DepartureRecord.arrival(2000, "t2", "B", "S2", null, "1"));
        assertEquals(1, history.recentAt("S1", 10).size());
        assertEquals(1, history.recentAt("S2", 10).size());
        assertTrue(history.recentAt("S3", 10).isEmpty());
    }

    @Test
    void allRecent_returnsAcrossStations_sortedDesc() {
        history.record(DepartureRecord.arrival(1000, "t1", "A", "S1", null, "1"));
        history.record(DepartureRecord.arrival(3000, "t3", "C", "S2", null, "1"));
        history.record(DepartureRecord.arrival(2000, "t2", "B", "S1", null, "1"));
        List<DepartureRecord> recs = history.allRecent(10);
        assertEquals(3, recs.size());
        assertEquals(3000, recs.get(0).ts());
        assertEquals(2000, recs.get(1).ts());
        assertEquals(1000, recs.get(2).ts());
    }

    @Test
    void allRecent_respectsLimit() {
        for (int i = 0; i < 50; i++) {
            history.record(DepartureRecord.arrival(i, "t" + i, "T" + i, "S", null, "1"));
        }
        assertEquals(5, history.allRecent(5).size());
        assertEquals(49, history.allRecent(5).get(0).ts());
    }

    @Test
    void clearStation_removesOnlyThatStation() {
        history.record(DepartureRecord.arrival(1000, "t1", "A", "S1", null, "1"));
        history.record(DepartureRecord.arrival(2000, "t2", "B", "S2", null, "1"));
        history.clearStation("S1");
        assertTrue(history.recentAt("S1", 10).isEmpty());
        assertEquals(1, history.recentAt("S2", 10).size());
    }

    @Test
    void clearAll_emptiesEverything() {
        history.record(DepartureRecord.arrival(1000, "t1", "A", "S1", null, "1"));
        history.record(DepartureRecord.arrival(2000, "t2", "B", "S2", null, "1"));
        history.clearAll();
        assertTrue(history.recentAt("S1", 10).isEmpty());
        assertTrue(history.recentAt("S2", 10).isEmpty());
    }

    @Test
    void recentAt_limitLargerThanSize_returnsAllAvailable() {
        history.record(DepartureRecord.arrival(1000, "t1", "A", "S", null, "1"));
        assertEquals(1, history.recentAt("S", 100).size());
    }
}
