package com.example.webboard.client.icon;

import com.example.webboard.CreateWebBoard;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * ClientIconEvents — wires the icon renderer + uploader into the client lifecycle.
 *
 * <p><b>When to render</b>: only when the player is hosting the world (single-player or LAN).
 * In that mode our mod's HTTP server runs locally at 127.0.0.1:8080, so the rendered icons have
 * somewhere to go. When the player joins a <em>remote</em> dedicated server, the dashboard's
 * HTTP server is on the remote box (not localhost) — uploading from the player's client would
 * hit nothing. The operator on the dedicated server should instead install the iconpack mod on
 * a client, run {@code /webboard export-icons}, and drop the resulting zip into the server's
 * {@code config/} dir.
 *
 * <p><b>Render drain</b>: each client tick ({@link ClientTickEvent.Post}) calls
 * {@link IconUploadService#tick()}, which renders a few items on the render thread and queues
 * async uploads. At ~4 items/tick (20 tps) a 4000-item catalog finishes in ~50s with negligible
 * frame cost.
 *
 * <p><b>Reset on disconnect</b>: clears the renderer's in-memory cache so the next world join
 * starts fresh (different mod set, different player language).
 *
 * <p>Registered with {@link Dist#CLIENT} so this class never loads on a dedicated server
 * (where the {@code Minecraft} class doesn't exist).
 */
@EventBusSubscriber(modid = CreateWebBoard.MOD_ID, value = Dist.CLIENT)
public final class ClientIconEvents {

    private ClientIconEvents() {}

    @SubscribeEvent
    public static void onPlayerLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        // Only render when we're hosting — our HTTP server runs at 127.0.0.1:8080 then.
        // On remote dedicated servers, the operator uses /webboard export-icons on a client
        // and uploads the zip; we don't auto-render.
        if (Minecraft.getInstance().getSingleplayerServer() == null) return;
        CreateWebBoard.LOGGER.info("[web_board] player joined a hosted world — starting icon renderer");
        IconUploadService.start();
    }

    @SubscribeEvent
    public static void onPlayerLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        IconUploadService.reset();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        IconUploadService.tick();
    }
}
