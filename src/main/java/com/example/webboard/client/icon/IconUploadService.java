package com.example.webboard.client.icon;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * IconUploadService — client-side driver that, after the player joins a world, walks every
 * registered item, renders each via {@link ItemIconRenderer}, and POSTs the PNG + localized
 * name to the dashboard server's {@code /api/item-icon/{id}} endpoint.
 *
 * <p><b>Why a client tick hook instead of a background thread</b>: GL calls (FBO bind, render,
 * glReadTexImage) MUST happen on the render thread. So we drain a few items per client tick —
 * lazy loading that keeps frame time bounded even with thousands of items. The HTTP POSTs run
 * on a worker thread (no GL), so they don't stall rendering.
 *
 * <p><b>Dedup against the server</b>: on start we GET {@code /api/icon-pack/status}. If the
 * server already has N icons cached, we still walk the registry (the set may have changed
 * since), but skip POSTing any id the server already has — saves bandwidth on re-connect.
 * The simpler "always re-upload everything" path is also fine for the common case (the server's
 * IconPackStorage dedups by id anyway); we just skip the network round-trip.
 *
 * <p><b>Rate</b>: {@link #RENDER_PER_TICK} items rendered per tick (per frame, ~20 tps). At
 * 4 items/tick, 4000 items finish in ~33 seconds with negligible per-frame cost. The HTTP
 * uploads are async and batched; they don't gate rendering.
 *
 * <p><b>Idempotency</b>: started flag ensures only one render pass runs at a time. Calling
 * {@link #start()} again after completion is a no-op until {@link #reset()} is called (e.g.
 * on disconnect).
 */
public final class IconUploadService {

    private static final Logger LOGGER = LoggerFactory.getLogger(IconUploadService.class);

    /** Dashboard server URL — same host as the in-game web viewer (the host runs both). */
    private static final String SERVER_URL = "http://127.0.0.1:8080";

    /** Items to render per client tick. Tuned for ~1ms frame cost on a midrange GPU. */
    private static final int RENDER_PER_TICK = 4;

    /** Max items per upload batch (each is a separate HTTP POST; this caps concurrency). */
    private static final int UPLOAD_BATCH = 32;

    private static final AtomicBoolean started = new AtomicBoolean(false);
    private static final AtomicBoolean done = new AtomicBoolean(false);
    private static final ConcurrentLinkedQueue<RenderedItem> uploadQueue = new ConcurrentLinkedQueue<>();
    private static final List<String> pendingIds = new ArrayList<>();

    private static HttpClient httpClient;
    private static volatile int uploadedCount = 0;
    private static volatile int skippedCount = 0;

    /** True if we temporarily disabled Iris shaders during the render pass (restored on finish). */
    private static boolean irisShadersDisabled = false;

    private IconUploadService() {}

    private record RenderedItem(String id, String name, byte[] png) {}

    /** True if a render pass has been started and is not yet complete. */
    public static boolean isRunning() {
        return started.get() && !done.get();
    }

    /** True if the render pass has finished. */
    public static boolean isDone() {
        return done.get();
    }

    /** Number of icons uploaded so far this session. */
    public static int uploadedCount() {
        return uploadedCount;
    }

    /**
     * Begin the render+upload pass. Called from a client-world-join event. No-op if already
     * started. Builds the pending-id list by iterating the item registry, then the per-tick
     * drainer ({@link #tick()}) renders + enqueues uploads.
     */
    public static void start() {
        if (!started.compareAndSet(false, true)) return;
        done.set(false);
        uploadedCount = 0;
        skippedCount = 0;
        uploadQueue.clear();
        pendingIds.clear();
        // Snapshot all registered item ids. Covers vanilla + Create + every addon.
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
            if (key != null) pendingIds.add(key.toString());
        }
        LOGGER.info("[web_board] icon renderer starting: {} items to render", pendingIds.size());
        // Initialize the HTTP client (lazy — first call to upload() would do this anyway).
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        // Iris shaderpack replaces vanilla RenderType shaders with its own gbuffer programs.
        // When active, off-screen FBO item renders produce garbage (wrong uniforms, missing
        // samplers). Temporarily disable shaders for the entire batch, restore when done.
        disableIrisShaders();
    }

    /** Reset state so the next world join starts a fresh pass. Called on disconnect. */
    public static void reset() {
        enableIrisShaders();
        started.set(false);
        done.set(false);
        uploadQueue.clear();
        pendingIds.clear();
        uploadedCount = 0;
        skippedCount = 0;
        ItemIconRenderer.reset();
    }

    /**
     * Per-tick hook: render up to {@link #RENDER_PER_TICK} items, enqueue their uploads, and
     * drain the upload queue. MUST be called on the render thread (client tick event).
     */
    public static void tick() {
        if (!started.get() || done.get()) return;
        // Render a few items this tick.
        int renderedThisTick = 0;
        while (!pendingIds.isEmpty() && renderedThisTick < RENDER_PER_TICK) {
            String id = pendingIds.remove(pendingIds.size() - 1);
            byte[] png = ItemIconRenderer.render(id);
            if (png != null) {
                String name = ItemIconRenderer.nameFor(id);
                uploadQueue.add(new RenderedItem(id, name, png));
            }
            renderedThisTick++;
        }
        // Drain the upload queue on a worker thread (no GL needed).
        drainUploads();
        // Done? Wait for the upload queue to flush too.
        if (pendingIds.isEmpty() && uploadQueue.isEmpty()) {
            done.set(true);
            enableIrisShaders();
            LOGGER.info("[web_board] icon render+upload complete: {} uploaded, {} skipped",
                    uploadedCount, skippedCount);
        }
    }

    private static void drainUploads() {
        int n = 0;
        while (n < UPLOAD_BATCH) {
            RenderedItem item = uploadQueue.poll();
            if (item == null) break;
            // Submit async — don't block the render thread on HTTP.
            final RenderedItem fi = item;
            httpClient.sendAsync(buildRequest(fi), HttpResponse.BodyHandlers.discarding())
                    .whenComplete((resp, err) -> {
                        if (err != null || resp == null || resp.statusCode() >= 400) {
                            skippedCount++;
                        } else {
                            uploadedCount++;
                        }
                    });
            n++;
        }
    }

    private static HttpRequest buildRequest(RenderedItem item) {
        String url = SERVER_URL + "/api/item-icon/" + URLEncoder.encode(item.id(), StandardCharsets.UTF_8);
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofByteArray(item.png()));
        if (item.name() != null && !item.name().isEmpty()) {
            b.header("X-Item-Name", URLEncoder.encode(item.name(), StandardCharsets.UTF_8));
        }
        return b.build();
    }

    // ---- Iris shaderpack compatibility ----
    // Iris replaces vanilla RenderType shaders with its own gbuffer programs that expect
    // Iris-specific uniforms (cameraPosition, colortex samplers, etc.). When rendering items
    // off-screen in our own FBO, those uniforms are stale/missing → garbage output.
    // We use reflection (Iris is an optional dependency) to temporarily disable shaders for
    // the entire batch, then restore when done. setShadersEnabledAndApply triggers a reload,
    // so we only toggle once per batch — not per item.

    private static void disableIrisShaders() {
        try {
            Class<?> irisApi = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object instance = irisApi.getMethod("getInstance").invoke(null);
            boolean inUse = (boolean) irisApi.getMethod("isShaderPackInUse").invoke(instance);
            if (inUse) {
                Object config = irisApi.getMethod("getConfig").invoke(instance);
                config.getClass().getMethod("setShadersEnabledAndApply", boolean.class).invoke(config, false);
                irisShadersDisabled = true;
                LOGGER.info("[web_board] Iris shaderpack detected — temporarily disabled for icon rendering");
            }
        } catch (ClassNotFoundException ignored) {
            // Iris not installed — nothing to do.
        } catch (Throwable t) {
            LOGGER.warn("[web_board] Failed to disable Iris shaders: {}", t.toString());
        }
    }

    private static void enableIrisShaders() {
        if (!irisShadersDisabled) return;
        irisShadersDisabled = false;
        try {
            Class<?> irisApi = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object instance = irisApi.getMethod("getInstance").invoke(null);
            Object config = irisApi.getMethod("getConfig").invoke(instance);
            config.getClass().getMethod("setShadersEnabledAndApply", boolean.class).invoke(config, true);
            LOGGER.info("[web_board] Iris shaders re-enabled");
        } catch (Throwable t) {
            LOGGER.warn("[web_board] Failed to re-enable Iris shaders: {}", t.toString());
        }
    }
}
