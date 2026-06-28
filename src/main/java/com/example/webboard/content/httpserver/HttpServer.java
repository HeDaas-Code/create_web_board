package com.example.webboard.content.httpserver;

import com.example.webboard.content.registry.BoardRegistry;

import io.javalin.Javalin;
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
            // Reasonable defaults for a localhost dashboard — never expose publicly.
            cfg.showJavalinBanner = false;
        });

        // Static dashboard assets. We serve them via explicit routes instead of Javalin's
        // staticFiles because staticFiles with hostedPath="/" does NOT auto-serve index.html
        // at GET / in Javalin 6 — it looks for an empty filename under the directory and 404s,
        // which left the dashboard as a blank page (user report: "白页面"). Explicit routes
        // read the classpath resource stream directly and are fully predictable.
        registerStaticAsset(app, "/", "/index.html", "text/html; charset=utf-8");
        registerStaticAsset(app, "/index.html", "/index.html", "text/html; charset=utf-8");
        registerStaticAsset(app, "/style.css", "/style.css", "text/css; charset=utf-8");
        registerStaticAsset(app, "/app.js", "/app.js", "application/javascript; charset=utf-8");

        ApiRoutes.register(app, BoardRegistry.get(), hub);
        NetworkRoutes.register(app);

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

    /**
     * Serves a single classpath resource under {@code /assets/create_web_board/web} at the
     * given route with the given content type. Used for the dashboard's static assets
     * (HTML/CSS/JS). Bytes are read eagerly so the InputStream can be closed before the
     * response is written — keeps the lambda simple and avoids leaking classpath handles.
     */
    private static void registerStaticAsset(Javalin app, String route, String classpathPath, String contentType) {
        String base = "/assets/create_web_board/web";
        app.get(route, ctx -> {
            try (var in = HttpServer.class.getResourceAsStream(base + classpathPath)) {
                if (in == null) {
                    ctx.status(404).result("asset not found: " + classpathPath);
                    return;
                }
                ctx.contentType(contentType);
                ctx.result(in.readAllBytes());
            }
        });
    }
}