package com.example.webboard;

import com.example.webboard.content.displaysource.WebDisplaySource;
import com.simibubi.create.foundation.data.CreateRegistrate;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Create 6.0.10 addon: a Display Link source that mirrors each link to a local
 * browser dashboard in real time. Pin to Create 6.0.10 / NeoForge 21.1.219 / MC 1.21.1.
 *
 * <p>Boot order is intentionally minimal — the only registered thing is the
 * {@link WebDisplaySource}, registered through CreateRegistrate's
 * {@code displaySource(name, supplier).register()} fluent chain.
 */
@Mod(CreateWebBoard.MOD_ID)
public class CreateWebBoard {
    public static final String MOD_ID = "create_web_board";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // CreateRegistrate = Registrate + a couple of Create-specific helpers (displaySource, etc.).
    public static final CreateRegistrate REGISTRATE = CreateRegistrate.create(MOD_ID);

    public CreateWebBoard(IEventBus modBus) {
        REGISTRATE.registerEventListeners(modBus);

        // Register the DisplaySource. The .register() call is what writes into Create's
        // DISPLAY_SOURCE registry; without it, the source is built but never visible to
        // Display Link's "Configure" screen.
        REGISTRATE.displaySource(WebDisplaySource.ID, WebDisplaySource::new).register();

        LOGGER.info("[{}] loaded — Display Link → web dashboard bridge", MOD_ID);
    }
}