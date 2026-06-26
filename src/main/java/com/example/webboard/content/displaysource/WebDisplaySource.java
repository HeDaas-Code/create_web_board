package com.example.webboard.content.displaysource;

import java.util.List;

import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * WebDisplaySource — Display Link source that mirrors each link's content to the local
 * browser dashboard served by the embedded HTTP server (issue #2).
 *
 * <p>For issue #1 (scaffold + registration), this source is registered but {@link #provideText}
 * returns a single placeholder line — there is no HTTP server yet, so there's nothing real to
 * display. Issue #2 will wire the in-world source data into {@code BoardRegistry} via the
 * {@code tick()} path and broadcast to connected browsers via WebSocket.
 *
 * <p>The {@link com.simibubi.create.foundation.data.CreateRegistrate#displaySource} helper
 * registers this class to {@link DisplaySource#BY_BLOCK_ENTITY} so the source appears in the
 * Display Link's "Configure" screen for any block entity (Display Link has a block entity).
 */
public class WebDisplaySource extends DisplaySource {

    /** DisplaySource registry key (the "name" passed to {@code CreateRegistrate.displaySource}). */
    public static final String ID = "web_board";

    /** Placeholder shown in-world while issue #2 (HTTP server) is unimplemented. */
    private static final List<MutableComponent> PLACEHOLDER = List.of(
            Component.literal("[Web Board — issue #2 will wire this]")
    );

    @Override
    public List<MutableComponent> provideText(DisplayLinkContext context, DisplayTargetStats stats) {
        // TODO #2: read BoardRegistry.lookup(context.getSourceBlockEntity()) and return its lines.
        // For now, just return the placeholder so the in-world display doesn't crash.
        return PLACEHOLDER;
    }

    /**
     * The Display Link calls {@code transferData} every {@link #getPassiveRefreshTicks()} ticks.
     * We use a slower refresh than the default 100 (5s) — once per second feels live enough for
     * a web dashboard without flooding the network stack with broadcasts.
     */
    @Override
    public int getPassiveRefreshTicks() {
        return 20;
    }
}