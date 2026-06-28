package com.example.webboard.content.network;

import com.example.webboard.content.network.NetworkDefinition.NetworkMember;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NetworkStorage — JSON-file persistence for {@link NetworkDefinition} entries.
 *
 * <p>Stored in {@code config/webboard-networks.json}. Unlike {@code BoardDatabase} which
 * debounces writes, network changes are infrequent (user-driven CRUD via the dashboard) so
 * we write immediately with an atomic temp-file move.
 *
 * <p>Thread model: CRUD calls come from HTTP threads (Javalin). All public methods are
 * synchronized on the instance to keep the in-memory map and file writes consistent.
 */
public final class NetworkStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger(NetworkStorage.class);

    private static final NetworkStorage INSTANCE = new NetworkStorage();

    public static NetworkStorage get() {
        return INSTANCE;
    }

    private static final String DB_PATH = "config/webboard-networks.json";

    private final ConcurrentHashMap<String, NetworkDefinition> networks = new ConcurrentHashMap<>();
    private final AtomicInteger idCounter = new AtomicInteger(0);
    private volatile boolean initialized = false;

    private NetworkStorage() {}

    public synchronized void init() {
        if (initialized) return;
        try {
            Path dbPath = Path.of(DB_PATH);
            if (Files.exists(dbPath)) {
                loadFromFile(dbPath);
            }
            Files.createDirectories(dbPath.getParent());
            initialized = true;
            LOGGER.info("[web_board] network storage initialized at {} ({} networks loaded)",
                    DB_PATH, networks.size());
        } catch (Exception e) {
            LOGGER.error("[web_board] Failed to initialize network storage: {}", e.toString(), e);
            initialized = false;
        }
    }

    public synchronized void close() {
        initialized = false;
    }

    public boolean isInitialized() {
        return initialized;
    }

    /** Generate a unique network id. */
    private String nextId() {
        return "net-" + idCounter.incrementAndGet();
    }

    /** Create a new network. Returns the created definition (with generated id). */
    public synchronized NetworkDefinition create(String name, List<NetworkMember> members) {
        if (!initialized) return null;
        String id = nextId();
        NetworkDefinition net = new NetworkDefinition(id, name, List.copyOf(members));
        networks.put(id, net);
        save();
        return net;
    }

    /** Update an existing network's name and members. Returns null if not found. */
    public synchronized NetworkDefinition update(String id, String name, List<NetworkMember> members) {
        if (!initialized || !networks.containsKey(id)) return null;
        NetworkDefinition net = new NetworkDefinition(id, name, List.copyOf(members));
        networks.put(id, net);
        save();
        return net;
    }

    /** Delete a network by id. Returns true if deleted, false if not found. */
    public synchronized boolean delete(String id) {
        if (!initialized) return false;
        NetworkDefinition removed = networks.remove(id);
        if (removed == null) return false;
        save();
        return true;
    }

    /** Get a network by id. */
    public NetworkDefinition get(String id) {
        return networks.get(id);
    }

    /** Snapshot of all networks. */
    public List<NetworkDefinition> all() {
        return new ArrayList<>(networks.values());
    }

    public int size() {
        return networks.size();
    }

    // ---------- persistence ----------

    private void save() {
        Path dbPath = Path.of(DB_PATH);
        Path tmpPath = dbPath.resolveSibling(dbPath.getFileName() + ".tmp");
        try {
            String json = serializeAll();
            Files.writeString(tmpPath, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            Files.move(tmpPath, dbPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.error("[web_board] Failed to save networks to {}: {}", DB_PATH, e.toString());
        }
    }

    private String serializeAll() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"networks\":[");
        boolean first = true;
        for (NetworkDefinition net : networks.values()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":").append(quote(net.id()))
              .append(",\"name\":").append(quote(net.name()))
              .append(",\"members\":[");
            boolean firstM = true;
            for (NetworkMember m : net.members()) {
                if (!firstM) sb.append(',');
                firstM = false;
                sb.append("{\"boardName\":").append(quote(m.boardName()))
                  .append(",\"role\":").append(quote(m.role()))
                  .append(",\"label\":").append(quote(m.label()))
                  .append(",\"lineIndex\":").append(m.lineIndex())
                  .append('}');
            }
            sb.append("]}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private void loadFromFile(Path dbPath) {
        try {
            String json = Files.readString(dbPath, StandardCharsets.UTF_8);
            parse(json);
        } catch (IOException e) {
            LOGGER.warn("[web_board] Failed to read network file {}: {}", DB_PATH, e.toString());
        } catch (RuntimeException e) {
            LOGGER.warn("[web_board] Network file {} is corrupt, starting fresh: {}", DB_PATH, e.toString());
        }
    }

    /**
     * Minimal recursive-descent parser for the network JSON file format.
     * Expected shape: {"networks":[{"id":"...","name":"...","members":[{...},...]}]}
     */
    private void parse(String s) {
        networks.clear();
        int i = 0;
        i = skipWs(s, i);
        if (i >= s.length() || s.charAt(i) != '{') return;
        i++;
        i = skipWs(s, i);
        if (i < s.length() && s.charAt(i) == '}') return;

        while (i < s.length()) {
            // Parse key
            var keyResult = parseString(s, skipWs(s, i));
            if (keyResult == null) break;
            String key = keyResult[0];
            i = keyResult[1];
            i = skipWs(s, i);
            if (i >= s.length() || s.charAt(i) != ':') break;
            i++;
            i = skipWs(s, i);
            if (i >= s.length()) break;

            if ("networks".equals(key) && s.charAt(i) == '[') {
                i++;
                i = skipWs(s, i);
                if (i < s.length() && s.charAt(i) == ']') { i++; break; }
                while (i < s.length()) {
                    if (s.charAt(i) != '{') break;
                    var netResult = parseNetwork(s, i);
                    if (netResult == null) break;
                    NetworkDefinition net = netResult[0];
                    i = netResult[1];
                    if (net != null) {
                        networks.put(net.id(), net);
                        // Update id counter to avoid collisions
                        try {
                            int num = Integer.parseInt(net.id().replace("net-", ""));
                            if (num > idCounter.get()) idCounter.set(num);
                        } catch (NumberFormatException ignored) {}
                    }
                    i = skipWs(s, i);
                    if (i < s.length() && s.charAt(i) == ',') { i++; i = skipWs(s, i); continue; }
                    break;
                }
                if (i < s.length() && s.charAt(i) == ']') i++;
            } else {
                // Skip unknown value
                i = skipValue(s, i);
            }

            i = skipWs(s, i);
            if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
            break;
        }
    }

    private Object[] parseNetwork(String s, int i) {
        if (s.charAt(i) != '{') return null;
        i++;
        i = skipWs(s, i);
        String id = null, name = null;
        List<NetworkMember> members = new ArrayList<>();
        if (i < s.length() && s.charAt(i) == '}') { i++; return new Object[]{null, i}; }

        while (i < s.length()) {
            var keyResult = parseString(s, skipWs(s, i));
            if (keyResult == null) break;
            String key = keyResult[0];
            i = keyResult[1];
            i = skipWs(s, i);
            if (i >= s.length() || s.charAt(i) != ':') break;
            i++;
            i = skipWs(s, i);

            switch (key) {
                case "id" -> { var r = parseString(s, i); if (r != null) { id = r[0]; i = r[1]; } }
                case "name" -> { var r = parseString(s, i); if (r != null) { name = r[0]; i = r[1]; } }
                case "members" -> {
                    if (i < s.length() && s.charAt(i) == '[') {
                        i++;
                        i = skipWs(s, i);
                        if (i < s.length() && s.charAt(i) != ']') {
                            while (i < s.length()) {
                                if (s.charAt(i) != '{') break;
                                var mResult = parseMember(s, i);
                                if (mResult == null) break;
                                NetworkMember m = mResult[0];
                                i = mResult[1];
                                if (m != null) members.add(m);
                                i = skipWs(s, i);
                                if (i < s.length() && s.charAt(i) == ',') { i++; i = skipWs(s, i); continue; }
                                break;
                            }
                        }
                        if (i < s.length() && s.charAt(i) == ']') i++;
                    }
                }
                default -> i = skipValue(s, i);
            }
            i = skipWs(s, i);
            if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
            break;
        }
        if (i < s.length() && s.charAt(i) == '}') i++;
        if (id == null) return new Object[]{null, i};
        return new Object[]{new NetworkDefinition(id, name != null ? name : "", members), i};
    }

    private Object[] parseMember(String s, int i) {
        if (s.charAt(i) != '{') return null;
        i++;
        i = skipWs(s, i);
        String boardName = null, role = "producer", label = null;
        int lineIndex = 0;
        if (i < s.length() && s.charAt(i) == '}') { i++; return new Object[]{null, i}; }

        while (i < s.length()) {
            var keyResult = parseString(s, skipWs(s, i));
            if (keyResult == null) break;
            String key = keyResult[0];
            i = keyResult[1];
            i = skipWs(s, i);
            if (i >= s.length() || s.charAt(i) != ':') break;
            i++;
            i = skipWs(s, i);

            switch (key) {
                case "boardName" -> { var r = parseString(s, i); if (r != null) { boardName = r[0]; i = r[1]; } }
                case "role" -> { var r = parseString(s, i); if (r != null) { role = r[0]; i = r[1]; } }
                case "label" -> { var r = parseString(s, i); if (r != null) { label = r[0]; i = r[1]; } }
                case "lineIndex" -> { var r = parseLong(s, i); if (r != null) { lineIndex = (int) r[0]; i = r[1]; } }
                default -> i = skipValue(s, i);
            }
            i = skipWs(s, i);
            if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
            break;
        }
        if (i < s.length() && s.charAt(i) == '}') i++;
        if (boardName == null) return new Object[]{null, i};
        return new Object[]{new NetworkMember(boardName, role, label, lineIndex), i};
    }

    // --- minimal JSON helpers (same pattern as BoardDatabase.JsonFileParser) ---

    private static int skipWs(String s, int i) {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
            else break;
        }
        return i;
    }

    private static Object[] parseString(String s, int i) {
        i = skipWs(s, i);
        if (i >= s.length() || s.charAt(i) != '"') return null;
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return new Object[]{sb.toString(), i};
            if (c == '\\' && i < s.length()) {
                char esc = s.charAt(i++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
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
        return null;
    }

    private static Object[] parseLong(String s, int i) {
        i = skipWs(s, i);
        int start = i;
        if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        if (start == i) return null;
        return new Object[]{Long.parseLong(s.substring(start, i)), i};
    }

    private static int skipValue(String s, int i) {
        i = skipWs(s, i);
        if (i >= s.length()) return i;
        char c = s.charAt(i);
        if (c == '"') { var r = parseString(s, i); return r != null ? r[1] : i + 1; }
        if (c == '{' || c == '[') {
            char open = c, close = (c == '{') ? '}' : ']';
            int depth = 0;
            while (i < s.length()) {
                char ch = s.charAt(i);
                if (ch == '"') { var r = parseString(s, i); if (r != null) { i = r[1]; continue; } }
                if (ch == open) depth++;
                else if (ch == close) { depth--; if (depth == 0) { i++; return i; } }
                i++;
            }
            return i;
        }
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch == ',' || ch == '}' || ch == ']' || ch == ' ' || ch == '\n' || ch == '\t' || ch == '\r') break;
            i++;
        }
        return i;
    }

    private static String quote(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
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
