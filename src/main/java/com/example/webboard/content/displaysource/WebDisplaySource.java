package com.example.webboard.content.displaysource;

import java.util.ArrayList;
import java.util.List;

import com.example.webboard.content.registry.BoardContent;
import com.example.webboard.content.registry.BoardRegistry;
import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import com.simibubi.create.foundation.utility.CreateLang;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * WebDisplaySource — Display Link source that mirrors each link's content to the local
 * browser dashboard served by the embedded HTTP server.
 *
 * <p>Flow per DisplaySource tick (every {@link #getPassiveRefreshTicks()} ticks):
 * <ol>
 *   <li>Identify the Display Link block entity — prefer its custom name, fall back to
 *       {@code "Board @ <pos>"} so unnamed links still appear in the dashboard.</li>
 *   <li>Read current display lines from the in-world source via {@link #provideText}.</li>
 *   <li>Push a {@link BoardContent} snapshot into {@link BoardRegistry}, which fires
 *       a WS broadcast to every connected browser.</li>
 * </ol>
 *
 * <p>This class never blocks: BoardRegistry writes are O(1) and listener invocation
 * is synchronous on the writer thread (the game thread for #2). HTTP/WS sends happen
 * inside listeners on the same thread; Javalin's WsContext#send is documented thread-safe.
 */
public class WebDisplaySource extends DisplaySource {

    /** DisplaySource registry key (the "name" passed to {@code CreateRegistrate.displaySource}). */
    public static final String ID = "web_board";

    /** Translation key prefix for any user-visible strings. */
    private static final String LANG_PREFIX = "web_board.";

    private final BoardRegistry registry = BoardRegistry.get();

    /**
     * Placeholder shown in-world when the linked block entity doesn't yield any usable lines.
     * Translation key: {@code web_board.placeholder}.
     */
    private static final List<MutableComponent> PLACEHOLDER = List.of(
            CreateLang.translateDirect(LANG_PREFIX + "placeholder")
    );

    /**
     * Resolve this Display Link's board name. Falls back to a stable position-based key
     * ("Board @ x,y,z") since BlockEntity in 1.21 has no public customName accessor we can
     * use without invoking the more elaborate ItemStack-with-CUSTOM_NAME dance.
     *
     * <p>TODO #4: when a name is set via the Display Link's own UI (e.g. anvil rename of the
     * block via NeoForge's ItemNameBlockItem pattern), wire that custom name here.
     */
    private String resolveBoardName(DisplayLinkContext context) {
        BlockEntity be = context.getSourceBlockEntity();
        if (be != null) {
            var pos = be.getBlockPos();
            return "Board @ " + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        }
        return "Unknown Board";
    }

    @Override
    public List<MutableComponent> provideText(DisplayLinkContext context, DisplayTargetStats stats) {
        // Compute current lines (for #2: just the placeholder; future sources will do real work).
        List<MutableComponent> rawLines = PLACEHOLDER;

        // Flatten to plain strings for the web payload (the browser renders text, not MC Components).
        List<String> plainLines = new ArrayList<>(rawLines.size());
        for (MutableComponent mc : rawLines) {
            plainLines.add(mc.getString());
        }

        // Mirror to the registry so the HTTP/WS layer can broadcast.
        String boardName = resolveBoardName(context);
        registry.put(BoardContent.of(boardName, "create_web_board:" + ID, plainLines));

        return rawLines;
    }

    /**
     * Default is 100 (every 5 seconds). We override to 20 (once per second) so WS updates
     * to the dashboard feel live. For a single Display Link this is ~5 msg/s/board; with
     * 10 boards that's 50 msg/s which is well under any WS throughput limit.
     */
    @Override
    public int getPassiveRefreshTicks() {
        return 20;
    }
}