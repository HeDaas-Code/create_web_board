package com.example.webboard;

import com.example.webboard.content.displaysource.WebDisplaySource;
import com.example.webboard.registry.ModBlockEntities;
import com.example.webboard.registry.ModBlocks;
import com.example.webboard.registry.ModItems;
import com.example.webboard.registry.ModRecipes;
import com.simibubi.create.foundation.data.CreateRegistrate;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Create 6.0.10 addon: a Display Link source that mirrors each link to a local
 * browser dashboard in real time. Pin to Create 6.0.10 / NeoForge 21.1.219 / MC 1.21.1.
 */
@Mod(CreateWebBoard.MOD_ID)
public class CreateWebBoard {
    public static final String MOD_ID = "create_web_board";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // CreateRegistrate = Registrate + a couple of Create-specific helpers (kineticBlock(), etc.)
    public static final CreateRegistrate REGISTRATE = CreateRegistrate.create(MOD_ID);

    public CreateWebBoard(IEventBus modBus) {
        REGISTRATE.registerEventListeners(modBus);

        // Bind the recipe DeferredRegisters to the mod bus.
        ModRecipes.registerToBus(modBus);

        // Issue #1: register WebDisplaySource via CreateRegistrate.displaySource().
        // The .register() call is what writes into Create's DISPLAY_SOURCE registry; without
        // it, the source is built but never visible to Display Link's "Configure" screen.
        REGISTRATE.displaySource(WebDisplaySource.ID, WebDisplaySource::new).register();

        // Issue #2 scope: HTTP server lifecycle (start/stop) will be wired to FMLCommonSetupEvent.
        //
        // Registration order rationale (matters because of CreateRegistrate.entry() holding
        // lazy RegistryObject refs): all REGISTRATE.entry() calls (including the displaySource
        // above) must come BEFORE the DeferredRegister batches (ModBlocks/Items/etc.) so that
        // when any builder's onRegisterAfter hook fires, the entry is already finalized.
        ModBlocks.register();
        ModBlockEntities.register();
        ModItems.register();
        ModRecipes.register();

        LOGGER.info("[{}] loaded — Display Link → web dashboard bridge", MOD_ID);
    }
}