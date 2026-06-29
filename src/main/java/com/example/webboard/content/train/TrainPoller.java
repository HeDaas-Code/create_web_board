package com.example.webboard.content.train;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.GlobalRailwayManager;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.station.GlobalStation;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * TrainPoller — game-thread poller that mirrors Create's live train + track-graph state
 * into {@link TrainMirrorService} for HTTP/WS consumption.
 *
 * <p><b>Cadence</b>: trains are polled every {@link #TRAIN_POLL_TICKS} (0.5s @ 20 tps) because
 * positions change quickly; topology is polled every {@link #GRAPH_POLL_TICKS} (10s) because
 * track graphs are usually stable and full traversal is more expensive.
 *
 * <p><b>Why poll instead of events?</b> Create's train mutation events are client-visible
 * packets, not a clean server-side change feed. Polling {@code Create.RAILWAYS.trains} on the
 * game thread is the simplest correct source of truth — {@link TrainMirrorService} dedups
 * value-equal snapshots so the downstream WS broadcast only fires on actual change.
 *
 * <p><b>Defensive</b>: every Create API touch is wrapped in try/catch. If Create's internals
 * throw (e.g. a train mid- migration between graphs), we log and continue — the dashboard
 * shows the last good snapshot rather than crashing the server tick.
 *
 * <p><b>Not unit-testable</b>: this class touches MC + Create classes that only resolve inside
 * a real game. The testable timing logic lives in {@link #shouldPollTrains(long)} and
 * {@link #shouldPollGraph(long)} which are pure functions of the tick counter.
 */
@EventBusSubscriber(modid = "create_web_board")
public final class TrainPoller {

    private static final Logger LOGGER = LoggerFactory.getLogger(TrainPoller.class);

    private static long tickCounter = 0;
    private static volatile boolean enabled = false;

    private TrainPoller() {}

    /** Enable polling. Called from ServerLifecycle on server start. */
    public static void enable() {
        enabled = true;
        tickCounter = 0;
    }

    /** Disable polling and clear the mirror. Called from ServerLifecycle on server stop. */
    public static void disable() {
        enabled = false;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!enabled) return;
        tickCounter++;
        try {
            if (TrainPollerMath.shouldPollTrains(tickCounter)) {
                pollTrains();
            }
            if (TrainPollerMath.shouldPollGraph(tickCounter)) {
                pollGraph();
            }
        } catch (Throwable t) {
            // Never let a poller failure crash the server tick.
            LOGGER.warn("[web_board] TrainPoller tick failed: {}", t.toString());
        }
    }

    /** Read all trains from {@code Create.RAILWAYS.trains} into the mirror service. */
    private static void pollTrains() {
        GlobalRailwayManager railways = Create.RAILWAYS;
        if (railways == null || railways.trains == null) return;

        List<TrainSnapshot> snapshots = new ArrayList<>(railways.trains.size());
        for (Train train : railways.trains.values()) {
            try {
                TrainSnapshot snap = snapshotTrain(train);
                if (snap != null) snapshots.add(snap);
            } catch (Throwable t) {
                // One bad train shouldn't drop the rest.
                LOGGER.debug("[web_board] Failed to snapshot train: {}", t.toString());
            }
        }
        TrainMirrorService.get().replaceAllTrains(snapshots);
    }

    /** Read all track graphs from {@code Create.RAILWAYS.trackNetworks} into the mirror service. */
    private static void pollGraph() {
        GlobalRailwayManager railways = Create.RAILWAYS;
        if (railways == null || railways.trackNetworks == null) return;

        List<TrackGraphSnapshot.TrackNodePos> nodes = new ArrayList<>();
        List<TrackGraphSnapshot.TrackEdgeInfo> edges = new ArrayList<>();
        List<StationInfo> stations = new ArrayList<>();

        for (TrackGraph graph : railways.trackNetworks.values()) {
            try {
                collectGraph(graph, nodes, edges, stations);
            } catch (Throwable t) {
                LOGGER.debug("[web_board] Failed to snapshot graph: {}", t.toString());
            }
        }

        TrackGraphSnapshot snapshot = new TrackGraphSnapshot(
                "all",  // v0.7.1 merges all graphs into one logical view
                List.copyOf(nodes),
                List.copyOf(edges),
                List.copyOf(stations),
                System.currentTimeMillis());
        TrainMirrorService.get().replaceGraph(snapshot);

        // Sync CRN metadata (categories / lines / station tags) on the same slow cycle.
        // No-op when CRN is absent; reflection errors are caught inside CrnBridge.
        com.example.webboard.content.train.CrnBridge.get().syncMetadata();
    }

    /** Build a {@link TrainSnapshot} from a Create {@link Train}. Returns null if unusable. */
    private static TrainSnapshot snapshotTrain(Train train) {
        if (train == null || train.id == null) return null;

        String trainId = train.id.toString();
        String name = train.name != null ? train.name.getString() : "";
        boolean stopped = Math.abs(train.speed) < 0.05;
        boolean derailed = train.derailed;

        // Position: use the leading bogey of the first carriage. Fall back to (0,0,0) when
        // the train is mid-migration between graphs or carriages aren't yet placed.
        String dimension = "";
        int x = 0, y = 0, z = 0;
        if (train.carriages != null && !train.carriages.isEmpty()) {
            Carriage first = train.carriages.get(0);
            try {
                TravellingPoint leading = first.getLeadingPoint();
                if (leading != null && leading.edge != null && train.graph != null) {
                    Vec3 pos = leading.getPosition(train.graph);
                    if (pos != null) {
                        x = (int) Math.round(pos.x);
                        y = (int) Math.round(pos.y);
                        z = (int) Math.round(pos.z);
                    }
                    if (leading.node1 != null && leading.node1.getLocation() != null) {
                        ResourceKey<Level> dim = leading.node1.getLocation().getDimension();
                        if (dim != null) dimension = dim.location().toString();
                    }
                }
            } catch (Throwable ignored) {
                // Position is best-effort; the dashboard still shows name/status without it.
            }
        }

        // Navigation target: the station the train is heading to (or currently at).
        String navigationTarget = null;
        boolean navigating = false;
        try {
            GlobalStation current = train.getCurrentStation();
            if (current != null && current.name != null) {
                navigationTarget = current.name;
            } else if (train.navigation != null && train.navigation.destination != null
                    && train.navigation.destination.name != null) {
                navigationTarget = train.navigation.destination.name;
                navigating = true;
            }
        } catch (Throwable ignored) {}

        // Heading: rough cardinal direction from the leading edge, for the map tooltip.
        String heading = null;
        try {
            if (train.carriages != null && !train.carriages.isEmpty()) {
                Carriage first = train.carriages.get(0);
                TravellingPoint leading = first.getLeadingPoint();
                if (leading != null && leading.edge != null && train.graph != null) {
                    Vec3 dir = leading.edge.getDirection(true);
                    if (dir != null) {
                        heading = TrainPollerMath.cardinalOf(dir.x, dir.z);
                    }
                }
            }
        } catch (Throwable ignored) {}

        int carriageCount = train.carriages != null ? train.carriages.size() : 0;

        return new TrainSnapshot(
                trainId, name, dimension, x, y, z,
                train.speed, stopped, derailed,
                navigating, heading, navigationTarget,
                carriageCount, System.currentTimeMillis());
    }

    /** Walk one {@link TrackGraph}, appending its nodes/edges/stations to the aggregate lists. */
    private static void collectGraph(TrackGraph graph,
                                     List<TrackGraphSnapshot.TrackNodePos> nodes,
                                     List<TrackGraphSnapshot.TrackEdgeInfo> edges,
                                     List<StationInfo> stations) {
        if (graph == null) return;

        // Nodes — TrackNodeLocation extends Vec3i and stores coordinates at 2× resolution
        // (Create's design for half-block track precision). Divide by 2 to get real world
        // coords, otherwise nodes render at 2× the position of trains (which use
        // TravellingPoint.getPosition() returning real Vec3) → map misalignment.
        for (TrackNodeLocation loc : graph.getNodes()) {
            if (loc == null) continue;
            String dim = loc.getDimension() != null ? loc.getDimension().location().toString() : "";
            TrackGraphSnapshot.TrackNodePos pos = new TrackGraphSnapshot.TrackNodePos(
                    dim, loc.getX() / 2, loc.getY() / 2, loc.getZ() / 2);
            nodes.add(pos);
        }

        // Edges — iterate from each node's connection map. Each edge appears once per
        // direction (a→b and b→a reference the same TrackEdge); we emit only a→b to avoid
        // doubling the line count on the map. Coordinates ÷2 for the same reason as nodes.
        for (TrackNodeLocation loc : graph.getNodes()) {
            TrackNode from = graph.locateNode(loc);
            if (from == null) continue;
            Map<TrackNode, TrackEdge> conns = graph.getConnectionsFrom(from);
            if (conns == null) continue;
            for (Map.Entry<TrackNode, TrackEdge> e : conns.entrySet()) {
                TrackNode to = e.getKey();
                TrackEdge edge = e.getValue();
                if (to == null || edge == null) continue;
                TrackNodeLocation fromLoc = from.getLocation();
                TrackNodeLocation toLoc = to.getLocation();
                if (fromLoc == null || toLoc == null) continue;
                // Skip the b→a duplicate by ordering on a stable key.
                if (fromLoc.compareTo(toLoc) >= 0) continue;
                String fromDim = fromLoc.getDimension() != null ? fromLoc.getDimension().location().toString() : "";
                String toDim = toLoc.getDimension() != null ? toLoc.getDimension().location().toString() : "";
                TrackGraphSnapshot.TrackNodePos a =
                        new TrackGraphSnapshot.TrackNodePos(fromDim, fromLoc.getX() / 2, fromLoc.getY() / 2, fromLoc.getZ() / 2);
                TrackGraphSnapshot.TrackNodePos b =
                        new TrackGraphSnapshot.TrackNodePos(toDim, toLoc.getX() / 2, toLoc.getY() / 2, toLoc.getZ() / 2);
                double length;
                try {
                    length = edge.getLength();
                } catch (Throwable t) {
                    length = 0;
                }
                edges.add(new TrackGraphSnapshot.TrackEdgeInfo(a, b, length));
            }
        }

        // Stations — Create's typed edge-point collection. Same ÷2 coordinate fix.
        try {
            for (GlobalStation station : graph.getPoints(EdgePointType.STATION)) {
                if (station == null || station.name == null) continue;
                TrackNodeLocation loc = station.edgeLocation != null ? station.edgeLocation.getFirst() : null;
                String dim = loc != null && loc.getDimension() != null
                        ? loc.getDimension().location().toString() : "";
                int sx = loc != null ? loc.getX() / 2 : 0;
                int sy = loc != null ? loc.getY() / 2 : 0;
                int sz = loc != null ? loc.getZ() / 2 : 0;
                stations.add(new StationInfo(station.name, dim, sx, sy, sz, "station"));
            }
        } catch (Throwable t) {
            LOGGER.debug("[web_board] Failed to enumerate stations: {}", t.toString());
        }
    }
}
