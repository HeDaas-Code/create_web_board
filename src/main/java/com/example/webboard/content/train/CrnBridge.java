package com.example.webboard.content.train;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.neoforged.fml.ModList;

/**
 * CrnBridge — reflection-based soft-dependency bridge to Create Railways Navigator (CRN).
 *
 * <p><b>Detection</b>: uses {@link ModList#get().isLoaded(String)} instead of {@code Class.forName}
 * — this is the standard NeoForge way to check whether a mod is loaded, and it doesn't depend on
 * knowing CRN's internal class layout (which shifts between beta versions).
 *
 * <p><b>Data sync</b>: when CRN is present, {@link #syncMetadata()} reads CRN's
 * {@code GlobalSettings} singleton via reflection and caches the categories / lines / station tags
 * as our own record types. The dashboard serves these read-only — the web UI no longer has CRUD
 * endpoints for these entities (per v0.7.1 field feedback: "默认同步CRN的数据，不再在网页上更改").
 *
 * <p><b>CRN API surface</b> (verified from CRN beta-0.9.0 source at
 * {@code de.mrjulsen.crn}):
 * <ul>
 *   <li>{@code GlobalSettings.getInstance()} — server-side singleton</li>
 *   <li>{@code GlobalSettings.getAllStationTags()} — {@code List<StationTag>} (public method)</li>
 *   <li>{@code trainCategories} / {@code trainLines} — private {@code Map<UUID, ...>} fields
 *       (no public getAll accessor in some versions; we fall back to field access)</li>
 *   <li>{@code TrainCategory.getId() / getCategoryName() / getColor().getAsARGB()}</li>
 *   <li>{@code TrainLine.getId() / getLineName() / getColor().getAsARGB()}</li>
 *   <li>{@code StationTag.getId() / getTagName().get()}</li>
 * </ul>
 *
 * <p><b>Thread safety</b>: {@link #syncMetadata()} is called from the game thread (via
 * {@link TrainPoller}). The cached lists use {@code volatile} references so HTTP-thread reads
 * are safe without locks.
 */
public final class CrnBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger(CrnBridge.class);

    /** CRN's main mod id; matches the {@code mods.toml} declaration. */
    public static final String CRN_MOD_ID = "createrailwaysnavigator";

    /** CRN class names — used for reflection. Package confirmed from CRN beta-0.9.0 source. */
    private static final String GS_CLASS = "de.mrjulsen.crn.data.storage.GlobalSettings";

    private static volatile Boolean cachedPresence = null;
    private volatile List<TrainCategory> cachedCategories = List.of();
    private volatile List<TrainLine> cachedLines = List.of();
    private volatile List<StationTag> cachedStationTags = List.of();

    public static CrnBridge get() {
        return INSTANCE;
    }

    private static final CrnBridge INSTANCE = new CrnBridge();

    public CrnBridge() {}

    /** True if CRN is loaded on the current mod list. Cached after first check. */
    public boolean isPresent() {
        if (cachedPresence != null) return cachedPresence;
        try {
            cachedPresence = ModList.get().isLoaded(CRN_MOD_ID);
        } catch (Throwable t) {
            cachedPresence = Boolean.FALSE;
        }
        return cachedPresence;
    }

    /**
     * Read categories / lines / station tags from CRN's {@code GlobalSettings} via reflection
     * and cache them as our own record types. Called from {@link TrainPoller} on the graph poll
     * cycle (every 10 s). No-op when CRN is absent.
     */
    public void syncMetadata() {
        if (!isPresent()) return;
        try {
            Class<?> cls = Class.forName(GS_CLASS);
            Method getInstance = cls.getMethod("getInstance");
            Object gs = getInstance.invoke(null);
            if (gs == null) return;

            cachedCategories = readCategories(cls, gs);
            cachedLines = readLines(cls, gs);
            cachedStationTags = readStationTags(cls, gs);
            LOGGER.debug("[web_board] CRN sync: {} categories, {} lines, {} station tags",
                    cachedCategories.size(), cachedLines.size(), cachedStationTags.size());
        } catch (Throwable t) {
            LOGGER.warn("[web_board] CRN metadata sync failed: {}", t.toString());
        }
    }

    // ---------- accessors (HTTP-thread safe, read volatile) ----------

    public List<TrainCategory> categories() {
        return cachedCategories;
    }

    public List<TrainLine> lines() {
        return cachedLines;
    }

    public List<StationTag> stationTags() {
        return cachedStationTags;
    }

    public int lineCount() {
        return cachedLines.size();
    }

    /** Status string for the {@code /api/trains/health} endpoint. */
    public String status() {
        return isPresent() ? "detected" : "absent";
    }

    // ---------- reflection helpers ----------

    @SuppressWarnings("unchecked")
    private List<TrainCategory> readCategories(Class<?> gsClass, Object gs) {
        Collection<?> raw = readCollection(gsClass, gs, "getAllTrainCategories", "trainCategories");
        if (raw == null) return List.of();
        List<TrainCategory> result = new ArrayList<>(raw.size());
        for (Object crnCat : raw) {
            try {
                String id = invokeString(crnCat, "getId");
                String name = invokeString(crnCat, "getCategoryName");
                int color = readColor(crnCat);
                result.add(new TrainCategory(
                        id != null ? id : "",
                        name != null ? name : "",
                        color,
                        TrainCategory.OTHER));
            } catch (Throwable t) {
                LOGGER.debug("[web_board] CRN category read failed: {}", t.toString());
            }
        }
        return List.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private List<TrainLine> readLines(Class<?> gsClass, Object gs) {
        Collection<?> raw = readCollection(gsClass, gs, "getAllTrainLines", "trainLines");
        if (raw == null) return List.of();
        List<TrainLine> result = new ArrayList<>(raw.size());
        for (Object crnLine : raw) {
            try {
                String id = invokeString(crnLine, "getId");
                String name = invokeString(crnLine, "getLineName");
                int color = readColor(crnLine);
                result.add(new TrainLine(
                        id != null ? id : "",
                        name != null ? name : "",
                        "",     // CRN lines don't have a categoryId
                        color,
                        List.of()));  // CRN lines don't carry station list directly
            } catch (Throwable t) {
                LOGGER.debug("[web_board] CRN line read failed: {}", t.toString());
            }
        }
        return List.copyOf(result);
    }

    private List<StationTag> readStationTags(Class<?> gsClass, Object gs) {
        Collection<?> raw = readCollection(gsClass, gs, "getAllStationTags", "stationTags");
        if (raw == null) return List.of();
        List<StationTag> result = new ArrayList<>(raw.size());
        for (Object crnTag : raw) {
            try {
                String id = invokeString(crnTag, "getId");
                String name = readTagName(crnTag);
                result.add(new StationTag(
                        id != null ? id : "",
                        name != null ? name : "",
                        "",   // CRN tags don't have a "type" field
                        0));  // CRN tags don't have a color
            } catch (Throwable t) {
                LOGGER.debug("[web_board] CRN station tag read failed: {}", t.toString());
            }
        }
        return List.copyOf(result);
    }

    /**
     * Try a public {@code getAllXxx()} method first; fall back to the private field.
     * CRN's {@code getAllStationTags()} is public, but {@code getAllTrainCategories()} /
     * {@code getAllTrainLines()} may not exist in all versions — the private map fields are the
     * reliable fallback.
     */
    private Collection<?> readCollection(Class<?> gsClass, Object gs,
                                         String methodName, String fieldName) {
        try {
            Method m = gsClass.getMethod(methodName);
            Object result = m.invoke(gs);
            if (result instanceof Collection<?> col) return col;
        } catch (NoSuchMethodException ignored) {
            // Fall through to field access
        } catch (Throwable t) {
            LOGGER.debug("[web_board] CRN {}() failed: {}", methodName, t.toString());
        }
        try {
            Field f = gsClass.getDeclaredField(fieldName);
            f.setAccessible(true);
            Object val = f.get(gs);
            if (val instanceof Map<?,?> map) return map.values();
            if (val instanceof Collection<?> col) return col;
        } catch (Throwable t) {
            LOGGER.debug("[web_board] CRN field {} access failed: {}", fieldName, t.toString());
        }
        return null;
    }

    /** Invoke a no-arg method returning String; null on any error. */
    private String invokeString(Object obj, String methodName) {
        try {
            Object result = obj.getClass().getMethod(methodName).invoke(obj);
            return result != null ? result.toString() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Read {@code getColor().getAsARGB()} as an int; 0 on any error. */
    private int readColor(Object obj) {
        try {
            Object dlColor = obj.getClass().getMethod("getColor").invoke(obj);
            if (dlColor == null) return 0;
            Object argb = dlColor.getClass().getMethod("getAsARGB").invoke(dlColor);
            return argb instanceof Number n ? n.intValue() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Read {@code getTagName().get()} as a String; null on any error. */
    private String readTagName(Object stationTag) {
        try {
            Object tagName = stationTag.getClass().getMethod("getTagName").invoke(stationTag);
            if (tagName == null) return null;
            Object name = tagName.getClass().getMethod("get").invoke(tagName);
            return name != null ? name.toString() : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
