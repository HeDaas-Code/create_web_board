package com.example.webboard.client.icon;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * IconPngCodec — pure-Java PNG encode/decode for 32×32 RGBA item icons.
 *
 * <p>Extracted from {@link ItemIconRenderer} so the PNG logic can be unit-tested without loading
 * any Minecraft client classes (which require a GL context). The renderer delegates all PNG
 * work here; this class has zero MC dependencies.
 *
 * <p><b>Encode</b>: {@link #encodeRgba(int, int, byte[])} writes a minimal PNG (signature + IHDR
 * + IDAT + IEND). IDAT is zlib-deflated; each scanline uses filter type 0 (None) for simplicity
 * — the encoder is the only producer, so we don't need the compression gains of adaptive filtering.
 *
 * <p><b>Decode + opaque-pixel count</b>: {@link #countOpaquePixels(byte[], int, int)} parses the
 * PNG back, inflates IDAT, reverses all 5 PNG filter types, and counts pixels with alpha ≥ 8.
 * Used by the render quality gate to reject blank renders before they pollute the cache.
 *
 * <p>No external deps — keeps the jar slim and avoids MC's NativeImage (whose API shifts between
 * versions). Verified round-trip: bytes encoded by {@link #encodeRgba} decode back to the same
 * pixel array (see {@code IconPngCodecTest}).
 */
public final class IconPngCodec {

    private IconPngCodec() {}

    /** Alpha threshold for "this pixel counts as visible". 8/255 ≈ 3% — generous to anti-aliased edges. */
    public static final int ALPHA_THRESHOLD = 8;

    /**
     * Encode an RGBA byte array (length = width × height × 4, row-major top-down) as a PNG.
     * Color type 6 (RGBA), 8-bit depth, no interlace.
     */
    public static byte[] encodeRgba(int width, int height, byte[] rgba) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
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
        ByteArrayOutputStream scanlines = new ByteArrayOutputStream();
        for (int y = 0; y < height; y++) {
            scanlines.write(0); // filter type 0 (None)
            scanlines.write(rgba, y * width * 4, width * 4);
        }
        Deflater def = new Deflater(Deflater.BEST_COMPRESSION);
        def.setInput(scanlines.toByteArray());
        def.finish();
        byte[] buf = new byte[8192];
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        while (!def.finished()) {
            int n = def.deflate(buf);
            compressed.write(buf, 0, n);
        }
        writeChunk(raw, "IDAT", compressed.toByteArray());

        // IEND
        writeChunk(raw, "IEND", new byte[0]);
        return raw.toByteArray();
    }

    /**
     * Count pixels with alpha ≥ {@link #ALPHA_THRESHOLD} in a PNG of the expected dimensions.
     *
     * @param png           PNG bytes (must be color type 6 RGBA, 8-bit)
     * @param expectWidth   expected width; returns -1 if mismatched (caller treats -1 as "don't reject")
     * @param expectHeight  expected height
     * @return opaque-pixel count, or -1 if the PNG is malformed (so the caller doesn't false-reject)
     */
    public static int countOpaquePixels(byte[] png, int expectWidth, int expectHeight) {
        try {
            ByteArrayInputStream in = new ByteArrayInputStream(png);
            byte[] sig = new byte[8];
            if (in.read(sig) != 8) return -1;
            if (sig[0] != (byte) 0x89 || sig[1] != 0x50 || sig[2] != 0x4E || sig[3] != 0x47) return -1;

            byte[] idat = null;
            int width = 0, height = 0, bitDepth = 0, colorType = 0;
            while (true) {
                int len = readIntBE(in);
                byte[] type = new byte[4];
                if (in.read(type) != 4) break;
                byte[] data = new byte[len];
                if (in.read(data) != len) return -1;
                readIntBE(in); // CRC, ignore
                String t = new String(type, StandardCharsets.US_ASCII);
                if ("IHDR".equals(t)) {
                    width = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16) | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
                    height = ((data[4] & 0xFF) << 24) | ((data[5] & 0xFF) << 16) | ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
                    bitDepth = data[8] & 0xFF;
                    colorType = data[9] & 0xFF;
                } else if ("IDAT".equals(t)) {
                    if (idat == null) idat = data;
                    else {
                        byte[] merged = new byte[idat.length + data.length];
                        System.arraycopy(idat, 0, merged, 0, idat.length);
                        System.arraycopy(data, 0, merged, idat.length, data.length);
                        idat = merged;
                    }
                } else if ("IEND".equals(t)) {
                    break;
                }
            }
            if (idat == null || width != expectWidth || height != expectHeight) return -1;
            if (bitDepth != 8) return -1;
            if (colorType != 6) return -1; // RGBA only

            byte[] pixels = inflateAndUnfilter(idat, width, height, 4);
            if (pixels == null) return -1;

            int count = 0;
            for (int i = 3; i < pixels.length; i += 4) {
                if ((pixels[i] & 0xFF) >= ALPHA_THRESHOLD) count++;
            }
            return count;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Fully decode a 32×32 RGBA PNG back to its raw RGBA pixel array (row-major top-down).
     * Returns null if malformed. Used by tests to verify round-trip fidelity.
     */
    public static byte[] decodeRgba(byte[] png, int expectWidth, int expectHeight) {
        try {
            ByteArrayInputStream in = new ByteArrayInputStream(png);
            byte[] sig = new byte[8];
            if (in.read(sig) != 8) return null;
            if (sig[0] != (byte) 0x89 || sig[1] != 0x50 || sig[2] != 0x4E || sig[3] != 0x47) return null;

            byte[] idat = null;
            int width = 0, height = 0, bitDepth = 0, colorType = 0;
            while (true) {
                int len = readIntBE(in);
                byte[] type = new byte[4];
                if (in.read(type) != 4) break;
                byte[] data = new byte[len];
                if (in.read(data) != len) return null;
                readIntBE(in);
                String t = new String(type, StandardCharsets.US_ASCII);
                if ("IHDR".equals(t)) {
                    width = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16) | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
                    height = ((data[4] & 0xFF) << 24) | ((data[5] & 0xFF) << 16) | ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
                    bitDepth = data[8] & 0xFF;
                    colorType = data[9] & 0xFF;
                } else if ("IDAT".equals(t)) {
                    if (idat == null) idat = data;
                    else {
                        byte[] merged = new byte[idat.length + data.length];
                        System.arraycopy(idat, 0, merged, 0, idat.length);
                        System.arraycopy(data, 0, merged, idat.length, data.length);
                        idat = merged;
                    }
                } else if ("IEND".equals(t)) {
                    break;
                }
            }
            if (idat == null || width != expectWidth || height != expectHeight) return null;
            if (bitDepth != 8 || colorType != 6) return null;
            return inflateAndUnfilter(idat, width, height, 4);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Inflate IDAT and reverse PNG scanline filters (0=None, 1=Sub, 2=Up, 3=Avg, 4=Paeth).
     * Returns width*height*bpp bytes, row-major top-down. Returns null on failure.
     */
    private static byte[] inflateAndUnfilter(byte[] idat, int width, int height, int bpp) {
        try {
            Inflater inf = new Inflater();
            inf.setInput(idat);
            int stride = 1 + width * bpp;
            byte[] raw = new byte[height * stride];
            int off = 0;
            while (off < raw.length) {
                int n = inf.inflate(raw, off, raw.length - off);
                if (n == 0) break;
                off += n;
            }
            inf.end();
            if (off < raw.length) return null;

            byte[] pixels = new byte[height * width * bpp];
            byte[] prevRow = new byte[width * bpp];
            for (int y = 0; y < height; y++) {
                int rowStart = y * stride;
                int filter = raw[rowStart] & 0xFF;
                byte[] cur = new byte[width * bpp];
                System.arraycopy(raw, rowStart + 1, cur, 0, width * bpp);
                switch (filter) {
                    case 0: break;
                    case 1:
                        for (int x = bpp; x < cur.length; x++) cur[x] += cur[x - bpp];
                        break;
                    case 2:
                        for (int x = 0; x < cur.length; x++) cur[x] += prevRow[x];
                        break;
                    case 3:
                        for (int x = 0; x < cur.length; x++) {
                            int left = x >= bpp ? (cur[x - bpp] & 0xFF) : 0;
                            int up = prevRow[x] & 0xFF;
                            cur[x] += (byte) ((left + up) / 2);
                        }
                        break;
                    case 4:
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
                    default: return null;
                }
                System.arraycopy(cur, 0, pixels, y * width * bpp, cur.length);
                prevRow = cur;
            }
            return pixels;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeChunk(OutputStream out, String type, byte[] data) throws IOException {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        byte[] len = new byte[4];
        putInt(len, 0, data.length);
        out.write(len);
        out.write(typeBytes);
        out.write(data);
        CRC32 crc = new CRC32();
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

    private static int readIntBE(InputStream in) throws IOException {
        int a = in.read(), b = in.read(), c = in.read(), d = in.read();
        if ((a | b | c | d) < 0) throw new EOFException();
        return (a << 24) | (b << 16) | (c << 8) | d;
    }
}
