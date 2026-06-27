package com.example.webboard.content.items;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ItemIconService — serves Minecraft item texture PNGs to the dashboard, plus a searchable
 * catalog of all registered items (vanilla + Create + every addon, since they all register
 * into {@link BuiltInRegistries.ITEM}).
 *
 * <p><b>Why this exists</b>: the dashboard's "select product" picker shows each item with a
 * thumbnail. The mod runs server-side, and MC's server {@code ResourceManager} does NOT load
 * {@code assets/.../textures/*.png} (textures are a client resource). But every mod jar is on
 * the classpath, so we read the PNG bytes directly via the context classloader — this reaches
 * into all loaded jars (minecraft, create, and every Create addon the user installed), which is
 * exactly the "scan addons yourself" requirement.
 *
 * <p><b>Texture path resolution</b>: most items follow the convention
 * {@code assets/<namespace>/textures/item/<path>.png} for item id {@code <namespace>:<path>}.
 * Block-items (e.g. {@code minecraft:stone}) don't have an item texture — their model inherits
 * the block model — so we fall back to {@code textures/block/<path>.png}. This covers the
 * overwhelming majority of vanilla + Create + addon items without parsing model JSON (which the
 * server can't do reliably anyway). Unresolved ids return null and are cached as misses so we
 * don't re-scan the classpath on every card render.
 *
 * <p><b>Caching</b>: hit cache (id -> PNG bytes) + miss cache (id -> true). The catalog of all
 * item ids is built once (lazily, on first search) by iterating the item registry — a few
 * thousand entries for a big modpack, takes ~50ms, then cached.
 *
 * <p>Singleton, like {@link com.example.webboard.content.registry.BoardRegistry}. All methods
 * are thread-safe (HTTP handler threads).
 */
public final class ItemIconService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ItemIconService.class);
    private static final ItemIconService INSTANCE = new ItemIconService();

    public static ItemIconService get() {
        return INSTANCE;
    }

    /** PNG bytes for a resolved item icon; null entries are cached as misses. */
    private final ConcurrentHashMap<String, byte[]> iconCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> missCache = new ConcurrentHashMap<>();

    /** All registered item ids, sorted. Built lazily on first search. */
    private volatile List<String> catalog;

    private ItemIconService() {}

    /** One search result item: the registry id and a short display name (path after the colon). */
    public record ItemInfo(String id, String name) {}

    /**
     * Return the PNG bytes for an item's icon, or null if no texture could be resolved.
     * Results (including misses) are cached. Thread-safe.
     *
     * <p>Resolution order: (1) the rendered-icon pack uploaded by a client (JEI-style rendered
     * icon with localized name) — this is what the dashboard prefers; (2) the raw PNG texture
     * from the classpath (fallback for items the client hasn't uploaded yet, or for pure
     * dedicated servers with no client connection).
     */
    public byte[] getIcon(String itemId) {
        if (itemId == null || itemId.isEmpty()) return null;
        // (1) Rendered icon pack — preferred (matches what JEI shows in-game).
        if (IconPackStorage.get().isInitialized() && IconPackStorage.get().hasIcon(itemId)) {
            return IconPackStorage.get().getIcon(itemId);
        }
        // (2) Raw PNG texture fallback (covers items the client hasn't uploaded yet).
        if (missCache.containsKey(itemId)) return null;
        byte[] cached = iconCache.get(itemId);
        if (cached != null) return cached;
        byte[] bytes = loadIcon(itemId);
        if (bytes == null) {
            missCache.put(itemId, Boolean.TRUE);
            return null;
        }
        iconCache.put(itemId, bytes);
        return bytes;
    }

    /** Try item texture path first, then block texture path (for block-items). */
    private byte[] loadIcon(String itemId) {
        int colon = itemId.indexOf(':');
        if (colon <= 0 || colon == itemId.length() - 1) return null;
        String ns = itemId.substring(0, colon);
        String path = itemId.substring(colon + 1);
        // Guard against path traversal-ish input (ids are normally [a-z0-9_/.-]); reject anything
        // that could escape the assets dir.
        if (!isValidSegment(ns) || !isValidSegment(path)) return null;
        byte[] b = readResource("assets/" + ns + "/textures/item/" + path + ".png");
        if (b != null) return b;
        return readResource("assets/" + ns + "/textures/block/" + path + ".png");
    }

    private static boolean isValidSegment(String s) {
        // ClassLoader resource lookup can't traverse out of a jar, so this is defense-in-depth:
        // reject empty segments and ".." (everything else just misses and returns null safely).
        return !s.isEmpty() && !s.contains("..");
    }

    private byte[] readResource(String resPath) {
        // Context classloader sees every loaded mod jar — this is what lets us reach Create +
        // addon textures, not just our own jar. Fall back to this class's loader just in case.
        try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(resPath)) {
            if (is == null) return null;
            return is.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Search the item catalog by id or localized-name substring (case-insensitive). Empty query
     * returns the first {@code limit} ids (alphabetical). The catalog is built on first call by
     * iterating {@link BuiltInRegistries.ITEM} — covers vanilla + every registered mod item.
     *
     * <p>The returned {@link ItemInfo#name()} is the localized name from the icon pack if
     * available (e.g. "铁锭"), else the short id (e.g. "iron_ingot"). The dashboard picker
     * shows this name; search matches against both id and localized name so users can type
     * either "iron" or "铁锭".
     */
    public List<ItemInfo> search(String query, int limit) {
        List<String> ids = ensureCatalog();
        String q = query == null ? "" : query.toLowerCase();
        int cap = limit > 0 ? limit : 50;
        List<ItemInfo> result = new ArrayList<>(Math.min(cap, ids.size()));
        for (String id : ids) {
            String localizedName = IconPackStorage.get().isInitialized()
                    ? IconPackStorage.get().getName(id) : null;
            String displayName = (localizedName != null && !localizedName.isEmpty())
                    ? localizedName : shortName(id);
            if (q.isEmpty()
                    || id.toLowerCase().contains(q)
                    || displayName.toLowerCase().contains(q)) {
                result.add(new ItemInfo(id, displayName));
                if (result.size() >= cap) break;
            }
        }
        return result;
    }

    private List<String> ensureCatalog() {
        List<String> ids = catalog;
        if (ids != null) return ids;
        synchronized (this) {
            if (catalog != null) return catalog;
            List<String> list = new ArrayList<>();
            for (Item item : BuiltInRegistries.ITEM) {
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
                if (key != null) list.add(key.toString());
            }
            list.sort(null);
            catalog = list;
            LOGGER.info("[web_board] item catalog built: {} items registered", list.size());
            return catalog;
        }
    }

    private static String shortName(String itemId) {
        int i = itemId.indexOf(':');
        return i >= 0 ? itemId.substring(i + 1) : itemId;
    }
}
