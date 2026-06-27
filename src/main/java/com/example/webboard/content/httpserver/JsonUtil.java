package com.example.webboard.content.httpserver;

/**
 * JsonUtil — minimal hand-rolled JSON helpers shared by {@link WebSocketHub} and {@link ApiRoutes}.
 *
 * <p>We pull exactly two record types ({@code BoardContent} + {@code ChangeEvent}) into JSON,
 * and we never need a full library. Avoiding Jackson (the usual choice) keeps the mod jar
 * slim — Jackson alone is ~1.5MB, larger than the rest of the mod combined.
 *
 * <p>For anything richer (generic serialization, JSON Patch, streaming), reach for Jackson or
 * Moshi. For this codebase, two record types and three message shapes, ~20 lines is enough.
 */
public final class JsonUtil {

    private JsonUtil() {}

    /** Quote-escape a string for JSON. {@code null} serializes as the JSON literal {@code null}. */
    public static String quote(String s) {
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

    /** Serialize a {@code BoardContent} to its JSON object form (no enclosing braces wrapping). */
    public static String boardToJson(com.example.webboard.content.registry.BoardContent b) {
        StringBuilder sb = new StringBuilder("{\"name\":").append(quote(b.name()))
                .append(",\"sourceType\":").append(quote(b.sourceType()))
                .append(",\"sourceLabel\":").append(quote(com.example.webboard.content.mirror.SourceLabels.label(b.sourceType())))
                .append(",\"lines\":[");
        boolean first = true;
        for (String line : b.lines()) {
            if (!first) sb.append(',');
            sb.append(quote(line));
            first = false;
        }
        sb.append("],\"lastUpdatedMs\":").append(b.lastUpdatedMs())
                .append(",\"stale\":").append(b.stale())
                .append('}');
        return sb.toString();
    }
}
