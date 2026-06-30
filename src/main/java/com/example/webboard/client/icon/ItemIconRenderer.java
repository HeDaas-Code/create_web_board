package com.example.webboard.client.icon;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ItemIconRenderer — renders every registered item into a 32×32 PNG (JEI-style: actual rendered
 * item with tint, layered textures, 3D-model transform — not the raw texture PNG) plus its
 * localized name, for the dashboard's product picker.
 *
 * <p><b>Why this is client-only</b>: rendering items requires the client's {@link ItemRenderer},
 * texture atlas, model manager, and GL context — none of which exist on a dedicated server. The
 * rendered PNG + localized name are POSTed to the server's {@code IconPackStorage}, which then
 * serves them to the dashboard browser.
 *
 * <p><b>Render method</b>: allocate a 32×32 FBO, bind it, clear to transparent, render the item
 * with {@link GuiGraphics#renderItem(ItemStack, int, int)} (which goes through the full
 * ItemRenderer pipeline — same call JEI uses to draw its item list), then {@code glReadPixels}
 * the result into a {@code NativeImage} and PNG-encode it. The FBO is reused across renders to
 * avoid allocation churn.
 *
 * <p><b>Scheduling</b>: rendering MUST happen on the render thread (GL is single-threaded). The
 * dashboard's {@code IconUploadService} enqueues item ids; a client tick hook drains a few per
 * frame (lazy loading — keeps frame time bounded even with thousands of items).
 *
 * <p><b>Caching</b>: rendered ids are remembered in {@link #renderedIds} so a second renderer
 * pass (e.g. after installing new mods) skips already-done items. The cache is also persisted to
 * disk by the upload service so a restart doesn't re-render everything.
 *
 * <p><b>Localized name</b>: read from the client's loaded {@link Language} via the item's
 * description id ({@code Component.translatable(...).getString()}), which gives "铁锭" for
 * {@code minecraft:iron_ingot} when the player's language is zh_cn. Falls back to the short id
 * when no translation is loaded.
 */
public final class ItemIconRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ItemIconRenderer.class);

    public static final int ICON_SIZE = 32;

    private static final Map<String, byte[]> RENDERED_ICONS = new ConcurrentHashMap<>();
    private static final Map<String, String> RENDERED_NAMES = new ConcurrentHashMap<>();
    private static final java.util.Set<String> renderedIds = ConcurrentHashMap.newKeySet();

    private static RenderTarget fbo;

    private ItemIconRenderer() {}

    /** Snapshot of everything rendered so far (id -> PNG bytes). Used by export-icons. */
    public static Map<String, byte[]> snapshotIcons() {
        return new LinkedHashMap<>(RENDERED_ICONS);
    }

    /** Snapshot of all localized names (id -> "铁锭"). Used by export-icons. */
    public static Map<String, String> snapshotNames() {
        return new LinkedHashMap<>(RENDERED_NAMES);
    }

    /** True if this id has already been rendered (skip on subsequent passes). */
    public static boolean alreadyRendered(String itemId) {
        return renderedIds.contains(itemId);
    }

    /** Number of items rendered so far. */
    public static int renderedCount() {
        return renderedIds.size();
    }

    /**
     * Render a single item to a 32×32 PNG. MUST be called on the render thread.
     * Returns null if rendering fails (logged at warn level).
     *
     * @param itemId full registry id, e.g. "minecraft:iron_ingot"
     * @return PNG bytes, or null on failure
     */
    public static byte[] render(String itemId) {
        if (renderedIds.contains(itemId)) {
            return RENDERED_ICONS.get(itemId);
        }
        try {
            ResourceLocation key = ResourceLocation.parse(itemId);
            // 1.21.1 Registry.get returns Items.AIR for missing keys — use getOptional to detect.
            Optional<Item> opt = BuiltInRegistries.ITEM.getOptional(key);
            if (opt.isEmpty() || opt.get() == Items.AIR) return null;
            ItemStack stack = new ItemStack(opt.get());
            if (stack.isEmpty()) return null;

            ensureFbo();
            fbo.bindWrite(true);
            RenderSystem.clearColor(0, 0, 0, 0);
            // Clear BOTH color and depth. The FBO has a depth attachment (TextureTarget with
            // useDepth=true) but we were only clearing color — stale/garbage depth values caused
            // every fragment to fail the depth test, producing blank (fully transparent) icons.
            RenderSystem.clear(GlConst.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, false);

            // ---- Save + set GL state ----
            // ItemRenderer's shaders read RenderSystem.getModelViewMatrix() at flush time.
            // We replicate vanilla's GUI frame setup: ortho projection, identity model-view,
            // depth test + blend, 3D item lighting, scissor off. All saved + restored below.
            Matrix4f savedProjection = RenderSystem.getProjectionMatrix();
            RenderSystem.setProjectionMatrix(
                    new Matrix4f().setOrtho(0, ICON_SIZE, ICON_SIZE, 0, 1000, 21000),
                    VertexSorting.ORTHOGRAPHIC_Z);

            // 1.21.1: RenderSystem.getModelViewStack() returns org.joml.Matrix4fStack.
            Matrix4fStack mvStack = RenderSystem.getModelViewStack();
            mvStack.pushMatrix();
            boolean depthWasOn = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            boolean blendWasOn = GL11.glIsEnabled(GL11.GL_BLEND);
            boolean scissorWasOn = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);

            try {
                mvStack.identity();
                RenderSystem.applyModelViewMatrix();
                RenderSystem.enableDepthTest();
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
                Lighting.setupFor3DItems();
                if (scissorWasOn) GL11.glDisable(GL11.GL_SCISSOR_TEST);

                Minecraft mc = Minecraft.getInstance();
                // Fresh BufferSource (not the shared mc.renderBuffers().bufferSource()) so
                // pending vertices from earlier in the frame don't pollute the icon.
                ByteBufferBuilder builder = new ByteBufferBuilder(256);
                MultiBufferSource.BufferSource bufSrc = MultiBufferSource.immediate(builder);
                GuiGraphics gg = new GuiGraphics(mc, bufSrc);
                PoseStack pose = gg.pose();
                pose.pushPose();
                // CRITICAL: push items deep into -Z so they fall inside the ortho frustum.
                // setOrtho(0,32,32,0, 1000, 21000) makes visible eye-space z ∈ [-21000, -1000].
                // GuiGraphics.renderItem internally translates to z≈+150, which is OUTSIDE
                // the frustum → every vertex is clipped by the near plane → blank icon.
                // Translating to -8000 puts the item safely in the middle of the frustum.
                pose.translate(0, 0, -8000);
                // renderItem draws 16×16; scale ×2 to fill the 32×32 FBO.
                pose.scale(2, 2, 1);
                gg.renderItem(stack, 0, 0);
                gg.flush();
                bufSrc.endBatch();
                pose.popPose();
                builder.close();
            } finally {
                // CRITICAL: always restore GL state, even if renderItem threw.
                // Without popMatrix here, a ReportedException leaves the model-view stack
                // pushed but never popped — after 16 leaks the game crashes with
                // "max stack size of 16 reached" (issue #11).
                Lighting.setupForFlatItems();
                if (scissorWasOn) GL11.glEnable(GL11.GL_SCISSOR_TEST);
                if (!depthWasOn) RenderSystem.disableDepthTest();
                if (!blendWasOn) RenderSystem.disableBlend();
                mvStack.popMatrix();
                RenderSystem.applyModelViewMatrix();
                RenderSystem.setProjectionMatrix(savedProjection, VertexSorting.ORTHOGRAPHIC_Z);
            }

            byte[] png = readFboToPng();
            fbo.unbindWrite();

            if (png != null) {
                // Quality gate: if the rendered PNG is essentially blank (no opaque pixels),
                // reject it instead of caching + uploading a blank icon to the server. This is
                // what makes a future renderer regression immediately visible in the log rather
                // than silently filling the dashboard with empty thumbnails.
                int opaque = IconPngCodec.countOpaquePixels(png, ICON_SIZE, ICON_SIZE);
                if (opaque < MIN_OPAQUE_PIXELS) {
                    LOGGER.warn("[web_board] rendered icon for {} is blank ({} opaque pixels in {} bytes) — " +
                            "renderer state may be wrong, NOT caching/uploading. PNG bytes: {}",
                            itemId, opaque, png.length, java.util.Arrays.copyOfRange(png, 0, Math.min(32, png.length)));
                    return null;
                }
                RENDERED_ICONS.put(itemId, png);
                RENDERED_NAMES.put(itemId, localizedName(itemId, stack));
                renderedIds.add(itemId);
            }
            return png;
        } catch (Exception e) {
            LOGGER.warn("[web_board] failed to render icon for {}: {}", itemId, e.toString());
            return null;
        }
    }

    /** Get the localized name for an already-rendered item, or null. */
    public static String nameFor(String itemId) {
        return RENDERED_NAMES.get(itemId);
    }

    /** Drop all cached renders (e.g. on disconnect). */
    public static void reset() {
        RENDERED_ICONS.clear();
        RENDERED_NAMES.clear();
        renderedIds.clear();
    }

    // ---------- internal ----------

    private static void ensureFbo() {
        if (fbo != null) return;
        fbo = new TextureTarget(ICON_SIZE, ICON_SIZE, true, Minecraft.ON_OSX);
        fbo.setClearColor(0, 0, 0, 0);
        fbo.enableStencil();
    }

    /** Minimum opaque-pixel count for a render to be considered non-blank. A 32×32 = 1024 px icon
     *  should have hundreds of opaque pixels (most items cover >50% of the frame); allow a very
     *  generous floor to avoid rejecting legitimately sparse items (e.g. a thin sword diagonal). */
    private static final int MIN_OPAQUE_PIXELS = 8;

    private static byte[] readFboToPng() {
        // Bind the FBO's color texture and read its pixels back as RGBA.
        RenderSystem.bindTexture(fbo.getColorTextureId());
        // Allocate buffer for 32*32*4 bytes.
        java.nio.ByteBuffer buf = org.lwjgl.BufferUtils.createByteBuffer(ICON_SIZE * ICON_SIZE * 4);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        // Read with origin at the bottom-left (GL convention). Rows are flipped below.
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);

        // Flip vertically (PNG top-down, GL bottom-up).
        byte[] rowTmp = new byte[ICON_SIZE * 4];
        byte[] pixels = new byte[ICON_SIZE * ICON_SIZE * 4];
        buf.rewind();
        buf.get(pixels);
        for (int y = 0; y < ICON_SIZE / 2; y++) {
            int top = y * ICON_SIZE * 4;
            int bot = (ICON_SIZE - 1 - y) * ICON_SIZE * 4;
            System.arraycopy(pixels, top, rowTmp, 0, ICON_SIZE * 4);
            System.arraycopy(pixels, bot, pixels, top, ICON_SIZE * 4);
            System.arraycopy(rowTmp, 0, pixels, bot, ICON_SIZE * 4);
        }

        try {
            return IconPngCodec.encodeRgba(ICON_SIZE, ICON_SIZE, pixels);
        } catch (Exception e) {
            LOGGER.warn("[web_board] PNG encode failed: {}", e.toString());
            return null;
        }
    }

    private static String localizedName(String itemId, ItemStack stack) {
        try {
            String name = stack.getHoverName().getString();
            if (name != null && !name.isEmpty()) return name;
        } catch (Throwable ignored) { }
        // Fallback: short id (path after the colon).
        int i = itemId.indexOf(':');
        return i >= 0 ? itemId.substring(i + 1) : itemId;
    }
}
