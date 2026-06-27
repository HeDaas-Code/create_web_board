package com.example.webboard.content.items;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * IconPackStorage — server-side cache of fully-rendered item icons (32×32 PNG) + localized
 * names uploaded by a Minecraft client. Lives at {@code config/webboard-icons/}.
 *
 * <p><b>Why this exists</b>: the dashboard's product picker shows JEI-style item icons —
 * i.e. the actual <em>rendered</em> item (with tint, layered textures, 3D-model transform),
 * not the raw texture PNG. Only the MC client can render items (via {@code ItemRenderer}),
 * so a client packs the rendered PNG + localized name and POSTs it here. The server then
 * serves these to the dashboard browser; the previous PNG-texture fallback
 * ({@link ItemIconService}) remains as a safety net when no client has uploaded yet.
 *
 * <p><b>Layout</b>:
 * <pre>
 *   config/webboard-icons/
 *     names.json              # {"minecraft:iron_ingot":"铁锭", ...}
 *     minecraft_iron_ingot.png
 *     create_cogwheel.png
 *     ...
 * </pre>
 * Filenames are the item id with {@code :} replaced by {@code _} (filesystem-safe).
 *
 * <p><b>Iconpack zip</b>: for dedicated-server setups with no client, the operator runs
 * {@code /webboard export-icons} on a client (or installs the standalone iconpack mod),
 * which produces {@code webboard-icons.zip} containing the same {@code names.json} +
 * {@code *.png} layout. Drop that zip at {@code config/webboard-icons.zip} and the server
 * loads it on next start — no client connection needed.
 *
 * <p><b>Write strategy</b>: writes from the bulk-upload endpoint are buffered into the
 * in-memory map immediately (so dashboard reads see them at once), then flushed to disk
 * by a daemon thread every few seconds (debounced) — same pattern as {@code BoardDatabase}.
 *
 * <p>Singleton. Thread-safe (HTTP handler threads read; upload handler writes).
 */
public final class IconPackStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger(IconPackStorage.class);

    private static final IconPackStorage INSTANCE = new IconPackStorage();

    public static IconPackStorage get() {
        return INSTANCE;
    }

    private static final String DIR_PATH = "config/webboard-icons";
    private static final String ZIP_PATH = "config/webboard-icons.zip";
    private static final String NAMES_FILE = "names.json";
    private static final long FLUSH_INTERVAL_MS = 3_000;

    /** In-memory cache: id -> PNG bytes. */
    private final ConcurrentHashMap<String, byte[]> icons = new ConcurrentHashMap<>();
    /** In-memory cache: id -> localized name (e.g. "铁锭"). */
    private final ConcurrentHashMap<String, String> names = new ConcurrentHashMap<>();

    private volatile boolean dirty = false;
    private volatile boolean initialized = false;
    private Thread flushThread;

    private IconPackStorage() {}

    /** Initialize: load from zip if present, else from the unpacked directory. Starts the flusher. */
    public synchronized void init() {
        if (initialized) return;
        try {
            Path dir = Path.of(DIR_PATH);
            Files.createDirectories(dir);
            Path zip = Path.of(ZIP_PATH);
            if (Files.exists(zip)) {
                loadFromZip(zip);
                LOGGER.info("[web_board] icon pack loaded from zip: {} icons, {} names",
                        icons.size(), names.size());
            } else {
                loadFromDir(dir);
                LOGGER.info("[web_board] icon pack loaded from dir: {} icons, {} names",
                        icons.size(), names.size());
            }
            if (icons.isEmpty()) {
                LOGGER.info("[web_board] no icon pack yet — dashboard will fall back to raw PNG textures. " +
                        "For a dedicated server, install the iconpack mod on a client and run /webboard export-icons, " +
                        "then drop the resulting webboard-icons.zip into config/.");
            }
            flushThread = new Thread(this::flushLoop, "web-board-icon-flusher");
            flushThread.setDaemon(true);
            flushThread.start();
            initialized = true;
        } catch (Exception e) {
            LOGGER.error("[web_board] failed to init icon pack: {}", e.toString(), e);
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    /** Get the cached PNG for an item id, or null if none uploaded yet. */
    public byte[] getIcon(String itemId) {
        return icons.get(itemId);
    }

    /** Get the cached localized name for an item id, or null if none. */
    public String getName(String itemId) {
        return names.get(itemId);
    }

    /** True if a rendered icon is cached for this id. */
    public boolean hasIcon(String itemId) {
        return icons.containsKey(itemId);
    }

    /**
     * Store one icon + its localized name. Called by the bulk-upload endpoint. The write is
     * buffered in-memory immediately; disk flush is debounced. Replaces any existing entry.
     */
    public void store(String itemId, String localizedName, byte[] pngBytes) {
        if (!initialized || itemId == null || pngBytes == null || pngBytes.length == 0) return;
        icons.put(itemId, pngBytes);
        if (localizedName != null && !localizedName.isEmpty()) {
            names.put(itemId, localizedName);
        }
        dirty = true;
    }

    /** Snapshot of all known (id, localized name) pairs. Used by item search to show 中文名. */
    public Map<String, String> allNames() {
        return new LinkedHashMap<>(names);
    }

    /** Number of cached icons. */
    public int size() {
        return icons.size();
    }

    // ---------- internal ----------

    private void flushLoop() {
        while (initialized) {
            try { Thread.sleep(FLUSH_INTERVAL_MS); } catch (InterruptedException e) { break; }
            try {
                flushIfDirty();
            } catch (Exception e) {
                LOGGER.warn("[web_board] icon flush failed: {}", e.toString());
            }
        }
    }

    private synchronized void flushIfDirty() {
        if (!dirty || !initialized) return;
        dirty = false;
        Path dir = Path.of(DIR_PATH);
        try {
            Files.createDirectories(dir);
            // Write each icon (filename-safe: replace ':' with '_').
            for (var e : icons.entrySet()) {
                Path p = dir.resolve(fileNameFor(e.getKey()) + ".png");
                Files.write(p, e.getValue());
            }
            // Write names.json (rewrite whole file — cheap for typical sizes).
            Files.writeString(dir.resolve(NAMES_FILE), serializeNames(names), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("[web_board] icon flush write failed: {}", e.toString());
            dirty = true;
        }
    }

    /** Load icons from an uploaded zip (icons/<id>.png + names.json at zip root). */
    private void loadFromZip(Path zipPath) throws IOException {
        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            var zipEntries = zf.entries();
            while (zipEntries.hasMoreElements()) {
                ZipEntry ze = zipEntries.nextElement();
                if (ze.isDirectory()) continue;
                String name = ze.getName();
                if (NAMES_FILE.equals(name)) {
                    String json = new String(zf.getInputStream(ze).readAllBytes(), StandardCharsets.UTF_8);
                    parseNames(json).forEach(names::put);
                } else if (name.endsWith(".png")) {
                    String id = idFromFileName(name.substring(0, name.length() - 4));
                    if (id == null) continue;
                    byte[] bytes = zf.getInputStream(ze).readAllBytes();
                    icons.put(id, bytes);
                }
            }
        }
    }

    /** Load icons from an unpacked directory ({@code <id>.png} + {@code names.json}). */
    private void loadFromDir(Path dir) throws IOException {
        Path namesFile = dir.resolve(NAMES_FILE);
        if (Files.exists(namesFile)) {
            String json = Files.readString(namesFile, StandardCharsets.UTF_8);
            parseNames(json).forEach(names::put);
        }
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".png")).forEach(p -> {
                String fn = p.getFileName().toString();
                String id = idFromFileName(fn.substring(0, fn.length() - 4));
                if (id == null) return;
                try {
                    icons.put(id, Files.readAllBytes(p));
                } catch (IOException e) {
                    LOGGER.warn("[web_board] failed to read icon {}: {}", p, e.toString());
                }
            });
        }
    }

    /**
     * "minecraft:iron_ingot" -> "minecraft%3Airon_ingot". URL-encodes {@code :} and {@code /}
     * (and {@code %}) so the filename is single-segment and works on Windows too — {@code :}
     * is reserved on NTFS. Round-trips with {@link #idFromFileName}.
     */
    private static String fileNameFor(String itemId) {
        return itemId.replace("%", "%25").replace(":", "%3A").replace("/", "%2F");
    }

    /** "minecraft%3Airon_ingot" -> "minecraft:iron_ingot"; returns null if no %3A marker. */
    private static String idFromFileName(String fileStem) {
        if (!fileStem.contains("%3A") && !fileStem.contains("%3a")) return null;
        return fileStem.replace("%2F", "/").replace("%2f", "/")
                .replace("%3A", ":").replace("%3a", ":")
                .replace("%25", "%");
    }

    /** Serialize names map to a flat JSON object. */
    private static String serializeNames(Map<String, String> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(JsonEscape.quote(e.getKey())).append(':').append(JsonEscape.quote(e.getValue()));
        }
        sb.append('}');
        return sb.toString();
    }

    /** Parse a flat JSON object of {id: name} into a LinkedHashMap (preserves insertion order). */
    private static Map<String, String> parseNames(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return result;
        int i = 0;
        i = skipWs(json, i);
        if (i >= json.length() || json.charAt(i) != '{') return result;
        i++;
        i = skipWs(json, i);
        if (i < json.length() && json.charAt(i) == '}') return result;
        while (i < json.length()) {
            int[] idOut = new int[1];
            String id = parseString(json, i, idOut);
            if (id == null) return result;
            i = skipWs(json, idOut[0]);
            if (i >= json.length() || json.charAt(i) != ':') return result;
            i = skipWs(json, i + 1);
            int[] nameOut = new int[1];
            String name = parseString(json, i, nameOut);
            if (name == null) return result;
            result.put(id, name);
            i = skipWs(json, nameOut[0]);
            if (i < json.length() && json.charAt(i) == ',') { i++; i = skipWs(json, i); continue; }
            break;
        }
        return result;
    }

    private static int skipWs(String s, int i) {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
            else break;
        }
        return i;
    }

    private static String parseString(String s, int i, int[] endOut) {
        if (i >= s.length() || s.charAt(i) != '"') return null;
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') { endOut[0] = i; return sb.toString(); }
            if (c == '\\' && i < s.length()) {
                char esc = s.charAt(i++);
                switch (esc) {
                    case '"'  -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/'  -> sb.append('/');
                    case 'n'  -> sb.append('\n');
                    case 'r'  -> sb.append('\r');
                    case 't'  -> sb.append('\t');
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

    /**
     * Pack every cached icon + names.json into a zip stream. Used by the
     * {@code /webboard export-icons} client command to produce an uploadable pack — the
     * exact same layout {@link #loadFromZip} reads back on the server.
     */
    public static void writeToZip(OutputStream out, Map<String, String> names, Map<String, byte[]> icons) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry(NAMES_FILE));
            zos.write(serializeNames(names).getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            for (var e : icons.entrySet()) {
                zos.putNextEntry(new ZipEntry(fileNameFor(e.getKey()) + ".png"));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
    }

    /** JSON string escaping (mirrors {@code JsonUtil.quote}, kept local to avoid a cross-package dep). */
    private static final class JsonEscape {
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
}
