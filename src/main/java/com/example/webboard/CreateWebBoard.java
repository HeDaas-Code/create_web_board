package com.example.webboard;

import com.example.webboard.content.displaysource.BlockEntitySummaryDisplaySource;
import com.example.webboard.content.displaysource.WebDisplaySource;
import com.simibubi.create.foundation.data.CreateRegistrate;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Create 6.0.10 addon: two DisplaySources that mirror Display Link state to a local
 * browser dashboard in real time. Pin to Create 6.0.10 / NeoForge 21.1.219 / MC 1.21.1.
 *
 * <p>Registered sources:
 * <ul>
 *   <li>{@link WebDisplaySource} — placeholder, ID-based registration</li>
 *   <li>{@link BlockEntitySummaryDisplaySource} — reads source BE state, ID-based</li>
 * </ul>
 */
@Mod(CreateWebBoard.MOD_ID)
public class CreateWebBoard {
    public static final String MOD_ID = "create_web_board";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final CreateRegistrate REGISTRATE = CreateRegistrate.create(MOD_ID);

    public CreateWebBoard(IEventBus modBus) {
        REGISTRATE.registerEventListeners(modBus);

        // Both sources write to the same BoardRegistry → single web dashboard sees both.
        // .register() is what writes into Create's DISPLAY_SOURCE registry.
        REGISTRATE.displaySource(WebDisplaySource.ID, WebDisplaySource::new).register();
        REGISTRATE.displaySource(BlockEntitySummaryDisplaySource.ID, BlockEntitySummaryDisplaySource::new).register();

        LOGGER.info("[{}] loaded — 2 DisplaySources → web dashboard bridge", MOD_ID);
    }
}