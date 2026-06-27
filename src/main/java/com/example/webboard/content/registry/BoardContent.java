package com.example.webboard.content.registry;

import java.time.Instant;
import java.util.List;

/**
 * BoardContent — immutable snapshot of one Display Link's current display state.
 *
 * <p>Stored in {@link BoardRegistry}. Sent over HTTP/WS to browser as JSON. The shape is:
 * <pre>
 * {
 *   "name": "Board @ 12,64,-8",      // stable position-based key (never changes)
 *   "displayName": "Factory Status",  // optional user-set pretty name (null = use name)
 *   "sourceType": "create_web_board:web_board",
 *   "lines": ["Line 1", "Line 2", ...],
 *   "lastUpdatedMs": 1734567890123
 * }
 * </pre>
 *
 * <p><b>name vs displayName</b>: {@code name} is derived from the Display Link's block
 * position by {@code WebMirror} and is the stable registry key — it never changes across
 * content updates, so the dashboard can keep its card identity. {@code displayName} is an
 * optional user-set pretty name (set via the dashboard modal). When present, the UI shows it
 * instead of {@code name}; API calls always use the stable {@code name}.
 *
 * <p>Records avoid boilerplate equals/hashCode for free, and Jackson/Gson serialize them
 * field-by-field out of the box.
 */
public record BoardContent(
        String name,
        String displayName,
        String sourceType,
        List<String> lines,
        long lastUpdatedMs,
        List<String> tags,
        List<String> itemIds
) {
    /** Heartbeat timeout: a board with no update for this long is considered stale. */
    public static long STALE_THRESHOLD_MS = 30_000;

    public static BoardContent of(String name, String sourceType, List<String> lines) {
        return new BoardContent(name, null, sourceType, List.copyOf(lines),
                Instant.now().toEpochMilli(), List.of(), List.of());
    }

    /**
     * Returns true if this board hasn't been updated for longer than {@link #STALE_THRESHOLD_MS}.
     * A board with {@code lastUpdatedMs == 0} is never considered stale (just created, no heartbeat yet).
     */
    public boolean stale() {
        return lastUpdatedMs > 0 && System.currentTimeMillis() - lastUpdatedMs > STALE_THRESHOLD_MS;
    }

    /** The name to show in the UI: user-set {@code displayName} if present, else the stable {@code name}. */
    public String effectiveName() {
        return (displayName != null && !displayName.isEmpty()) ? displayName : name;
    }
}