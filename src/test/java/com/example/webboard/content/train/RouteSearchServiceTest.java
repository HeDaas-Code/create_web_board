package com.example.webboard.content.train;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RouteSearchServiceTest — exercises the pure-JDK BFS path search over a TrackGraphSnapshot.
 *
 * <p>The search runs entirely on the snapshot's nodes/edges/stations — no Create classes
 * involved. This lets us test path-finding correctness without booting MC.
 */
class RouteSearchServiceTest {

    private static final String OW = "minecraft:overworld";

    private static TrackGraphSnapshot.TrackNodePos node(String dim, int x, int y, int z) {
        return new TrackGraphSnapshot.TrackNodePos(dim, x, y, z);
    }

    private static TrackGraphSnapshot.TrackEdgeInfo edge(TrackGraphSnapshot.TrackNodePos a,
                                                          TrackGraphSnapshot.TrackNodePos b,
                                                          double len) {
        return new TrackGraphSnapshot.TrackEdgeInfo(a, b, len);
    }

    @Test
    void emptyGraph_returnsEmptyList() {
        TrackGraphSnapshot g = TrackGraphSnapshot.empty();
        List<RouteOption> routes = RouteSearchService.search(g, "A", "B", 5);
        assertTrue(routes.isEmpty());
    }

    @Test
    void missingStations_returnsEmpty() {
        TrackGraphSnapshot g = new TrackGraphSnapshot("g",
                List.of(node(OW, 0, 64, 0)),
                List.of(),
                List.of(StationInfo.station("A", OW, 0, 64, 0)),
                System.currentTimeMillis());
        assertTrue(RouteSearchService.search(g, "A", "MISSING", 5).isEmpty());
        assertTrue(RouteSearchService.search(g, "MISSING", "A", 5).isEmpty());
    }

    @Test
    void directEdge_returnsOneRoute() {
        TrackGraphSnapshot.TrackNodePos a = node(OW, 0, 64, 0);
        TrackGraphSnapshot.TrackNodePos b = node(OW, 10, 64, 0);
        TrackGraphSnapshot g = new TrackGraphSnapshot("g",
                List.of(a, b),
                List.of(edge(a, b, 10.0)),
                List.of(StationInfo.station("A", OW, 0, 64, 0),
                        StationInfo.station("B", OW, 10, 64, 0)),
                System.currentTimeMillis());
        List<RouteOption> routes = RouteSearchService.search(g, "A", "B", 5);
        assertEquals(1, routes.size());
        RouteOption r = routes.get(0);
        assertEquals("A", r.fromStation());
        assertEquals("B", r.toStation());
        assertEquals(List.of("A", "B"), r.hops());
        assertEquals(10.0, r.totalDistance(), 0.001);
    }

    @Test
    void multiHopPath_returnsCorrectSequence() {
        // A -- B -- C, search A to C
        TrackGraphSnapshot.TrackNodePos a = node(OW, 0, 64, 0);
        TrackGraphSnapshot.TrackNodePos b = node(OW, 10, 64, 0);
        TrackGraphSnapshot.TrackNodePos c = node(OW, 20, 64, 0);
        TrackGraphSnapshot g = new TrackGraphSnapshot("g",
                List.of(a, b, c),
                List.of(edge(a, b, 10.0), edge(b, c, 10.0)),
                List.of(StationInfo.station("A", OW, 0, 64, 0),
                        StationInfo.station("B", OW, 10, 64, 0),
                        StationInfo.station("C", OW, 20, 64, 0)),
                System.currentTimeMillis());
        List<RouteOption> routes = RouteSearchService.search(g, "A", "C", 5);
        assertEquals(1, routes.size());
        assertEquals(List.of("A", "B", "C"), routes.get(0).hops());
        assertEquals(20.0, routes.get(0).totalDistance(), 0.001);
    }

    @Test
    void multiplePaths_returnsShortestFirst() {
        // Two paths A -> D:
        //   Short: A -- D (length 5)
        //   Long:  A -- B -- C -- D (length 1+1+1 = 3)  — wait this is shorter!
        // Let me reverse: short path has lower total length.
        TrackGraphSnapshot.TrackNodePos a = node(OW, 0, 64, 0);
        TrackGraphSnapshot.TrackNodePos b = node(OW, 1, 64, 0);
        TrackGraphSnapshot.TrackNodePos c = node(OW, 2, 64, 0);
        TrackGraphSnapshot.TrackNodePos d = node(OW, 10, 64, 0);
        TrackGraphSnapshot g = new TrackGraphSnapshot("g",
                List.of(a, b, c, d),
                List.of(edge(a, d, 5.0),     // direct short
                        edge(a, b, 2.0),     // long way around
                        edge(b, c, 2.0),
                        edge(c, d, 2.0)),
                List.of(StationInfo.station("A", OW, 0, 64, 0),
                        StationInfo.station("B", OW, 1, 64, 0),
                        StationInfo.station("C", OW, 2, 64, 0),
                        StationInfo.station("D", OW, 10, 64, 0)),
                System.currentTimeMillis());
        List<RouteOption> routes = RouteSearchService.search(g, "A", "D", 5);
        // Both routes should be returned; the short one (length 5) first.
        assertEquals(2, routes.size());
        assertEquals(5.0, routes.get(0).totalDistance(), 0.001);
        assertEquals(6.0, routes.get(1).totalDistance(), 0.001);
        assertEquals(List.of("A", "D"), routes.get(0).hops());
    }

    @Test
    void noPath_returnsEmpty() {
        TrackGraphSnapshot.TrackNodePos a = node(OW, 0, 64, 0);
        TrackGraphSnapshot.TrackNodePos b = node(OW, 100, 64, 0);
        TrackGraphSnapshot g = new TrackGraphSnapshot("g",
                List.of(a, b),
                List.of(), // no edges
                List.of(StationInfo.station("A", OW, 0, 64, 0),
                        StationInfo.station("B", OW, 100, 64, 0)),
                System.currentTimeMillis());
        assertTrue(RouteSearchService.search(g, "A", "B", 5).isEmpty());
    }

    @Test
    void sameOriginAndDestination_returnsEmpty() {
        TrackGraphSnapshot g = new TrackGraphSnapshot("g",
                List.of(node(OW, 0, 64, 0)),
                List.of(),
                List.of(StationInfo.station("A", OW, 0, 64, 0)),
                System.currentTimeMillis());
        assertTrue(RouteSearchService.search(g, "A", "A", 5).isEmpty());
    }

    @Test
    void maxResults_capsReturnedRoutes() {
        // Build a graph with many parallel paths between A and D
        TrackGraphSnapshot.TrackNodePos a = node(OW, 0, 64, 0);
        TrackGraphSnapshot.TrackNodePos d = node(OW, 100, 64, 0);
        var b1 = node(OW, 50, 70, 0);
        var b2 = node(OW, 50, 75, 0);
        var b3 = node(OW, 50, 80, 0);
        TrackGraphSnapshot g = new TrackGraphSnapshot("g",
                List.of(a, d, b1, b2, b3),
                List.of(edge(a, d, 100.0),
                        edge(a, b1, 50.0), edge(b1, d, 50.0),
                        edge(a, b2, 50.0), edge(b2, d, 50.0),
                        edge(a, b3, 50.0), edge(b3, d, 50.0)),
                List.of(StationInfo.station("A", OW, 0, 64, 0),
                        StationInfo.station("B1", OW, 50, 70, 0),
                        StationInfo.station("B2", OW, 50, 75, 0),
                        StationInfo.station("B3", OW, 50, 80, 0),
                        StationInfo.station("D", OW, 100, 64, 0)),
                System.currentTimeMillis());
        // Limit to 2 routes
        List<RouteOption> routes = RouteSearchService.search(g, "A", "D", 2);
        assertEquals(2, routes.size());
        // Shortest (direct) must be first
        assertEquals(100.0, routes.get(0).totalDistance(), 0.001);
    }
}
