package com.example.webboard.content.httpserver;

import com.example.webboard.content.registry.BoardRegistry;
import com.example.webboard.content.registry.BoardRegistry.ChangeEvent;

import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocketHub — holds the set of connected browser WS sessions and broadcasts
 * BoardRegistry changes to all of them.
 *
 * <p>Thread model: {@link #broadcast} may be called from any thread (listener invocation
 * happens on the BoardRegistry writer thread, which is the game thread for #2). The
 * {@link WsContext#send} call is itself thread-safe per Javalin docs.
 *
 * <p>Cleanup: Javalin does not auto-remove closed sessions from a user-held set, so we
 * register an onClose hook in the WS config to prune the set.
 */
public class WebSocketHub {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebSocketHub.class);

    private final Set<WsContext> sessions = ConcurrentHashMap.newKeySet();
    private final BoardRegistry registry;

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
                sb.append(toJson(b));
                first = false;
            }
            sb.append("]}");
            ctx.send(sb.toString());
        } catch (Exception e) {
            LOGGER.debug("[web_board] initial snapshot send failed for {}: {}", ctx.sessionId(), e.toString());
        }
    }

    private void onChange(ChangeEvent ev) {
        String json = switch (ev) {
            case ChangeEvent.Put put -> "{\"type\":\"update\",\"board\":" + toJson(put.content()) + "}";
            case ChangeEvent.Remove rm -> "{\"type\":\"remove\",\"name\":" + quote(rm.name()) + "}";
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

    /** Minimal hand-rolled JSON to avoid pulling in Jackson for two record types. */
    static String toJson(com.example.webboard.content.registry.BoardContent b) {
        StringBuilder sb = new StringBuilder("{\"name\":").append(quote(b.name()))
                .append(",\"sourceType\":").append(quote(b.sourceType()))
                .append(",\"lines\":[");
        boolean first = true;
        for (String line : b.lines()) {
            if (!first) sb.append(',');
            sb.append(quote(line));
            first = false;
        }
        sb.append("],\"lastUpdatedMs\":").append(b.lastUpdatedMs()).append('}');
        return sb.toString();
    }

    /** Quote-escape a string for JSON. */
    static String quote(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}