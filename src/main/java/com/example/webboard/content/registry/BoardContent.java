package com.example.webboard.content.registry;

import java.time.Instant;
import java.util.List;

/**
 * BoardContent — immutable snapshot of one Display Link's current display state.
 *
 * <p>Stored in {@link BoardRegistry}. Sent over HTTP/WS to browser as JSON. The shape is:
 * <pre>
 * {
 *   "name": "My Factory Status",
 *   "sourceType": "create_web_board:web_board",
 *   "lines": ["Line 1", "Line 2", ...],
 *   "lastUpdatedMs": 1734567890123
 * }
 * </pre>
 *
 * <p>Records avoid boilerplate equals/hashCode for free, and Jackson/Gson serialize them
 * field-by-field out of the box.
 */
public record BoardContent(
        String name,
        String sourceType,
        List<String> lines,
        long lastUpdatedMs
) {
    public static BoardContent of(String name, String sourceType, List<String> lines) {
        return new BoardContent(name, sourceType, List.copyOf(lines), Instant.now().toEpochMilli());
    }
}