package com.example.webboard.content.httpserver;

import com.example.webboard.content.persistence.BoardDatabase;
import com.example.webboard.content.registry.BoardRegistry;
import com.example.webboard.content.registry.BoardRegistry.ChangeEvent;

import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * WebSocketHub — holds the set of connected browser WS sessions and broadcasts
 * BoardRegistry changes to all of them.
 *
 * <p><b>Thread model &amp; performance</b>: {@code ChangeEvent} listeners fire synchronously
 * on the thread that called {@link BoardRegistry#put} / {@link BoardRegistry#remove} — and for
 * the display-link mirror that thread is the <em>game thread</em>. Originally the JSON build
 * (per-char string escaping) and the per-session {@link WsContext#send} ran inline on the game
 * thread too; with several active links + browser tabs that froze the game. Now
 * {@link #onChange} only submits a tiny task to a single-thread daemon executor and returns
 * immediately, so the game thread does nothing heavier than a {@code ConcurrentHashMap} put.
 * The single worker preserves submission order so the browser sees a consistent event stream.
 *
 * <p>Cleanup: Javalin does not auto-remove closed sessions from a user-held set, so we
 * register an onClose hook in the WS config to prune the set. The send executor is drained on
 * {@link #shutdown()} (called from {@code HttpServer.stop()} at server-stop).
 */
public class WebSocketHub {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebSocketHub.class);

    private final Set<WsContext> sessions = ConcurrentHashMap.newKeySet();
    private final BoardRegistry registry;
    private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "web-board-ws-sender");
        t.setDaemon(true);
        return t;
    });

    public WebSocketHub(BoardRegistry registry) {
        this.registry = registry;
        // Subscribe to all registry changes for the lifetime of the hub.
        registry.addListener(this::onChange);
    }

    public Set<WsContext> sessions() {
        return sessions;
    }

    public int connectionCount() {
        return sessions.size();
    }

    /**
     * Pushes the initial snapshot to a freshly-connected session. Send failures (closed
     * socket mid-handshake) are swallowed — the session will be pruned by its onClose hook.
     */
    public void sendInitialSnapshot(WsContext ctx) {
        try {
            StringBuilder sb = new StringBuilder("{\"type\":\"snapshot\",\"boards\":[");
            boolean first = true;
            for (var b : registry.all()) {
                if (!first) sb.append(',');
                sb.append(JsonUtil.boardToJson(b));
                first = false;
            }
            sb.append("]}");
            ctx.send(sb.toString());
        } catch (Exception e) {
            LOGGER.debug("[web_board] initial snapshot send failed for {}: {}", ctx.sessionId(), e.toString());
        }
    }

    /**
     * Listener entry point -- runs on the game thread. It must stay cheap: it only submits the
     * real work (JSON build + per-session send) to the worker thread. Returning quickly here is
     * what keeps the game thread responsive.
     *
     * <p>Also persists the change to SQLite so that boards updated from any source (e.g. WS
     * clients) survive a server restart. The DB write is guarded by {@code isInitialized()} so
     * it is a no-op before the database is ready.
     */
    private void onChange(ChangeEvent ev) {
        // Persist to SQLite (write-through). This ensures DB writes happen even when the
        // event comes from a different source (e.g. a WebSocket client action).
        switch (ev) {
            case ChangeEvent.Put put -> {
                if (BoardDatabase.get().isInitialized()) {
                    BoardDatabase.get().upsert(put.content());
                }
            }
            case ChangeEvent.Remove rm -> {
                if (BoardDatabase.get().isInitialized()) {
                    BoardDatabase.get().markRemoved(rm.name());
                }
            }
        }
        try {
            sendExecutor.submit(() -> broadcast(ev));
        } catch (Exception e) {
            // RejectedExecutionException if shutdown() already ran -- harmless at server stop.
            LOGGER.debug("[web_board] ws send task rejected: {}", e.toString());
        }
    }

    private void broadcast(ChangeEvent ev) {
        String json = switch (ev) {
            case ChangeEvent.Put put -> "{\"type\":\"update\",\"board\":" + JsonUtil.boardToJson(put.content()) + "}";
            case ChangeEvent.Remove rm -> "{\"type\":\"remove\",\"name\":" + JsonUtil.quote(rm.name()) + "}";
        };
        for (WsContext s : sessions) {
            try {
                s.send(json);
            } catch (Exception e) {
                LOGGER.debug("[web_board] ws send failed (will close): {}", e.toString());
                // Don't manually close here — let Javalin's onClose hook clean up.
            }
        }
    }

    /**
     * Drains pending sends and stops the worker thread. Called from {@code HttpServer.stop()}
     * during {@code ServerStoppingEvent}. A short grace window avoids dropping the last remove
     * events (e.g. boards cleared on server stop) that the dashboard may want to apply.
     */
    public void shutdown() {
        sendExecutor.shutdown();
        try {
            if (!sendExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                sendExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            sendExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
