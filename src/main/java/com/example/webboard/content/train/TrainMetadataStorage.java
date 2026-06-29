package com.example.webboard.content.train;

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
 * TrainMetadataStorage — JSON-file persistence for the train dashboard's user configuration.
 *
 * <p>Stores four collections in one file ({@code config/webboard-trains.json}):
 * <ul>
 *   <li>{@link TrainCategory categories} — freight / passenger / mixed groupings</li>
 *   <li>{@link TrainLine lines} — ordered station sequences</li>
 *   <li>{@link StationTag station tags} — free-form station labels</li>
 *   <li>{@link TrainMetadata per-train metadata} — category/line/notes per train</li>
 * </ul>
 *
 * <p>Design mirrors {@code NetworkStorage}: synchronized CRUD, atomic temp-file write,
 * minimal hand-rolled JSON parser (no Jackson — keeps the jar slim). The {@link Path}
 * constructor is for tests; production uses the singleton with the default path.
 *
 * <p>Thread model: all public mutating methods are synchronized. Reads use the
 * ConcurrentHashMap directly (snapshot reads — safe under Javalin's request-per-thread model).
 */
public final class TrainMetadataStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger(TrainMetadataStorage.class);

    private static final TrainMetadataStorage INSTANCE = new TrainMetadataStorage(
            Path.of("config/webboard-trains.json"));

    public static TrainMetadataStorage get() {
        return INSTANCE;
    }

    private final Path dbPath;
    private final ConcurrentHashMap<String, TrainCategory> categories = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TrainLine> lines = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StationTag> stationTags = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TrainMetadata> trainMetadata = new ConcurrentHashMap<>();
    private final AtomicInteger catCounter = new AtomicInteger(0);
    private final AtomicInteger lineCounter = new AtomicInteger(0);
    private final AtomicInteger tagCounter = new AtomicInteger(0);
    private volatile boolean initialized = false;

    /** Constructor — visible for tests; production uses {@link #get()} singleton. */
    public TrainMetadataStorage(Path dbPath) {
        this.dbPath = dbPath;
    }

    public synchronized void init() {
        if (initialized) return;
        try {
            Files.createDirectories(dbPath.getParent());
            if (Files.exists(dbPath)) {
                loadFromFile(dbPath);
            }
            initialized = true;
            LOGGER.info("[web_board] train metadata storage initialized at {} (cats={}, lines={}, tags={}, trains={})",
                    dbPath, categories.size(), lines.size(), stationTags.size(), trainMetadata.size());
        } catch (Exception e) {
            LOGGER.error("[web_board] Failed to initialize train metadata storage: {}", e.toString(), e);
            initialized = false;
        }
    }

    public synchronized void close() {
        initialized = false;
    }

    public boolean isInitialized() {
        return initialized;
    }

    // ---------- categories ----------

    public synchronized TrainCategory createCategory(String name, int color, String freightType) {
        if (!initialized) return null;
        String id = "cat-" + catCounter.incrementAndGet();
        TrainCategory c = TrainCategory.create(id, name, color, freightType);
        categories.put(id, c);
        save();
        return c;
    }

    public synchronized TrainCategory updateCategory(String id, String name, int color, String freightType) {
        if (!initialized || !categories.containsKey(id)) return null;
        TrainCategory c = TrainCategory.create(id, name, color, freightType);
        categories.put(id, c);
        save();
        return c;
    }

    public synchronized boolean deleteCategory(String id) {
        if (!initialized) return false;
        TrainCategory removed = categories.remove(id);
        if (removed == null) return false;
        save();
        return true;
    }

    public TrainCategory getCategory(String id) { return categories.get(id); }

    public List<TrainCategory> allCategories() {
        return new ArrayList<>(categories.values());
    }

    // ---------- lines ----------

    public synchronized TrainLine createLine(String name, String categoryId, int color, List<String> stations) {
        if (!initialized) return null;
        String id = "line-" + lineCounter.incrementAndGet();
        TrainLine l = TrainLine.create(id, name, categoryId, color, stations);
        lines.put(id, l);
        save();
        return l;
    }

    public synchronized TrainLine updateLine(String id, String name, String categoryId, int color, List<String> stations) {
        if (!initialized || !lines.containsKey(id)) return null;
        TrainLine l = TrainLine.create(id, name, categoryId, color, stations);
        lines.put(id, l);
        save();
        return l;
    }

    public synchronized boolean deleteLine(String id) {
        if (!initialized) return false;
        TrainLine removed = lines.remove(id);
        if (removed == null) return false;
        save();
        return true;
    }

    public TrainLine getLine(String id) { return lines.get(id); }

    public List<TrainLine> allLines() {
        return new ArrayList<>(lines.values());
    }

    // ---------- station tags ----------

    public synchronized StationTag createStationTag(String name, String type, int color) {
        if (!initialized) return null;
        String id = "tag-" + tagCounter.incrementAndGet();
        StationTag t = StationTag.create(id, name, type, color);
        stationTags.put(id, t);
        save();
        return t;
    }

    public synchronized StationTag updateStationTag(String id, String name, String type, int color) {
        if (!initialized || !stationTags.containsKey(id)) return null;
        StationTag t = StationTag.create(id, name, type, color);
        stationTags.put(id, t);
        save();
        return t;
    }

    public synchronized boolean deleteStationTag(String id) {
        if (!initialized) return false;
        StationTag removed = stationTags.remove(id);
        if (removed == null) return false;
        save();
        return true;
    }

    public StationTag getStationTag(String id) { return stationTags.get(id); }

    public List<StationTag> allStationTags() {
        return new ArrayList<>(stationTags.values());
    }

    // ---------- train metadata ----------

    public synchronized TrainMetadata upsertMetadata(TrainMetadata m) {
        if (!initialized) return null;
        trainMetadata.put(m.trainId(), m);
        save();
        return m;
    }

    public synchronized boolean deleteMetadata(String trainId) {
        if (!initialized) return false;
        TrainMetadata removed = trainMetadata.remove(trainId);
        if (removed == null) return false;
        save();
        return true;
    }

    public TrainMetadata getMetadata(String trainId) { return trainMetadata.get(trainId); }

    public List<TrainMetadata> allTrainMetadata() {
        return new ArrayList<>(trainMetadata.values());
    }

    /** Test helper: clears all in-memory state. Not used in production. */
    synchronized void clearAllForTests() {
        categories.clear();
        lines.clear();
        stationTags.clear();
        trainMetadata.clear();
        catCounter.set(0);
        lineCounter.set(0);
        tagCounter.set(0);
    }

    // ---------- persistence ----------

    private void save() {
        Path tmpPath = dbPath.resolveSibling(dbPath.getFileName() + ".tmp");
        try {
            String json = serializeAll();
            Files.writeString(tmpPath, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            Files.move(tmpPath, dbPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.error("[web_board] Failed to save train metadata to {}: {}", dbPath, e.toString());
        }
    }

    private String serializeAll() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"categories\":[");
        appendCategories(sb);
        sb.append("],\"lines\":[");
        appendLines(sb);
        sb.append("],\"stationTags\":[");
        appendStationTags(sb);
        sb.append("],\"trainMetadata\":[");
        appendTrainMetadata(sb);
        sb.append("]}");
        return sb.toString();
    }

    private void appendCategories(StringBuilder sb) {
        boolean first = true;
        for (TrainCategory c : categories.values()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":").append(quote(c.id()))
              .append(",\"name\":").append(quote(c.name()))
              .append(",\"color\":").append(c.color())
              .append(",\"freightType\":").append(quote(c.freightType()))
              .append('}');
        }
    }

    private void appendLines(StringBuilder sb) {
        boolean first = true;
        for (TrainLine l : lines.values()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":").append(quote(l.id()))
              .append(",\"name\":").append(quote(l.name()))
              .append(",\"categoryId\":").append(quote(l.categoryId()))
              .append(",\"color\":").append(l.color())
              .append(",\"stationNames\":[");
            boolean firstS = true;
            for (String s : l.stationNames()) {
                if (!firstS) sb.append(',');
                firstS = false;
                sb.append(quote(s));
            }
            sb.append("]}");
        }
    }

    private void appendStationTags(StringBuilder sb) {
        boolean first = true;
        for (StationTag t : stationTags.values()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":").append(quote(t.id()))
              .append(",\"name\":").append(quote(t.name()))
              .append(",\"type\":").append(quote(t.type()))
              .append(",\"color\":").append(t.color())
              .append('}');
        }
    }

    private void appendTrainMetadata(StringBuilder sb) {
        boolean first = true;
        for (TrainMetadata m : trainMetadata.values()) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"trainId\":").append(quote(m.trainId()))
              .append(",\"trainName\":").append(quote(m.trainName()))
              .append(",\"categoryId\":").append(quote(m.categoryId()))
              .append(",\"lineId\":").append(quote(m.lineId()))
              .append(",\"color\":").append(m.color())
              .append(",\"notes\":").append(quote(m.notes()))
              .append(",\"lastUpdatedMs\":").append(m.lastUpdatedMs())
              .append('}');
        }
    }

    private void loadFromFile(Path path) {
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            parse(json);
        } catch (IOException e) {
            LOGGER.warn("[web_board] Failed to read train metadata file {}: {}", path, e.toString());
        } catch (RuntimeException e) {
            LOGGER.warn("[web_board] Train metadata file {} is corrupt, starting fresh: {}", path, e.toString());
            categories.clear(); lines.clear(); stationTags.clear(); trainMetadata.clear();
        }
    }

    /**
     * Minimal recursive-descent parser for the JSON file format. Same shape as
     * {@code NetworkStorage.parse} — string-keyed objects inside arrays inside a top-level object.
     */
    private void parse(String s) {
        categories.clear(); lines.clear(); stationTags.clear(); trainMetadata.clear();
        int i = skipWs(s, 0);
        if (i >= s.length() || s.charAt(i) != '{') return;
        i++;
        i = skipWs(s, i);
        if (i < s.length() && s.charAt(i) == '}') return;

        while (i < s.length()) {
            var keyResult = parseString(s, skipWs(s, i));
            if (keyResult == null) break;
            String key = (String) keyResult[0];
            i = (Integer) keyResult[1];
            i = skipWs(s, i);
            if (i >= s.length() || s.charAt(i) != ':') break;
            i++;
            i = skipWs(s, i);
            if (i >= s.length()) break;

            switch (key) {
                case "categories" -> i = parseCategoryArray(s, i);
                case "lines" -> i = parseLineArray(s, i);
                case "stationTags" -> i = parseStationTagArray(s, i);
                case "trainMetadata" -> i = parseTrainMetadataArray(s, i);
                default -> i = skipValue(s, i);
            }
            i = skipWs(s, i);
            if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
            break;
        }
    }

    private int parseCategoryArray(String s, int i) {
        if (i >= s.length() || s.charAt(i) != '[') return i;
        i++;
        i = skipWs(s, i);
        if (i < s.length() && s.charAt(i) == ']') return i + 1;
        while (i < s.length()) {
            if (s.charAt(i) != '{') break;
            int start = i;
            i = skipValue(s, i);
            String obj = s.substring(start, i);
            String id = JsonUtil.extractStringField(obj, "id");
            String name = JsonUtil.extractStringField(obj, "name");
            String ftype = JsonUtil.extractStringField(obj, "freightType");
            int color = JsonUtil.extractIntField(obj, "color", 0);
            if (id != null) {
                TrainCategory c = TrainCategory.create(id, name == null ? "" : name, color, ftype);
                categories.put(id, c);
                bumpCounter(catCounter, id, "cat-");
            }
            i = skipWs(s, i);
            if (i < s.length() && s.charAt(i) == ',') { i++; i = skipWs(s, i); continue; }
            break;
        }
        if (i < s.length() && s.charAt(i) == ']') i++;
        return i;
    }

    private int parseLineArray(String s, int i) {
        if (i >= s.length() || s.charAt(i) != '[') return i;
        i++;
        i = skipWs(s, i);
        if (i < s.length() && s.charAt(i) == ']') return i + 1;
        while (i < s.length()) {
            if (s.charAt(i) != '{') break;
            int start = i;
            i = skipValue(s, i);
            String obj = s.substring(start, i);
            String id = JsonUtil.extractStringField(obj, "id");
            String name = JsonUtil.extractStringField(obj, "name");
            String categoryId = JsonUtil.extractStringField(obj, "categoryId");
            int color = JsonUtil.extractIntField(obj, "color", 0);
            List<String> stations = JsonUtil.extractStringArrayField(obj, "stationNames");
            if (id != null) {
                TrainLine l = TrainLine.create(id, name == null ? "" : name, categoryId, color, stations);
                lines.put(id, l);
                bumpCounter(lineCounter, id, "line-");
            }
            i = skipWs(s, i);
            if (i < s.length() && s.charAt(i) == ',') { i++; i = skipWs(s, i); continue; }
            break;
        }
        if (i < s.length() && s.charAt(i) == ']') i++;
        return i;
    }

    private int parseStationTagArray(String s, int i) {
        if (i >= s.length() || s.charAt(i) != '[') return i;
        i++;
        i = skipWs(s, i);
        if (i < s.length() && s.charAt(i) == ']') return i + 1;
        while (i < s.length()) {
            if (s.charAt(i) != '{') break;
            int start = i;
            i = skipValue(s, i);
            String obj = s.substring(start, i);
            String id = JsonUtil.extractStringField(obj, "id");
            String name = JsonUtil.extractStringField(obj, "name");
            String type = JsonUtil.extractStringField(obj, "type");
            int color = JsonUtil.extractIntField(obj, "color", 0);
            if (id != null) {
                StationTag t = StationTag.create(id, name == null ? "" : name, type, color);
                stationTags.put(id, t);
                bumpCounter(tagCounter, id, "tag-");
            }
            i = skipWs(s, i);
            if (i < s.length() && s.charAt(i) == ',') { i++; i = skipWs(s, i); continue; }
            break;
        }
        if (i < s.length() && s.charAt(i) == ']') i++;
        return i;
    }

    private int parseTrainMetadataArray(String s, int i) {
        if (i >= s.length() || s.charAt(i) != '[') return i;
        i++;
        i = skipWs(s, i);
        if (i < s.length() && s.charAt(i) == ']') return i + 1;
        while (i < s.length()) {
            if (s.charAt(i) != '{') break;
            int start = i;
            i = skipValue(s, i);
            String obj = s.substring(start, i);
            String trainId = JsonUtil.extractStringField(obj, "trainId");
            String trainName = JsonUtil.extractStringField(obj, "trainName");
            String categoryId = JsonUtil.extractStringField(obj, "categoryId");
            String lineId = JsonUtil.extractStringField(obj, "lineId");
            int color = JsonUtil.extractIntField(obj, "color", TrainMetadata.DEFAULT_COLOR);
            String notes = JsonUtil.extractStringField(obj, "notes");
            long ts = JsonUtil.extractIntField(obj, "lastUpdatedMs", 0);
            if (trainId != null) {
                TrainMetadata m = new TrainMetadata(trainId,
                        trainName == null ? "" : trainName,
                        categoryId, lineId, color,
                        notes == null ? "" : notes, ts);
                trainMetadata.put(trainId, m);
            }
            i = skipWs(s, i);
            if (i < s.length() && s.charAt(i) == ',') { i++; i = skipWs(s, i); continue; }
            break;
        }
        if (i < s.length() && s.charAt(i) == ']') i++;
        return i;
    }

    /** Set the counter to max(current, parsed id number) so new ids stay unique. */
    private static void bumpCounter(AtomicInteger counter, String id, String prefix) {
        if (id != null && id.startsWith(prefix)) {
            try {
                int num = Integer.parseInt(id.substring(prefix.length()));
                if (num > counter.get()) counter.set(num);
            } catch (NumberFormatException ignored) {}
        }
    }

    // --- minimal JSON helpers (same pattern as NetworkStorage) ---

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

    private static int skipValue(String s, int i) {
        i = skipWs(s, i);
        if (i >= s.length()) return i;
        char c = s.charAt(i);
        if (c == '"') { var r = parseString(s, i); return r != null ? (Integer) r[1] : i + 1; }
        if (c == '{' || c == '[') {
            char open = c, close = (c == '{') ? '}' : ']';
            int depth = 0;
            while (i < s.length()) {
                char ch = s.charAt(i);
                if (ch == '"') { var r = parseString(s, i); if (r != null) { i = (Integer) r[1]; continue; } }
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

    // Reuse the public JsonUtil helpers for object-field extraction inside parse loops.
    private static final class JsonUtil {
        static String extractStringField(String json, String field) {
            return com.example.webboard.content.httpserver.JsonUtil.extractStringField(json, field);
        }
        static int extractIntField(String json, String field, int def) {
            return com.example.webboard.content.httpserver.JsonUtil.extractIntField(json, field, def);
        }
        static List<String> extractStringArrayField(String json, String field) {
            return com.example.webboard.content.httpserver.JsonUtil.extractStringArrayField(json, field);
        }
    }
}
