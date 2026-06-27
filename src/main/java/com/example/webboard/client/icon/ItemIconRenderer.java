package com.example.webboard.client.icon;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Matrix4f;
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
            RenderSystem.clear(GlConst.GL_COLOR_BUFFER_BIT, false);

            // Set an ortho projection matching the 32×32 FBO. GuiGraphics.renderItem uses
            // RenderSystem.getProjectionMatrix(); without this it inherits the main window's
            // projection (e.g. 1920×1080), the item renders at ~1px, and the read-back PNG is
            // essentially empty (the "icons don't render" bug). GUI convention: top-left origin.
            // Save + restore so the rest of the frame's rendering isn't affected.
            Matrix4f savedProjection = RenderSystem.getProjectionMatrix();
            RenderSystem.setProjectionMatrix(
                    new Matrix4f().setOrtho(0, ICON_SIZE, ICON_SIZE, 0, 1000, 3000),
                    VertexSorting.ORTHOGRAPHIC_Z);
            // Scissor may be left active by the previous frame's GUI rendering — disable it
            // so the item isn't culled against the main window's scissor box.
            boolean scissorWasOn = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            if (scissorWasOn) GL11.glDisable(GL11.GL_SCISSOR_TEST);

            Minecraft mc = Minecraft.getInstance();
            GuiGraphics gg = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            PoseStack pose = gg.pose();
            pose.pushPose();
            // renderItem draws a 16×16 icon; scale ×2 so it fills the 32×32 FBO exactly.
            // (No translate — the icon's top-left goes to FBO origin (0,0).)
            pose.scale(2, 2, 1);
            gg.renderItem(stack, 0, 0);
            gg.flush();
            pose.popPose();

            // Restore projection + scissor so the rest of this frame renders normally.
            RenderSystem.setProjectionMatrix(savedProjection, VertexSorting.ORTHOGRAPHIC_Z);
            if (scissorWasOn) GL11.glEnable(GL11.GL_SCISSOR_TEST);

            byte[] png = readFboToPng();
            fbo.unbindWrite();

            if (png != null) {
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
            return pngEncodeRgba(ICON_SIZE, ICON_SIZE, pixels);
        } catch (Exception e) {
            LOGGER.warn("[web_board] PNG encode failed: {}", e.toString());
            return null;
        }
    }

    /**
     * Minimal PNG encoder (RGBA, 8-bit). No external deps — keeps the jar small and avoids
     * depending on MC's NativeImage (whose API shifts between MC versions). Compressed with
     * java.util.zip (zlib deflate, same as PNG mandates).
     */
    private static byte[] pngEncodeRgba(int width, int height, byte[] rgba) throws java.io.IOException {
        java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
        // PNG signature
        raw.write(0x89); raw.write(0x50); raw.write(0x4E); raw.write(0x47);
        raw.write(0x0D); raw.write(0x0A); raw.write(0x1A); raw.write(0x0A);

        // IHDR
        byte[] ihdr = new byte[13];
        putInt(ihdr, 0, width);
        putInt(ihdr, 4, height);
        ihdr[8] = 8;     // bit depth
        ihdr[9] = 6;     // color type: RGBA
        ihdr[10] = 0;    // compression: deflate
        ihdr[11] = 0;    // filter: standard
        ihdr[12] = 0;    // interlace: none
        writeChunk(raw, "IHDR", ihdr);

        // IDAT: filter byte (0 = None) per scanline, then zlib-deflate the whole thing.
        java.io.ByteArrayOutputStream scanlines = new java.io.ByteArrayOutputStream();
        for (int y = 0; y < height; y++) {
            scanlines.write(0); // filter type 0 (None)
            scanlines.write(rgba, y * width * 4, width * 4);
        }
        java.util.zip.Deflater def = new java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION);
        def.setInput(scanlines.toByteArray());
        def.finish();
        byte[] buf = new byte[8192];
        java.io.ByteArrayOutputStream compressed = new java.io.ByteArrayOutputStream();
        while (!def.finished()) {
            int n = def.deflate(buf);
            compressed.write(buf, 0, n);
        }
        writeChunk(raw, "IDAT", compressed.toByteArray());

        // IEND
        writeChunk(raw, "IEND", new byte[0]);
        return raw.toByteArray();
    }

    private static void writeChunk(java.io.OutputStream out, String type, byte[] data) throws java.io.IOException {
        byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] len = new byte[4];
        putInt(len, 0, data.length);
        out.write(len);
        out.write(typeBytes);
        out.write(data);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(typeBytes);
        crc.update(data);
        byte[] crcBytes = new byte[4];
        putInt(crcBytes, 0, (int) crc.getValue());
        out.write(crcBytes);
    }

    private static void putInt(byte[] b, int off, int v) {
        b[off]     = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
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
