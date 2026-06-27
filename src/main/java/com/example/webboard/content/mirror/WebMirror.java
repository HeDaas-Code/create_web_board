package com.example.webboard.content.mirror;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.example.webboard.content.persistence.BoardDatabase;
import com.example.webboard.content.registry.BoardContent;
import com.example.webboard.content.registry.BoardRegistry;
import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.api.registry.CreateBuiltInRegistries;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Mirrors a Display Link's outgoing text to the {@link BoardRegistry} when the link has
 * the "WebSynced" flag enabled in its source config.
 *
 * <p>Called from {@code DisplaySourceTransferMixin} which wraps {@code DisplaySource#provideText}
 * inside {@code transferData}. This way <em>any</em> DisplaySource (Create built-in or addon)
 * is mirrored — the player just toggles the option on the Display Link UI.
 *
 * <p>The toggle state is stored in the link's source-config CompoundTag under {@link #NBT_KEY},
 * so it piggy-backs on Create's existing {@code DisplayLinkConfigurationPacket} + NBT
 * persistence — no custom networking or BlockEntity fields required.
 *
 * <p><b>Performance</b>: {@code transferData} fires every few ticks per active Display Link.
 * Without dedup, every refresh rebuilt the lines, allocated a {@link BoardContent}, mutated the
 * registry, fired a {@code ChangeEvent.Put} listener, built a JSON string and pushed it to every
 * WebSocket session — <em>all on the game thread</em>. That froze the game once several links
 * were active. Two fixes:
 * <ul>
 *   <li><b>Here</b>: dedup. We cache the last-sent {@code (sourceType, lines)} per board name;
 *       if unchanged we skip {@link BoardRegistry#put} entirely. Display content is usually
 *       stable for long stretches (e.g. an item count), so the common case becomes a cheap
 *       list-equality check after the first broadcast.</li>
 *   <li>In {@code WebSocketHub}: the JSON-build + WS-send is dispatched to a dedicated
 *       daemon thread, so the game thread only does the registry {@code put} (a
 *       {@code ConcurrentHashMap} op — microseconds).</li>
 * </ul>
 */
public final class WebMirror {

    /** NBT key inside the Display Link's source-config tag. */
    public static final String NBT_KEY = "WebSynced";

    /**
     * Last-sent content per board, used to suppress no-op refreshes. Keyed by board name.
     * Tracks {@code lastBroadcastMs} so we can send a heartbeat refresh even when the content
     * is unchanged (otherwise stable-content boards would go stale after 30s — see below).
     */
    private record Cached(String sourceType, List<String> lines, long lastBroadcastMs) {}

    /**
     * Maximum gap between two broadcasts of unchanged content. Boards whose content is stable
     * (the common case — item counts, static labels) still get a fresh {@code lastUpdatedMs}
     * at least this often, so {@link BoardContent#stale()} stays false as long as the Display
     * Link is alive and calling {@code transferData}. Without this heartbeat, the v0.2.3 dedup
     * caused every stable-content board to flip to "offline" 30s after its last content change
     * (user report: "一直是离线状态了").
     */
    private static final long HEARTBEAT_INTERVAL_MS = 10_000;

    private static final ConcurrentHashMap<String, Cached> LAST_SENT = new ConcurrentHashMap<>();

    private WebMirror() {}

    public static void mirror(DisplayLinkContext context, DisplaySource source, List<MutableComponent> text) {
        Level level = context.level();
        if (level != null && level.isClientSide) return;

        BlockEntity be = context.blockEntity();
        if (be == null) return;

        String name = boardName(be);
        CompoundTag cfg = context.sourceConfig();
        if (cfg == null || !cfg.getBoolean(NBT_KEY)) {
            // Toggle off (or never set): drop any previously mirrored board for this link.
            // remove() is a no-op if the board isn't tracked; clear the dedup cache too.
            LAST_SENT.remove(name);
            BoardRegistry.get().remove(name);
            if (BoardDatabase.get().isInitialized()) {
                BoardDatabase.get().markRemoved(name);
            }
            return;
        }

        List<String> lines = new ArrayList<>(text.size());
        for (MutableComponent mc : text) lines.add(mc.getString());

        ResourceLocation id = CreateBuiltInRegistries.DISPLAY_SOURCE.getKey(source);
        String sourceType = id != null ? id.toString() : "unknown";

        // Preserve a user-set display name across content refreshes (rename is set via the
        // dashboard modal; without this the next refresh would drop it back to the position key).
        BoardContent existing = BoardRegistry.get().get(name);
        String displayName = existing != null ? existing.displayName() : null;
        // Likewise preserve user-set tags + product item ids — a content refresh must never wipe
        // the dashboard-side organization the user configured.
        List<String> tags = existing != null ? existing.tags() : List.of();
        List<String> itemIds = existing != null ? existing.itemIds() : List.of();

        long now = System.currentTimeMillis();
        Cached prev = LAST_SENT.get(name);
        boolean contentChanged = prev == null
                || !prev.sourceType().equals(sourceType)
                || !prev.lines().equals(lines);
        boolean heartbeatDue = prev == null || (now - prev.lastBroadcastMs() > HEARTBEAT_INTERVAL_MS);

        // Dedup: skip the registry put + WS broadcast when the content is unchanged AND a
        // heartbeat was sent recently. The last broadcast's lastUpdatedMs keeps stale() false
        // for 30s, and we re-broadcast every HEARTBEAT_INTERVAL_MS (10s), so an alive DL never
        // trips the stale threshold. When the DL is destroyed it stops calling mirror(), the
        // heartbeat stops, and after 30s the staleScanner correctly flags it offline.
        if (!contentChanged && !heartbeatDue) {
            return;
        }
        LAST_SENT.put(name, new Cached(sourceType, lines, now));

        BoardContent content = new BoardContent(name, displayName, sourceType, lines, now, tags, itemIds);
        BoardRegistry.get().put(content);
        if (BoardDatabase.get().isInitialized()) {
            BoardDatabase.get().upsert(content);
        }
    }

    private static String boardName(BlockEntity be) {
        BlockPos pos = be.getBlockPos();
        return "Board @ " + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
