package com.example.webboard.client.icon;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
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

            // ---- Save all GL state we're about to touch ----
            // The dashboard's item icons were coming out blank because GuiGraphics.renderItem's
            // shaders read RenderSystem.getModelViewMatrix() at flush time — but we never set it,
            // so the item's vertices were multiplied by whatever matrix the previous frame left
            // behind (usually a large translate), throwing every vertex off-screen. The fix is to
            // replicate what vanilla's GameRenderer does at the start of each GUI frame:
            //   1. Set an ortho projection matching the FBO size.
            //   2. Reset the model-view stack to identity and apply it to the GPU.
            //   3. Enable depth test + blend (ItemRenderer toggles these internally, but starting
            //      from a known state avoids contamination from the previous frame).
            //   4. Set up 3D item lighting (without it, shaded block items render black/blank).
            //   5. Disable scissor (the main window's scissor box would cull our 32×32 render).
            // All of this is saved + restored so the rest of the frame is unaffected.
            Matrix4f savedProjection = RenderSystem.getProjectionMatrix();
            RenderSystem.setProjectionMatrix(
                    // near=1000, far=21000 matches vanilla's GUI projection (GameRenderer.GUI_Z_NEAR).
                    // Our earlier 1000..3000 was too narrow and clipped the item's default z offset.
                    new Matrix4f().setOrtho(0, ICON_SIZE, ICON_SIZE, 0, 1000, 21000),
                    VertexSorting.ORTHOGRAPHIC_Z);

            PoseStack mvStack = RenderSystem.getModelViewStack();
            mvStack.pushPose();
            mvStack.identity();
            RenderSystem.applyModelViewMatrix();

            boolean depthWasOn = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            RenderSystem.enableDepthTest();
            boolean blendWasOn = GL11.glIsEnabled(GL11.GL_BLEND);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

            Lighting.setupFor3DItems();

            boolean scissorWasOn = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            if (scissorWasOn) GL11.glDisable(GL11.GL_SCISSOR_TEST);

            Minecraft mc = Minecraft.getInstance();
            // Use a fresh BufferSource instead of the shared mc.renderBuffers().bufferSource():
            // the shared one may hold pending vertices from earlier in the frame; flushing those
            // into our 32×32 FBO would pollute the icon. A private source is flushed and discarded.
            MultiBufferSource.BufferSource bufSrc = new MultiBufferSource.BufferSource(
                    new com.mojang.blaze3d.vertex.ByteBufferBuilder(256));
            GuiGraphics gg = new GuiGraphics(mc, bufSrc);
            PoseStack pose = gg.pose();
            pose.pushPose();
            // renderItem draws a 16×16 icon; scale ×2 so it fills the 32×32 FBO exactly.
            pose.scale(2, 2, 1);
            gg.renderItem(stack, 0, 0);
            gg.flush();
            bufSrc.endBatch();
            pose.popPose();

            // ---- Restore all GL state ----
            Lighting.setupForFlatItems();
            if (scissorWasOn) GL11.glEnable(GL11.GL_SCISSOR_TEST);
            if (!depthWasOn) RenderSystem.disableDepthTest();
            if (!blendWasOn) RenderSystem.disableBlend();
            mvStack.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(savedProjection, VertexSorting.ORTHOGRAPHIC_Z);

            byte[] png = readFboToPng();
            fbo.unbindWrite();

            if (png != null) {
                // Quality gate: if the rendered PNG is essentially blank (no opaque pixels),
                // reject it instead of caching + uploading a blank icon to the server. This is
                // what makes a future renderer regression immediately visible in the log rather
                // than silently filling the dashboard with empty thumbnails.
                int opaque = countOpaquePixels(png);
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

    /**
     * Count pixels with alpha ≥ 8 in a 32×32 RGBA PNG. Used by the render quality gate to reject
     * blank renders before they leak into the cache/upload pipeline. Parses the PNG manually
     * (signature → IHDR → IDAT → inflate → unfilter scanlines) so we don't depend on MC's
     * NativeImage, whose API shifts between versions.
     *
     * <p>Returns -1 if the PNG is malformed (so the caller can still accept the PNG — better to
     * ship a possibly-blank icon than to reject a valid one because our parser is buggy).
     */
    private static int countOpaquePixels(byte[] png) {
        try {
            java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(png);
            // Verify PNG signature.
            byte[] sig = new byte[8];
            if (in.read(sig) != 8) return -1;
            if (sig[0] != (byte) 0x89 || sig[1] != 0x50 || sig[2] != 0x4E || sig[3] != 0x47) return -1;

            // Read chunks until IDAT.
            byte[] idat = null;
            int width = 0, height = 0, bitDepth = 0, colorType = 0;
            while (true) {
                int len = readIntBE(in);
                byte[] type = new byte[4];
                if (in.read(type) != 4) break;
                byte[] data = new byte[len];
                if (in.read(data) != len) return -1;
                readIntBE(in); // CRC, ignore
                String t = new String(type, java.nio.charset.StandardCharsets.US_ASCII);
                if ("IHDR".equals(t)) {
                    width = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16) | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
                    height = ((data[4] & 0xFF) << 24) | ((data[5] & 0xFF) << 16) | ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
                    bitDepth = data[8] & 0xFF;
                    colorType = data[9] & 0xFF;
                } else if ("IDAT".equals(t)) {
                    if (idat == null) idat = data;
                    else {
                        // Multiple IDAT chunks — concatenate.
                        byte[] merged = new byte[idat.length + data.length];
                        System.arraycopy(idat, 0, merged, 0, idat.length);
                        System.arraycopy(data, 0, merged, idat.length, data.length);
                        idat = merged;
                    }
                } else if ("IEND".equals(t)) {
                    break;
                }
            }
            if (idat == null || width != ICON_SIZE || height != ICON_SIZE) return -1;
            if (bitDepth != 8) return -1;
            // colorType 6 = RGBA. We only ever encode RGBA (see pngEncodeRgba).
            if (colorType != 6) return -1;

            // Inflate IDAT.
            java.util.zip.Inflater inf = new java.util.zip.Inflater();
            inf.setInput(idat);
            // Each scanline: 1 filter byte + width * 4 bytes RGBA.
            byte[] raw = new byte[height * (1 + width * 4)];
            int off = 0;
            while (off < raw.length) {
                int n = inf.inflate(raw, off, raw.length - off);
                if (n == 0) break;
                off += n;
            }
            inf.end();
            if (off < raw.length) return -1;

            // Unfilter scanlines (PNG filters: 0=None, 1=Sub, 2=Up, 3=Avg, 4=Paeth).
            int bpp = 4; // bytes per pixel for RGBA8
            int stride = 1 + width * bpp;
            byte[] pixels = new byte[height * width * bpp];
            byte[] prevRow = new byte[width * bpp];
            for (int y = 0; y < height; y++) {
                int rowStart = y * stride;
                int filter = raw[rowStart] & 0xFF;
                byte[] cur = new byte[width * bpp];
                System.arraycopy(raw, rowStart + 1, cur, 0, width * bpp);
                switch (filter) {
                    case 0: break; // None
                    case 1: // Sub
                        for (int x = bpp; x < cur.length; x++) cur[x] += cur[x - bpp];
                        break;
                    case 2: // Up
                        for (int x = 0; x < cur.length; x++) cur[x] += prevRow[x];
                        break;
                    case 3: // Average
                        for (int x = 0; x < cur.length; x++) {
                            int left = x >= bpp ? (cur[x - bpp] & 0xFF) : 0;
                            int up = prevRow[x] & 0xFF;
                            cur[x] += (byte) ((left + up) / 2);
                        }
                        break;
                    case 4: // Paeth
                        for (int x = 0; x < cur.length; x++) {
                            int a = x >= bpp ? (cur[x - bpp] & 0xFF) : 0;
                            int b = prevRow[x] & 0xFF;
                            int c = x >= bpp ? (prevRow[x - bpp] & 0xFF) : 0;
                            int p = a + b - c;
                            int pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
                            int pred = (pa <= pb && pa <= pc) ? a : (pb <= pc) ? b : c;
                            cur[x] += (byte) pred;
                        }
                        break;
                    default: return -1;
                }
                System.arraycopy(cur, 0, pixels, y * width * bpp, cur.length);
                prevRow = cur;
            }

            // Count pixels with alpha ≥ 8 (i.e. not essentially transparent).
            int count = 0;
            for (int i = 3; i < pixels.length; i += 4) {
                if ((pixels[i] & 0xFF) >= 8) count++;
            }
            return count;
        } catch (Exception e) {
            return -1;
        }
    }

    private static int readIntBE(java.io.InputStream in) throws java.io.IOException {
        int a = in.read(), b = in.read(), c = in.read(), d = in.read();
        if ((a | b | c | d) < 0) throw new java.io.EOFException();
        return (a << 24) | (b << 16) | (c << 8) | d;
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
