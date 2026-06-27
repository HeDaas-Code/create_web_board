package com.example.webboard;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Create 6.0.10 addon: adds a "Web: ON/OFF" toggle to every Display Link. When enabled,
 * the link's live output (from <em>any</em> DisplaySource) is mirrored to a local browser
 * dashboard served by the embedded HTTP server. Pin to Create 6.0.10 / NeoForge 21.1.219 / MC 1.21.1.
 *
 * <p>The mirroring itself happens in {@code DisplaySourceTransferMixin} (wraps
 * {@code DisplaySource#provideText}); the toggle UI lives in {@code DisplayLinkScreenMixin}.
 */
@Mod(CreateWebBoard.MOD_ID)
public class CreateWebBoard {
    public static final String MOD_ID = "create_web_board";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public CreateWebBoard(IEventBus modBus) {
        LOGGER.info("[{}] loaded — Display Link web-mirror toggle", MOD_ID);
    }
}
