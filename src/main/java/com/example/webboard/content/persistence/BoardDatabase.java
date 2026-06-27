package com.example.webboard.content.persistence;

import com.example.webboard.content.registry.BoardContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * BoardDatabase -- JSON-file-backed persistence layer for {@link BoardContent} entries.
 *
 * <p><b>Why JSON instead of SQLite?</b> v0.3.0 used SQLite via {@code org.sqlite.JDBC}, but
 * NeoForge 1.21.1 does NOT bundle the SQLite JDBC driver (verified via issue #9 log:
 * "No suitable driver found for jdbc:sqlite:..."). Rather than ship a native SQLite binary
 * as a jarJar dependency (platform-specific, large, fragile), we fall back to a single JSON
 * file. For a localhost dashboard with tens of boards this is plenty fast and has zero
 * external dependencies.
 *
 * <p><b>Storage format</b>: {@code config/webboard-boards.json} containing a JSON object:
 * <pre>
 * {
 *   "boards": {
 *     "Board @ 12,64,-8": {
 *       "sourceType": "create:time_of_day",
 *       "lines": ["06:30"],
 *       "lastUpdatedMs": 1782555000000,
 *       "status": "active"
 *     },
 *     ...
 *   }
 * }
 * </pre>
 * Removed boards are kept with {@code status: "removed"} for future analytics.
 *
 * <p><b>Thread model &amp; performance</b>: writes happen on the game thread (via
 * {@link WebMirror}) and on the WS thread (via {@code WebSocketHub.onChange}). To avoid
 * hammering the disk on every refresh, writes are <em>buffered</em> in memory and flushed
 * to disk by a single-thread daemon executor every 5 seconds (debounced). The flush is
 * atomic: write to {@code *.tmp} then {@link Files#move} with ATOMIC_MOVE. Reads at server
 * start are a single file load.
 *
 * <p>Singleton pattern, mirroring {@link com.example.webboard.content.registry.BoardRegistry}.
 * All public methods are no-ops (silently return) if {@link #init()} has not been called or
 * if {@link #close()} has already run.
 */
public final class BoardDatabase {

    private static final Logger LOGGER = LoggerFactory.getLogger(BoardDatabase.class);

    private static final BoardDatabase INSTANCE = new BoardDatabase();

    public static BoardDatabase get() {
        return INSTANCE;
    }

    private static final String DB_PATH = "config/webboard-boards.json";
    private static final long FLUSH_INTERVAL_SECONDS = 5;

    /** In-memory mirror of the file. Single source of truth between flushes. */
    private final ConcurrentHashMap<String, BoardEntry> entries = new ConcurrentHashMap<>();

    /** True when an entry has been modified since the last flush. */
    private volatile boolean dirty = false;

    private volatile boolean initialized = false;
    private ScheduledExecutorService flushExecutor;

    private BoardDatabase() {}

    /** In-memory representation of a persisted board. */
    private record BoardEntry(String sourceType, List<String> lines, long lastUpdatedMs, String status) {}

    /**
     * Initialize the database -- loads the JSON file (if it exists) and starts the flush
     * scheduler. Safe to call multiple times; subsequent calls after the first are no-ops.
     */
    public synchronized void init() {
        if (initialized) return;

        try {
            Path dbPath = Path.of(DB_PATH);
            if (Files.exists(dbPath)) {
                loadFromFile(dbPath);
            }
            // Ensure parent directory exists for future writes.
            Files.createDirectories(dbPath.getParent());

            flushExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "web-board-db-flusher");
                t.setDaemon(true);
                return t;
            });
            flushExecutor.scheduleWithFixedDelay(this::flushIfDirty,
                    FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);

            initialized = true;
            LOGGER.info("[web_board] JSON database initialized at {} ({} boards loaded)",
                    DB_PATH, entries.size());
        } catch (Exception e) {
            LOGGER.error("[web_board] Failed to initialize database: {}", e.toString(), e);
            initialized = false;
        }
    }

    /**
     * Insert or update a board. The write is buffered in memory; the flush scheduler persists
     * it to disk at most once every 5 seconds. Safe to call from any thread.
     */
    public void upsert(BoardContent content) {
        if (!initialized) return;
        entries.put(content.name(), new BoardEntry(
                content.sourceType(),
                new ArrayList<>(content.lines()),
                content.lastUpdatedMs(),
                "active"));
        dirty = true;
    }

    /**
     * Mark a board as removed. The entry is kept in the JSON file for analytics but flagged
     * with {@code status: "removed"} so {@link #loadAll()} skips it. Safe to call from any thread.
     */
    public void markRemoved(String name) {
        if (!initialized) return;
        BoardEntry existing = entries.get(name);
        if (existing != null) {
            entries.put(name, new BoardEntry(
                    existing.sourceType(),
                    existing.lines(),
                    existing.lastUpdatedMs(),
                    "removed"));
            dirty = true;
        }
    }

    /**
     * Load all active (non-removed) boards. Called at server start to restore boards persisted
     * from a previous session.
     *
     * @return a list of active boards; empty if none found or if not initialized
     */
    public List<BoardContent> loadAll() {
        if (!initialized) return List.of();
        List<BoardContent> result = new ArrayList<>();
        for (var e : entries.entrySet()) {
            BoardEntry be = e.getValue();
            if ("active".equals(be.status())) {
                result.add(new BoardContent(e.getKey(), be.sourceType(),
                        new ArrayList<>(be.lines()), be.lastUpdatedMs()));
            }
        }
        return result;
    }

    /**
     * Close the database: performs one final flush then stops the scheduler. All subsequent
     * public method calls become no-ops until {@link #init()} is called again.
     */
    public synchronized void close() {
        if (!initialized) return;
        // Final flush before shutdown so no buffered writes are lost.
        flushIfDirty();
        if (flushExecutor != null) {
            flushExecutor.shutdown();
            try {
                if (!flushExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    flushExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                flushExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            flushExecutor = null;
        }
        initialized = false;
        LOGGER.info("[web_board] JSON database closed");
    }

    /** Returns true if the database has been initialized. */
    public boolean isInitialized() {
        return initialized;
    }

    // ---------- internal ----------

    /** Flush to disk only if writes have occurred since the last flush. */
    private synchronized void flushIfDirty() {
        if (!dirty || !initialized) return;
        dirty = false;
        Path dbPath = Path.of(DB_PATH);
        Path tmpPath = dbPath.resolveSibling(dbPath.getFileName() + ".tmp");
        try {
            String json = serializeAll();
            Files.writeString(tmpPath, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            Files.move(tmpPath, dbPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.error("[web_board] Failed to flush database to {}: {}", DB_PATH, e.toString());
            dirty = true; // try again next cycle
        }
    }

    /** Serialize the entire entries map to a JSON string. */
    private String serializeAll() {
        StringBuilder sb = new StringBuilder(256 * (entries.size() + 1));
        sb.append("{\"boards\":{");
        boolean first = true;
        for (var e : entries.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            BoardEntry be = e.getValue();
            sb.append(JsonEscape.quote(e.getKey())).append(":{");
            sb.append("\"sourceType\":").append(JsonEscape.quote(be.sourceType())).append(',');
            sb.append("\"lines\":[");
            boolean firstLine = true;
            for (String line : be.lines()) {
                if (!firstLine) sb.append(',');
                firstLine = false;
                sb.append(JsonEscape.quote(line));
            }
            sb.append("],");
            sb.append("\"lastUpdatedMs\":").append(be.lastUpdatedMs()).append(',');
            sb.append("\"status\":").append(JsonEscape.quote(be.status()));
            sb.append('}');
        }
        sb.append("}}");
        return sb.toString();
    }

    /** Load entries from the JSON file into the in-memory map. Tolerant of missing/corrupt files. */
    private void loadFromFile(Path dbPath) {
        try {
            String json = Files.readString(dbPath, StandardCharsets.UTF_8);
            Map<String, BoardEntry> parsed = JsonFileParser.parse(json);
            entries.clear();
            entries.putAll(parsed);
        } catch (IOException e) {
            LOGGER.warn("[web_board] Failed to read database file {}: {}", DB_PATH, e.toString());
        } catch (RuntimeException e) {
            LOGGER.warn("[web_board] Database file {} is corrupt, starting fresh: {}", DB_PATH, e.toString());
        }
    }

    /**
     * Minimal JSON string escaping. Mirrors the escaping logic in
     * {@link com.example.webboard.content.httpserver.JsonUtil#quote} but lives here to
     * avoid a cross-package dependency from persistence to httpserver.
     */
    private static final class JsonEscape {
        private JsonEscape() {}

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
    }

    /**
     * Tiny recursive-descent JSON parser for the file format produced by {@link #serializeAll}.
     * We don't pull in Jackson/Gson to keep the dependency footprint at zero. Only supports
     * the exact shape we write: {@code {"boards": {string: {sourceType, lines, lastUpdatedMs, status}}}}.
     */
    private static final class JsonFileParser {
        private final String s;
        private int i;

        private JsonFileParser(String s) {
            this.s = s;
            this.i = 0;
        }

        static Map<String, BoardEntry> parse(String s) {
            JsonFileParser p = new JsonFileParser(s);
            p.skipWs();
            p.expect('{');
            p.skipWs();
            Map<String, BoardEntry> result = new LinkedHashMap<>();
            // Empty object check
            if (p.peek() == '}') { p.i++; return result; }
            // Expect "boards": {...}
            String key = p.parseString();
            p.skipWs(); p.expect(':'); p.skipWs();
            if (!"boards".equals(key)) {
                // Unknown top-level key — skip its value.
                p.skipValue();
                // For now we only know "boards"; bail if that's all we see.
                p.skipWs();
                if (p.peek() == '}') { p.i++; return result; }
            }
            p.expect('{');
            p.skipWs();
            if (p.peek() == '}') { p.i++; return result; }
            while (true) {
                String name = p.parseString();
                p.skipWs(); p.expect(':'); p.skipWs();
                p.expect('{');
                p.skipWs();
                String sourceType = "unknown";
                List<String> lines = new ArrayList<>();
                long lastUpdatedMs = 0;
                String status = "active";
                if (p.peek() != '}') {
                    while (true) {
                        String field = p.parseString();
                        p.skipWs(); p.expect(':'); p.skipWs();
                        switch (field) {
                            case "sourceType" -> sourceType = p.parseString();
                            case "lines" -> {
                                p.expect('[');
                                p.skipWs();
                                if (p.peek() != ']') {
                                    while (true) {
                                        lines.add(p.parseString());
                                        p.skipWs();
                                        if (p.peek() == ',') { p.i++; p.skipWs(); continue; }
                                        break;
                                    }
                                }
                                p.expect(']');
                            }
                            case "lastUpdatedMs" -> lastUpdatedMs = p.parseLong();
                            case "status" -> status = p.parseString();
                            default -> p.skipValue();
                        }
                        p.skipWs();
                        if (p.peek() == ',') { p.i++; p.skipWs(); continue; }
                        break;
                    }
                }
                p.expect('}');
                result.put(name, new BoardEntry(sourceType, lines, lastUpdatedMs, status));
                p.skipWs();
                if (p.peek() == ',') { p.i++; p.skipWs(); continue; }
                break;
            }
            p.expect('}');
            return result;
        }

        private void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }

        private char peek() {
            if (i >= s.length()) throw new RuntimeException("unexpected EOF");
            return s.charAt(i);
        }

        private void expect(char c) {
            skipWs();
            if (i >= s.length() || s.charAt(i) != c) {
                throw new RuntimeException("expected '" + c + "' at " + i + ", got " +
                        (i < s.length() ? "'" + s.charAt(i) + "'" : "EOF"));
            }
            i++;
        }

        private String parseString() {
            skipWs();
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\' && i < s.length()) {
                    char esc = s.charAt(i++);
                    switch (esc) {
                        case '"'  -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/'  -> sb.append('/');
                        case 'n'  -> sb.append('\n');
                        case 'r'  -> sb.append('\r');
                        case 't'  -> sb.append('\t');
                        case 'u'  -> {
                            if (i + 4 <= s.length()) {
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                                i += 4;
                            }
                        }
                        default -> sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new RuntimeException("unterminated string");
        }

        private long parseLong() {
            skipWs();
            int start = i;
            if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            if (start == i) throw new RuntimeException("expected number at " + i);
            return Long.parseLong(s.substring(start, i));
        }

        private void skipValue() {
            skipWs();
            char c = peek();
            if (c == '"') { parseString(); return; }
            if (c == '{' || c == '[') {
                char open = c, close = (c == '{') ? '}' : ']';
                int depth = 0;
                while (i < s.length()) {
                    char ch = s.charAt(i++);
                    if (ch == '"') { parseString(); continue; }
                    if (ch == open) depth++;
                    else if (ch == close) { depth--; if (depth == 0) return; }
                }
                return;
            }
            // number / true / false / null
            while (i < s.length()) {
                char ch = s.charAt(i);
                if (ch == ',' || ch == '}' || ch == ']' || ch == ' ' || ch == '\n' || ch == '\t' || ch == '\r') break;
                i++;
            }
        }
    }
}
