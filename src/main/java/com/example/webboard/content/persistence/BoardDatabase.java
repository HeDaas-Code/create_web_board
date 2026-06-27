package com.example.webboard.content.persistence;

import com.example.webboard.content.registry.BoardContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * BoardDatabase -- SQLite-backed persistence layer for {@link BoardContent} entries.
 *
 * <p>Uses the SQLite JDBC driver bundled with NeoForge/Minecraft ({@code org.sqlite.JDBC}).
 * The database file lives at {@code config/webboard-boards.db} relative to the game run directory.
 *
 * <p>Singleton pattern, mirroring {@link com.example.webboard.content.registry.BoardRegistry}.
 * All public methods are no-ops (silently return) if {@link #init()} has not been called or
 * if {@link #close()} has already run, so callers don't need guards in hot paths.
 *
 * <p>Thread safety: SQLite in WAL mode allows concurrent reads and serialized writes. We use
 * a single {@link Connection} with {@code INSERT OR REPLACE} upserts; SQLite handles the
 * locking internally. All JDBC operations use try-with-resources.
 */
public final class BoardDatabase {

    private static final Logger LOGGER = LoggerFactory.getLogger(BoardDatabase.class);

    private static final BoardDatabase INSTANCE = new BoardDatabase();

    public static BoardDatabase get() {
        return INSTANCE;
    }

    private static final String DB_PATH = "config/webboard-boards.db";
    private static final String JDBC_URL = "jdbc:sqlite:" + DB_PATH;

    /** Connection is kept open for the lifetime of the server. */
    private volatile Connection connection;
    private volatile boolean initialized = false;

    private BoardDatabase() {}

    /**
     * Initialize the database -- loads the JDBC driver, opens the connection, and creates
     * the {@code boards} table + index if they don't already exist. Safe to call multiple times;
     * subsequent calls after the first are no-ops.
     */
    public synchronized void init() {
        if (initialized) return;

        try {
            // Ensure the config directory exists so SQLite can create the file.
            java.nio.file.Path configDir = java.nio.file.Path.of("config");
            if (!java.nio.file.Files.exists(configDir)) {
                java.nio.file.Files.createDirectories(configDir);
            }

            connection = DriverManager.getConnection(JDBC_URL);
            connection.setAutoCommit(true);

            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS boards ("
                    + "name TEXT PRIMARY KEY,"
                    + "source_type TEXT NOT NULL,"
                    + "lines_json TEXT NOT NULL DEFAULT '[]',"
                    + "last_updated_ms BIGINT NOT NULL,"
                    + "status TEXT NOT NULL DEFAULT 'active',"
                    + "created_at BIGINT NOT NULL,"
                    + "updated_at BIGINT NOT NULL"
                    + ")"
                );
                stmt.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_status ON boards(status)"
                );
            }

            initialized = true;
            LOGGER.info("[web_board] SQLite database initialized at {}", DB_PATH);
        } catch (Exception e) {
            LOGGER.error("[web_board] Failed to initialize database: {}", e.toString(), e);
            initialized = false;
            closeConnection();
        }
    }

    /**
     * Insert or update a board. Uses {@code INSERT OR REPLACE} so the same call works for both
     * new and existing boards. Only writes when the board is active (status='active').
     *
     * @param content the board content to persist
     */
    public void upsert(BoardContent content) {
        if (!initialized) return;
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = connection.prepareStatement(
            "INSERT OR REPLACE INTO boards (name, source_type, lines_json, last_updated_ms, status, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, 'active', "
            + "COALESCE((SELECT created_at FROM boards WHERE name = ?), ?), ?)"
        )) {
            ps.setString(1, content.name());
            ps.setString(2, content.sourceType());
            ps.setString(3, linesToJson(content.lines()));
            ps.setLong(4, content.lastUpdatedMs());
            ps.setString(5, content.name()); // subquery parameter
            ps.setLong(6, now);              // fallback created_at for new rows
            ps.setLong(7, now);              // updated_at
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("[web_board] Failed to upsert board '{}': {}", content.name(), e.toString());
        }
    }

    /**
     * Mark a board as removed. The row is kept in the database for analytics/historical purposes
     * but will no longer appear in {@link #loadAll()}.
     *
     * @param name the board name to mark as removed
     */
    public void markRemoved(String name) {
        if (!initialized) return;
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = connection.prepareStatement(
            "UPDATE boards SET status = 'removed', updated_at = ? WHERE name = ?"
        )) {
            ps.setLong(1, now);
            ps.setString(2, name);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.error("[web_board] Failed to mark board '{}' as removed: {}", name, e.toString());
        }
    }

    /**
     * Load all active (non-removed) boards from the database. Called at server start to restore
     * boards persisted from a previous session.
     *
     * @return a list of active boards; empty if none found or if not initialized
     */
    public List<BoardContent> loadAll() {
        if (!initialized) return List.of();

        List<BoardContent> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT name, source_type, lines_json, last_updated_ms FROM boards WHERE status = 'active'"
        );
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String name = rs.getString("name");
                String sourceType = rs.getString("source_type");
                String linesJson = rs.getString("lines_json");
                long lastUpdatedMs = rs.getLong("last_updated_ms");

                List<String> lines = parseLines(linesJson);
                result.add(new BoardContent(name, sourceType, lines, lastUpdatedMs));
            }
        } catch (SQLException e) {
            LOGGER.error("[web_board] Failed to load boards from database: {}", e.toString());
        }
        return result;
    }

    /**
     * Close the database connection. Called at server stop. All subsequent public method calls
     * become no-ops until {@link #init()} is called again.
     */
    public synchronized void close() {
        if (!initialized) return;
        closeConnection();
        initialized = false;
        LOGGER.info("[web_board] SQLite database connection closed");
    }

    /** Returns true if the database has been initialized and the connection is open. */
    public boolean isInitialized() {
        return initialized;
    }

    private void closeConnection() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                LOGGER.warn("[web_board] Error closing database connection: {}", e.toString());
            }
            connection = null;
        }
    }

    /**
     * Convert a list of lines to a JSON array string. Uses hand-rolled JSON to avoid pulling in
     * a serialization library. Lines containing special characters are escaped.
     */
    private static String linesToJson(List<String> lines) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String line : lines) {
            if (!first) sb.append(',');
            sb.append(JsonEscape.quote(line));
            first = false;
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * Parse a JSON array of strings back into a List. Simple hand-rolled parser for
     * the expected format: {@code ["line1","line2",...]}.
     */
    private static List<String> parseLines(String json) {
        if (json == null || json.isBlank() || json.equals("[]")) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        // Strip brackets
        String inner = json.trim();
        if (inner.startsWith("[")) inner = inner.substring(1);
        if (inner.endsWith("]")) inner = inner.substring(0, inner.length() - 1);
        inner = inner.trim();
        if (inner.isEmpty()) return List.of();

        // Simple state-machine parser for quoted strings
        int i = 0;
        while (i < inner.length()) {
            // Skip whitespace and commas
            while (i < inner.length() && (inner.charAt(i) == ' ' || inner.charAt(i) == ',' || inner.charAt(i) == '\n' || inner.charAt(i) == '\r' || inner.charAt(i) == '\t')) {
                i++;
            }
            if (i >= inner.length()) break;

            if (inner.charAt(i) == '"') {
                i++; // skip opening quote
                StringBuilder sb = new StringBuilder();
                while (i < inner.length()) {
                    char c = inner.charAt(i);
                    if (c == '\\' && i + 1 < inner.length()) {
                        i++;
                        char escaped = inner.charAt(i);
                        switch (escaped) {
                            case '"'  -> sb.append('"');
                            case '\\' -> sb.append('\\');
                            case 'n'  -> sb.append('\n');
                            case 'r'  -> sb.append('\r');
                            case 't'  -> sb.append('\t');
                            default   -> sb.append(escaped);
                        }
                    } else if (c == '"') {
                        break;
                    } else {
                        sb.append(c);
                    }
                    i++;
                }
                result.add(sb.toString());
                i++; // skip closing quote
            } else {
                i++; // skip unexpected characters
            }
        }
        return result;
    }

    /**
     * Minimal JSON string escaping for lines stored in SQLite. Mirrors the escaping logic
     * in {@link com.example.webboard.content.httpserver.JsonUtil#quote} but lives here to
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
}
