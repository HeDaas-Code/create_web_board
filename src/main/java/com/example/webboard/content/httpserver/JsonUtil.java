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

    /**
     * Extract a string field's value from a small JSON object (e.g. a request body like
     * {@code {"displayName":"Factory"}}). Returns {@code null} if the field is absent or not a
     * string. Handles standard escape sequences. This is a controlled-input parser (the dashboard
     * modal), not a general JSON parser — fine for our tiny API payloads.
     */
    public static String extractStringField(String json, String field) {
        if (json == null) return null;
        String needle = "\"" + field + "\"";
        int i = json.indexOf(needle);
        if (i < 0) return null;
        i += needle.length();
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == ':') { i++; break; }
            if (Character.isWhitespace(c)) { i++; continue; }
            return null; // unexpected char before colon
        }
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != '"') return null;
        i++; // opening quote
        StringBuilder sb = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i++);
            if (c == '"') return sb.toString();
            if (c == '\\' && i < json.length()) {
                char esc = json.charAt(i++);
                switch (esc) {
                    case '"'  -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/'  -> sb.append('/');
                    case 'n'  -> sb.append('\n');
                    case 'r'  -> sb.append('\r');
                    case 't'  -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 <= json.length()) {
                            sb.append((char) Integer.parseInt(json.substring(i, i + 4), 16));
                            i += 4;
                        }
                    }
                    default -> sb.append(esc);
                }
            } else {
                sb.append(c);
            }
        }
        return null; // unterminated string
    }

    /**
     * Extract a JSON string array field's values from a small JSON object (e.g. a request body
     * like {@code {"tags":["a","b"]}} or {@code {"itemIds":["minecraft:iron_ingot"]}}).
     * Returns an empty list if the field is absent or not an array. Controlled-input parser
     * for the dashboard modal's tag/item API payloads — not a general JSON parser.
     */
    public static java.util.List<String> extractStringArrayField(String json, String field) {
        java.util.List<String> result = new java.util.ArrayList<>();
        if (json == null) return result;
        String needle = "\"" + field + "\"";
        int i = json.indexOf(needle);
        if (i < 0) return result;
        i += needle.length();
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == ':') { i++; break; }
            if (Character.isWhitespace(c)) { i++; continue; }
            return result; // unexpected char before colon
        }
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != '[') return result;
        i++; // opening bracket
        while (i < json.length()) {
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            if (i >= json.length()) break;
            if (json.charAt(i) == ']') return result;
            if (json.charAt(i) != '"') return result; // malformed
            i++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (i < json.length()) {
                char c = json.charAt(i++);
                if (c == '"') { result.add(sb.toString()); break; }
                if (c == '\\' && i < json.length()) {
                    char esc = json.charAt(i++);
                    switch (esc) {
                        case '"'  -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/'  -> sb.append('/');
                        case 'n'  -> sb.append('\n');
                        case 'r'  -> sb.append('\r');
                        case 't'  -> sb.append('\t');
                        case 'u' -> {
                            if (i + 4 <= json.length()) {
                                sb.append((char) Integer.parseInt(json.substring(i, i + 4), 16));
                                i += 4;
                            }
                        }
                        default -> sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            if (i < json.length() && json.charAt(i) == ',') { i++; continue; }
            if (i < json.length() && json.charAt(i) == ']') return result;
        }
        return result;
    }

    /**
     * Extract an int field's value from a small JSON object. Returns {@code defaultValue} if
     * the field is absent or not parseable as an int. Controlled-input parser for the
     * dashboard API payloads (e.g. network member lineIndex).
     */
    public static int extractIntField(String json, String field, int defaultValue) {
        if (json == null) return defaultValue;
        String needle = "\"" + field + "\"";
        int i = json.indexOf(needle);
        if (i < 0) return defaultValue;
        i += needle.length();
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == ':') { i++; break; }
            if (Character.isWhitespace(c)) { i++; continue; }
            return defaultValue;
        }
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        int start = i;
        if (i < json.length() && (json.charAt(i) == '-' || json.charAt(i) == '+')) i++;
        while (i < json.length() && Character.isDigit(json.charAt(i))) i++;
        if (start == i) return defaultValue;
        try {
            return Integer.parseInt(json.substring(start, i));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Extract a JSON array of objects from a field, returning each object as a raw JSON
     * substring (e.g. {@code {"boardName":"...","role":"..."}}). Used to parse network member
     * arrays from API request bodies. Returns an empty list if the field is absent or malformed.
     */
    public static java.util.List<String> extractObjectArrayField(String json, String field) {
        java.util.List<String> result = new java.util.ArrayList<>();
        if (json == null) return result;
        String needle = "\"" + field + "\"";
        int i = json.indexOf(needle);
        if (i < 0) return result;
        i += needle.length();
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == ':') { i++; break; }
            if (Character.isWhitespace(c)) { i++; continue; }
            return result;
        }
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != '[') return result;
        i++; // skip '['
        while (i < json.length()) {
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            if (i >= json.length() || json.charAt(i) == ']') break;
            if (json.charAt(i) != '{') break;
            // Find matching '}' accounting for nested strings
            int depth = 0;
            int start = i;
            while (i < json.length()) {
                char c = json.charAt(i);
                if (c == '"') {
                    // Skip the entire string
                    i++;
                    while (i < json.length()) {
                        char sc = json.charAt(i++);
                        if (sc == '\\' && i < json.length()) { i++; continue; }
                        if (sc == '"') break;
                    }
                    continue;
                }
                if (c == '{') depth++;
                else if (c == '}') { depth--; if (depth == 0) { i++; break; } }
                i++;
            }
            result.add(json.substring(start, i));
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            if (i < json.length() && json.charAt(i) == ',') { i++; continue; }
            break;
        }
        return result;
    }

    /** Serialize a {@code NetworkDefinition} to its JSON object form. */
    public static String networkToJson(com.example.webboard.content.network.NetworkDefinition net) {
        StringBuilder sb = new StringBuilder("{\"id\":").append(quote(net.id()))
                .append(",\"name\":").append(quote(net.name()))
                .append(",\"members\":[");
        boolean first = true;
        for (var m : net.members()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"boardName\":").append(quote(m.boardName()))
              .append(",\"role\":").append(quote(m.role()))
              .append(",\"label\":").append(quote(m.label()))
              .append(",\"lineIndex\":").append(m.lineIndex())
              .append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    /** Serialize a {@code BoardContent} to its JSON object form (no enclosing braces wrapping). */
    public static String boardToJson(com.example.webboard.content.registry.BoardContent b) {
        StringBuilder sb = new StringBuilder("{\"name\":").append(quote(b.name()))
                .append(",\"displayName\":").append(quote(b.displayName()))
                .append(",\"effectiveName\":").append(quote(b.effectiveName()))
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
                .append(",\"tags\":[");
        boolean firstTag = true;
        for (String tag : b.tags()) {
            if (!firstTag) sb.append(',');
            sb.append(quote(tag));
            firstTag = false;
        }
        sb.append("],\"itemIds\":[");
        boolean firstItem = true;
        for (String item : b.itemIds()) {
            if (!firstItem) sb.append(',');
            sb.append(quote(item));
            firstItem = false;
        }
        sb.append("]}");
        return sb.toString();
    }
}
