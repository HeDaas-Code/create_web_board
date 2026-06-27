package com.example.webboard.content.httpserver;

import com.example.webboard.content.registry.BoardRegistry;

import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HttpServer — wraps Javalin's lifecycle for the in-process dashboard server.
 *
 * <p>Bound to {@code 127.0.0.1:8080} by default (localhost only — never expose publicly).
 * Configurable via {@link ServerConfig} (issue #4 will add the TOML reader; defaults here
 * are fine for issue #2).
 *
 * <p>Static assets are served from {@code /static/*} mapping to the
 * {@code /assets/create_web_board/web/} classpath directory (issue #3 fills it).
 */
public class HttpServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpServer.class);

    private final ServerConfig config;
    private final WebSocketHub hub;
    private Javalin app;

    public HttpServer(ServerConfig config, BoardRegistry registry) {
        this.config = config;
        this.hub = new WebSocketHub(registry);
    }

    public WebSocketHub hub() {
        return hub;
    }

    public int port() {
        return app == null ? -1 : app.port();
    }

    public synchronized void start() {
        if (app != null) {
            LOGGER.warn("[web_board] HTTP server already started on port {}", port());
            return;
        }
        app = Javalin.create(cfg -> {
            // Single static-files entry: the classpath dir /assets/create_web_board/web
            // is served at "/", so:
            //   GET /          → index.html (Javalin serves index.html at the hosted root)
            //   GET /style.css → style.css
            //   GET /app.js    → app.js
            // The previous setup also mounted the same dir at "/static" — that was unused
            // (index.html references /style.css and /app.js directly) and the duplicate
            // mount was a suspected cause of inconsistent asset resolution. Dropping it.
            cfg.staticFiles.add(staticDir -> {
                staticDir.hostedPath = "/";
                staticDir.directory = "/assets/create_web_board/web";
                staticDir.location = Location.CLASSPATH;
                staticDir.skipFileFunction = req -> false;  // serve index.html on "/"
            });
            // Reasonable defaults for a localhost dashboard — never expose publicly.
            cfg.showJavalinBanner = false;
        });

        ApiRoutes.register(app, BoardRegistry.get(), hub);

        // WebSocket endpoint — clients connect here for live updates.
        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                hub.sessions().add(ctx);
                hub.sendInitialSnapshot(ctx);
                LOGGER.info("[web_board] WS client connected: {} (total {})",
                        ctx.sessionId(), hub.connectionCount());
            });
            ws.onClose(ctx -> {
                hub.sessions().remove(ctx);
                LOGGER.info("[web_board] WS client disconnected: {} (total {})",
                        ctx.sessionId(), hub.connectionCount());
            });
            ws.onError(ctx -> {
                hub.sessions().remove(ctx);
                LOGGER.warn("[web_board] WS error on {}: {}", ctx.sessionId(), ctx.error());
            });
        });

        app.start(config.host(), config.port());
        LOGGER.info("[web_board] HTTP server started at http://{}:{}", config.host(), app.port());
    }

    public synchronized void stop() {
        if (app == null) return;
        try {
            app.stop();
            LOGGER.info("[web_board] HTTP server stopped");
        } catch (Exception e) {
            LOGGER.warn("[web_board] HTTP server stop failed: {}", e.toString());
        } finally {
            app = null;
            // Drain pending WS broadcasts (e.g. final remove events) then stop the worker thread.
            hub.shutdown();
        }
    }

    public boolean isRunning() {
        return app != null;
    }
}