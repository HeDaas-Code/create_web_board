package com.example.webboard.content.lifecycle;

import com.example.webboard.CreateWebBoard;
import com.example.webboard.content.httpserver.ConfigLoader;
import com.example.webboard.content.httpserver.HttpServer;
import com.example.webboard.content.httpserver.ServerConfig;
import com.example.webboard.content.persistence.BoardDatabase;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * ServerLifecycle — starts the embedded HTTP server when the MC server starts, stops it when
 * the server stops. Works on both dedicated servers and integrated (single-player) servers.
 *
 * <p>Why {@link ServerStartedEvent} instead of {@code FMLCommonSetupEvent}? The latter fires
 * before worlds exist; we want to bind the port only when we know the game is actually running
 * (so single-player users see the server come up in their log right when they load the world).
 *
 * <p>For pure clients (no server at all) we don't bind — the dashboard is server-side only.
 * The web UI is browsed from the host machine, not from a remote MC client.
 */
@EventBusSubscriber(modid = CreateWebBoard.MOD_ID)
public final class ServerLifecycle {

    private static HttpServer httpServer;

    private ServerLifecycle() {}

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (httpServer != null) {
            CreateWebBoard.LOGGER.warn("[web_board] HTTP server already running; not starting a second instance");
            return;
        }
        // Issue #4: load host/port from webboard-server.toml (next to mods.toml) if present.
        // The path is the running server's config dir; we don't have a direct handle from this
        // event signature, so we fall back to a well-known relative path. Operators can also
        // override port at runtime via a system property (useful for tests).
        var configPath = java.nio.file.Path.of("config", "webboard-server.toml");
        ServerConfig cfg = ConfigLoader.load(configPath);
        String portOverride = System.getProperty("webboard.port");
        if (portOverride != null) {
            try {
                cfg = cfg.withPort(Integer.parseInt(portOverride));
            } catch (NumberFormatException ignored) { /* keep loaded port */ }
        }
        // Fresh server start -> clear any boards left over from a previous run.
        com.example.webboard.content.registry.BoardRegistry.get().clearAll();
        // Initialize SQLite persistence and load boards from the previous session.
        BoardDatabase.get().init();
        com.example.webboard.content.registry.BoardRegistry.get().putAll(BoardDatabase.get().loadAll());
        httpServer = new HttpServer(cfg, com.example.webboard.content.registry.BoardRegistry.get());
        try {
            httpServer.start();
        } catch (Exception e) {
            CreateWebBoard.LOGGER.error("[web_board] Failed to start HTTP server (port in use?): {}", e.toString());
            httpServer = null;
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (httpServer != null) {
            // Close the database connection before shutting down the HTTP server.
            BoardDatabase.get().close();
            httpServer.stop();
            httpServer = null;
        }
    }

    /** Exposed for tests / manual control. Returns null when not running. */
    public static HttpServer getServer() {
        return httpServer;
    }
}