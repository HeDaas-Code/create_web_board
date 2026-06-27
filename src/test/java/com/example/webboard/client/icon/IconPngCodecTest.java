package com.example.webboard.client.icon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IconPngCodecTest — verifies the pure-Java PNG encode/decode used by the item-icon renderer's
 * quality gate, WITHOUT requiring a Minecraft client / GL context (which CI can't provide).
 *
 * <p>What this guards against:
 * <ul>
 *   <li><b>Blank-icon regression</b> (the v0.6.0 bug): if the renderer's GL state setup is wrong,
 *       it produces a fully-transparent FBO. {@code countOpaquePixels} must return 0 for that,
 *       so the quality gate in {@code ItemIconRenderer.render} rejects it instead of caching +
 *       uploading a blank icon to the server.</li>
 *   <li><b>Round-trip fidelity</b>: bytes encoded by {@code encodeRgba} must decode back to the
 *       exact same RGBA array, and must also be readable by the JDK's standard {@code ImageIO}.
 *       If the PNG encoder ever produces a malformed PNG, ImageIO will throw — catching
 *       regressions that our own decoder (which only handles filter 0) might miss.</li>
 *   <li><b>Filter robustness</b>: {@code countOpaquePixels} must handle all 5 PNG filter types
 *       (None/Sub/Up/Avg/Paeth), because ImageIO uses adaptive filtering and our decoder must
 *       still parse ImageIO-produced PNGs correctly (this is how we'd detect "the icon the
 *       renderer uploaded is actually fine, but our quality gate mis-rejected it").</li>
 * </ul>
 *
 * <p>What this does NOT cover: the GL rendering itself (FBO bind, projection matrix, modelview
 * matrix, Lighting, depth/blend) — that requires a live MC client and is verified in the field
 * via the quality-gate warning log ("rendered icon for X is blank ..."). If you see that log
 * line after upgrading, the GL state setup regressed; this test won't catch it, but the log will.
 */
class IconPngCodecTest {

    private static final int W = IconPngCodecTestConstants.W;
    private static final int H = IconPngCodecTestConstants.H;

    @Test
    @DisplayName("encodeRgba produces a PNG readable by JDK ImageIO (signature/IHDR/IDAT valid)")
    void encode_isValidPng_readableByImageIO() throws IOException {
        byte[] rgba = solidRed();
        byte[] png = IconPngCodec.encodeRgba(W, H, rgba);

        // JDK's ImageIO is the gold standard for "is this a valid PNG". If our encoder produces
        // anything malformed (wrong CRC, bad IHDR, truncated IDAT), ImageIO.read throws.
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(img, "ImageIO.read returned null — PNG is malformed");
        assertEquals(W, img.getWidth());
        assertEquals(H, img.getHeight());
        // Top-left pixel should be opaque red (R=255,G=0,B=0,A=255).
        int argb = img.getRGB(0, 0);
        assertEquals(255, (argb >> 16) & 0xFF, "red channel");
        assertEquals(0,   (argb >> 8)  & 0xFF, "green channel");
        assertEquals(0,   argb         & 0xFF, "blue channel");
        assertEquals(255, (argb >> 24) & 0xFF, "alpha channel — must be opaque");
    }

    @Test
    @DisplayName("decodeRgba(encodeRgba(x)) == x — round-trip preserves every byte")
    void roundTrip_preservesEveryPixel() throws IOException {
        // Build a non-trivial pattern: each pixel a different color so a byte-offset bug
        // (e.g. wrong stride, flipped axis) would scramble the comparison.
        byte[] original = gradient();
        byte[] png = IconPngCodec.encodeRgba(W, H, original);
        byte[] decoded = IconPngCodec.decodeRgba(png, W, H);
        assertNotNull(decoded, "decode returned null for a PNG we just encoded");
        assertArrayEquals(original, decoded, "round-trip must preserve every RGBA byte");
    }

    @Test
    @DisplayName("countOpaquePixels on a fully-opaque 32×32 returns 1024")
    void countOpaquePixels_fullyOpaque_returns1024() throws IOException {
        byte[] png = IconPngCodec.encodeRgba(W, H, solidRed());
        int n = IconPngCodec.countOpaquePixels(png, W, H);
        assertEquals(W * H, n, "every pixel of solidRed is opaque (alpha=255 ≥ 8)");
    }

    @Test
    @DisplayName("countOpaquePixels on a fully-transparent 32×32 returns 0 — the blank-icon guard")
    void countOpaquePixels_fullyTransparent_returns0() throws IOException {
        // This is the v0.6.0 bug scenario: renderer produced a fully-transparent FBO.
        // countOpaquePixels MUST return 0 so the quality gate rejects it.
        byte[] allTransparent = new byte[W * H * 4]; // all zeros = alpha 0 everywhere
        byte[] png = IconPngCodec.encodeRgba(W, H, allTransparent);
        int n = IconPngCodec.countOpaquePixels(png, W, H);
        assertEquals(0, n, "fully-transparent PNG must count as 0 opaque pixels");
    }

    @Test
    @DisplayName("countOpaquePixels counts only pixels above the alpha threshold (8)")
    void countOpaquePixels_thresholdBoundary() throws IOException {
        // Half the pixels at alpha=7 (below threshold), half at alpha=8 (at threshold).
        byte[] rgba = new byte[W * H * 4];
        for (int i = 0; i < W * H; i++) {
            int alpha = (i % 2 == 0) ? 7 : 8;
            rgba[i * 4 + 3] = (byte) alpha;
        }
        byte[] png = IconPngCodec.encodeRgba(W, H, rgba);
        int n = IconPngCodec.countOpaquePixels(png, W, H);
        assertEquals(W * H / 2, n, "only alpha>=8 pixels should count");
    }

    @Test
    @DisplayName("countOpaquePixels on a sparse pattern (8 pixels) returns exactly 8")
    void countOpaquePixels_sparsePattern() throws IOException {
        // Simulates a thin item (e.g. a stick diagonal) — exactly MIN_OPAQUE_PIXELS visible.
        // Verifies the quality gate's floor of 8 doesn't false-reject legitimately sparse items.
        byte[] rgba = new byte[W * H * 4];
        for (int i = 0; i < 8; i++) {
            rgba[i * 4 + 3] = (byte) 255; // 8 opaque pixels at the start
        }
        byte[] png = IconPngCodec.encodeRgba(W, H, rgba);
        int n = IconPngCodec.countOpaquePixels(png, W, H);
        assertEquals(8, n);
    }

    @Test
    @DisplayName("countOpaquePixels on a PNG encoded by JDK ImageIO (adaptive filters) still works")
    void countOpaquePixels_handlesAdaptiveFilteredPng() throws IOException {
        // Our encoder only writes filter type 0 (None), but ImageIO uses adaptive filtering
        // (picks Sub/Up/Avg/Paeth per-row for better compression). Our decoder must handle ALL
        // 5 filter types — otherwise if someone hand-edits an icon or uploads one from another
        // tool, the quality gate would false-reject it.
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                // Diagonal stripe pattern — exercises Sub/Up/Paeth filters.
                int alpha = ((x + y) % 4 == 0) ? 255 : 0;
                img.setRGB(x, y, (alpha << 24) | 0x00FF0000); // opaque red on diagonal
            }
        }
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        ImageIO.write(img, "PNG", baos);
        byte[] imageioPng = baos.toByteArray();

        int n = IconPngCodec.countOpaquePixels(imageioPng, W, H);
        assertTrue(n > 0, "ImageIO-encoded PNG must be parseable; got " + n);
        // Diagonal with step 4 in a 32×32 grid: roughly 32*32/4 = 256 pixels, but the exact
        // count depends on the pattern. Just assert it's substantial and matches a direct count.
        int expected = 0;
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++)
                if ((x + y) % 4 == 0) expected++;
        assertEquals(expected, n, "decoder must unfilter ImageIO's adaptive filters correctly");
    }

    @Test
    @DisplayName("countOpaquePixels returns -1 (not 0) for malformed PNG — so gate doesn't false-reject")
    void countOpaquePixels_malformedPng_returnsMinus1() {
        // Garbage bytes — not a real PNG. The gate treats -1 as "don't reject" (better to ship
        // a possibly-blank icon than to reject a valid one because OUR parser is buggy).
        byte[] garbage = "this is not a png".getBytes();
        int n = IconPngCodec.countOpaquePixels(garbage, W, H);
        assertEquals(-1, n);
    }

    @Test
    @DisplayName("countOpaquePixels returns -1 for wrong dimensions — prevents mis-counting resized icons")
    void countOpaquePixels_wrongDimensions_returnsMinus1() throws IOException {
        byte[] png = IconPngCodec.encodeRgba(W, H, solidRed());
        // Ask for 16×16 from a 32×32 PNG — should refuse rather than miscount.
        int n = IconPngCodec.countOpaquePixels(png, 16, 16);
        assertEquals(-1, n);
    }

    @Test
    @DisplayName("encodeRgba + countOpaquePixels is stable across multiple encode calls (no shared state)")
    void encodeAndCount_isStateless() throws IOException {
        byte[] a = solidRed();
        byte[] b = solidBlue();
        byte[] pngA1 = IconPngCodec.encodeRgba(W, H, a);
        byte[] pngB = IconPngCodec.encodeRgba(W, H, b);
        byte[] pngA2 = IconPngCodec.encodeRgba(W, H, a);
        // Re-encoding the same input must produce identical output (deflater is deterministic).
        assertArrayEquals(pngA1, pngA2, "encoder must be deterministic");
        // And the counts must match the inputs.
        assertEquals(W * H, IconPngCodec.countOpaquePixels(pngA1, W, H));
        assertEquals(W * H, IconPngCodec.countOpaquePixels(pngB, W, H));
    }

    // ---------- fixture builders ----------

    /** 32×32 of solid opaque red (R=255,G=0,B=0,A=255). */
    private static byte[] solidRed() {
        byte[] rgba = new byte[W * H * 4];
        for (int i = 0; i < W * H; i++) {
            rgba[i * 4]     = (byte) 255; // R
            rgba[i * 4 + 1] = 0;          // G
            rgba[i * 4 + 2] = 0;          // B
            rgba[i * 4 + 3] = (byte) 255; // A
        }
        return rgba;
    }

    /** 32×32 of solid opaque blue. */
    private static byte[] solidBlue() {
        byte[] rgba = new byte[W * H * 4];
        for (int i = 0; i < W * H; i++) {
            rgba[i * 4]     = 0;
            rgba[i * 4 + 1] = 0;
            rgba[i * 4 + 2] = (byte) 255;
            rgba[i * 4 + 3] = (byte) 255;
        }
        return rgba;
    }

    /** 32×32 where each pixel has a unique (R,G,B,A) — catches stride/offset bugs in round-trip. */
    private static byte[] gradient() {
        byte[] rgba = new byte[W * H * 4];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int i = (y * W + x) * 4;
                rgba[i]     = (byte) x;          // R = x
                rgba[i + 1] = (byte) y;          // G = y
                rgba[i + 2] = (byte) (x ^ y);    // B = x^y
                rgba[i + 3] = (byte) (255 - x);  // A = 255-x (some transparent, some opaque)
            }
        }
        return rgba;
    }
}

/** Indirection so the constant lives next to the renderer's ICON_SIZE without importing MC classes. */
class IconPngCodecTestConstants {
    static final int W = 32;
    static final int H = 32;
}
