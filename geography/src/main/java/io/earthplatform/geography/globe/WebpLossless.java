package io.earthplatform.geography.globe;

import java.io.IOException;

/**
 * Decoder for lossless WebP images (the VP8L bitstream of RFC 9649, section 3), written from the
 * specification. Java's image readers have no WebP support, and elevation tiles published as
 * lossless WebP carry exact Terrarium values, so this reads them without a native library.
 *
 * <p>Only still images are read: a plain {@code VP8L} file, or an extended file whose first image
 * chunk is {@code VP8L}. Lossy {@code VP8} data and animation are rejected.
 */
public final class WebpLossless {
    /** Largest side accepted; Earth tiles are 512 pixels. */
    private static final int MAX_SIDE = 4096;
    private static final int[] CODE_LENGTH_ORDER = {17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
    /** Distance codes 1..120 as (x, y) offsets to nearby pixels (RFC 9649 figure 20). */
    private static final int[] DISTANCE_MAP = {
        0, 1, 1, 0, 1, 1, -1, 1, 0, 2, 2, 0, 1, 2, -1, 2, 2, 1, -2, 1, 2, 2, -2, 2, 0, 3, 3, 0,
        1, 3, -1, 3, 3, 1, -3, 1, 2, 3, -2, 3, 3, 2, -3, 2, 0, 4, 4, 0, 1, 4, -1, 4, 4, 1, -4, 1,
        3, 3, -3, 3, 2, 4, -2, 4, 4, 2, -4, 2, 0, 5, 3, 4, -3, 4, 4, 3, -4, 3, 5, 0, 1, 5, -1, 5,
        5, 1, -5, 1, 2, 5, -2, 5, 5, 2, -5, 2, 4, 4, -4, 4, 3, 5, -3, 5, 5, 3, -5, 3, 0, 6, 6, 0,
        1, 6, -1, 6, 6, 1, -6, 1, 2, 6, -2, 6, 6, 2, -6, 2, 4, 5, -4, 5, 5, 4, -5, 4, 3, 6, -3, 6,
        6, 3, -6, 3, 0, 7, 7, 0, 1, 7, -1, 7, 5, 5, -5, 5, 7, 1, -7, 1, 4, 6, -4, 6, 6, 4, -6, 4,
        2, 7, -2, 7, 7, 2, -7, 2, 3, 7, -3, 7, 7, 3, -7, 3, 5, 6, -5, 6, 6, 5, -6, 5, 8, 0, 4, 7,
        -4, 7, 7, 4, -7, 4, 8, 1, 8, 2, 6, 6, -6, 6, 8, 3, 5, 7, -5, 7, 7, 5, -7, 5, 8, 4, 6, 7,
        -6, 7, 7, 6, -7, 6, 8, 5, 7, 7, -7, 7, 8, 6, 8, 7
    };

    /** A decoded image: ARGB pixels, row by row from the top. */
    public record Image(int width, int height, int[] argb) {}

    private WebpLossless() {}

    public static Image decode(byte[] file) throws IOException {
        try {
            int[] chunk = findLosslessChunk(file);
            return new Decoder(file, chunk[0], chunk[1]).image();
        } catch (ArrayIndexOutOfBoundsException | NegativeArraySizeException corrupt) {
            throw new IOException("Corrupt lossless WebP", corrupt);
        }
    }

    /** Offset and length of the VP8L chunk's payload. */
    private static int[] findLosslessChunk(byte[] file) throws IOException {
        if (file.length < 20 || !tag(file, 0, "RIFF") || !tag(file, 8, "WEBP")) throw new IOException("Not a WebP file");
        int end = (int) Math.min(file.length, 8L + littleEndian(file, 4));
        for (int at = 12; at + 8 <= end; ) {
            long size = littleEndian(file, at + 4);
            int payload = at + 8;
            if (size > end - payload) throw new IOException("Truncated WebP chunk");
            if (tag(file, at, "VP8L")) return new int[]{payload, (int) size};
            if (tag(file, at, "VP8 ") || tag(file, at, "ANIM")) throw new IOException("Only lossless still WebP images are supported");
            at = payload + (int) size + (int) (size & 1);
        }
        throw new IOException("WebP file has no lossless image");
    }

    private static boolean tag(byte[] b, int at, String fourCc) {
        for (int i = 0; i < 4; i++) if (b[at + i] != fourCc.charAt(i)) return false;
        return true;
    }

    private static long littleEndian(byte[] b, int at) {
        return (b[at] & 0xFFL) | (b[at + 1] & 0xFFL) << 8 | (b[at + 2] & 0xFFL) << 16 | (b[at + 3] & 0xFFL) << 24;
    }

    /** Reads bits least significant first, as VP8L stores them. */
    private static final class Bits {
        private final byte[] data;
        private final int start, end;
        private int next;
        private long buffer;
        private int count;

        Bits(byte[] data, int offset, int length) { this.data = data; this.start = offset; this.next = offset; this.end = offset + length; }

        private void fill() {
            while (count <= 56) {
                long b = next < end ? data[next] & 0xFF : 0;
                next++;
                buffer |= b << count;
                count += 8;
            }
        }

        int read(int n) throws IOException {
            if (n == 0) return 0;
            if (count < n) fill();
            if ((long) (next - start) * 8 - count + n > (long) (end - start) * 8) throw new IOException("Lossless WebP data ends early");
            int value = (int) (buffer & ((1L << n) - 1));
            buffer >>>= n;
            count -= n;
            return value;
        }

        int peek(int n) {
            if (count < n) fill();
            return (int) (buffer & ((1L << n) - 1));
        }

        void skip(int n) { buffer >>>= n; count -= n; }
    }

    /**
     * A canonical prefix code. Codes up to {@link #FAST} bits long decode with one table lookup
     * indexed by the next bits in stream order; longer ones walk the canonical counts bit by bit.
     */
    private static final class Code {
        private static final int FAST = 9;
        private final int single;
        private final int[] table;
        private final int[] counts = new int[16];
        private final int[] sorted;

        Code(int[] lengths) throws IOException {
            int used = 0, last = -1;
            for (int s = 0; s < lengths.length; s++) if (lengths[s] > 0) { counts[lengths[s]]++; used++; last = s; }
            if (used == 0) { single = 0; table = null; sorted = null; return; }
            if (used == 1) { single = last; table = null; sorted = null; return; }
            int left = 1;
            for (int len = 1; len < 16; len++) {
                left = (left << 1) - counts[len];
                if (left < 0) throw new IOException("Over-subscribed prefix code");
            }
            if (left != 0) throw new IOException("Incomplete prefix code");
            single = -1;
            int[] offsets = new int[16];
            for (int len = 1; len < 15; len++) offsets[len + 1] = offsets[len] + counts[len];
            sorted = new int[used];
            for (int s = 0; s < lengths.length; s++) if (lengths[s] > 0) sorted[offsets[lengths[s]]++] = s;
            table = new int[1 << FAST];
            java.util.Arrays.fill(table, -1);
            int code = 0, index = 0;
            for (int len = 1; len <= FAST; len++) {
                for (int k = 0; k < counts[len]; k++, code++, index++) {
                    int reversed = Integer.reverse(code) >>> (32 - len);
                    for (int fill = reversed; fill < table.length; fill += 1 << len) table[fill] = sorted[index] << 4 | len;
                }
                code <<= 1;
            }
        }

        int read(Bits bits) throws IOException {
            if (single >= 0) return single;
            int entry = table[bits.peek(FAST)];
            if (entry >= 0) { bits.skip(entry & 15); return entry >>> 4; }
            int code = 0, first = 0, index = 0;
            for (int len = 1; len < 16; len++) {
                code |= bits.read(1);
                int count = counts[len];
                if (code - first < count) return sorted[index + code - first];
                index += count;
                first = (first + count) << 1;
                code <<= 1;
            }
            throw new IOException("Bad prefix code in lossless WebP");
        }
    }

    private static final class Decoder {
        private final Bits bits;
        private final int width, height;
        private final int[] transformTypes = new int[4];
        private final int[] transformBits = new int[4];
        private final int[][] transformData = new int[4][];
        private final int[] transformWidths = new int[4];
        private int transforms;

        Decoder(byte[] file, int offset, int length) throws IOException {
            if (length < 5 || (file[offset] & 0xFF) != 0x2F) throw new IOException("Bad lossless WebP signature");
            bits = new Bits(file, offset + 1, length - 1);
            width = bits.read(14) + 1;
            height = bits.read(14) + 1;
            bits.read(1);
            if (bits.read(3) != 0) throw new IOException("Unknown lossless WebP version");
            if (width > MAX_SIDE || height > MAX_SIDE) throw new IOException("Lossless WebP too large: " + width + "x" + height);
        }

        Image image() throws IOException {
            int codedWidth = width;
            boolean[] seen = new boolean[4];
            while (bits.read(1) == 1) {
                int type = bits.read(2);
                if (seen[type]) throw new IOException("Repeated lossless WebP transform");
                seen[type] = true;
                int t = transforms++;
                transformTypes[t] = type;
                transformWidths[t] = codedWidth;
                switch (type) {
                    case 0, 1 -> {
                        int sizeBits = bits.read(3) + 2;
                        transformBits[t] = sizeBits;
                        transformData[t] = entropyImage(divUp(codedWidth, 1 << sizeBits), divUp(height, 1 << sizeBits));
                    }
                    case 2 -> {}
                    default -> {
                        int size = bits.read(8) + 1;
                        int[] palette = entropyImage(size, 1);
                        for (int i = 1; i < size; i++) palette[i] = addPixels(palette[i], palette[i - 1]);
                        int widthBits = size > 16 ? 0 : size > 4 ? 1 : size > 2 ? 2 : 3;
                        transformBits[t] = widthBits;
                        transformData[t] = palette;
                        codedWidth = divUp(codedWidth, 1 << widthBits);
                    }
                }
            }
            int[] pixels = pixels(codedWidth, height, true);
            for (int t = transforms - 1; t >= 0; t--) pixels = inverse(t, pixels);
            return new Image(width, height, pixels);
        }

        /** A subresolution image: no transforms and no meta prefix codes. */
        private int[] entropyImage(int w, int h) throws IOException { return pixels(w, h, false); }

        private int[] pixels(int w, int h, boolean main) throws IOException {
            int cacheBits = 0;
            if (bits.read(1) == 1) {
                cacheBits = bits.read(4);
                if (cacheBits < 1 || cacheBits > 11) throw new IOException("Bad lossless WebP colour cache size");
            }
            int prefixBits = 0, prefixWidth = 0;
            int[] groupsImage = null;
            int groupCount = 1;
            if (main && bits.read(1) == 1) {
                prefixBits = bits.read(3) + 2;
                prefixWidth = divUp(w, 1 << prefixBits);
                groupsImage = entropyImage(prefixWidth, divUp(h, 1 << prefixBits));
                for (int i = 0; i < groupsImage.length; i++) {
                    groupsImage[i] = (groupsImage[i] >> 8) & 0xFFFF;
                    groupCount = Math.max(groupCount, groupsImage[i] + 1);
                }
            }
            int cacheSize = cacheBits == 0 ? 0 : 1 << cacheBits;
            Code[][] groups = new Code[groupCount][];
            int[] alphabets = {256 + 24 + cacheSize, 256, 256, 256, 40};
            for (int g = 0; g < groupCount; g++) {
                groups[g] = new Code[5];
                for (int c = 0; c < 5; c++) groups[g][c] = readCode(alphabets[c]);
            }
            int[] cache = cacheSize == 0 ? null : new int[cacheSize];
            int[] out = new int[w * h];
            int total = out.length;
            Code[] group = groups[0];
            for (int at = 0; at < total; ) {
                int x = at % w, y = at / w;
                if (groupsImage != null) group = groups[groupsImage[(y >> prefixBits) * prefixWidth + (x >> prefixBits)]];
                int s = group[0].read(bits);
                if (s < 256) {
                    int red = group[1].read(bits), blue = group[2].read(bits), alpha = group[3].read(bits);
                    int argb = alpha << 24 | red << 16 | s << 8 | blue;
                    out[at++] = argb;
                    if (cache != null) cache[(0x1e35a7bd * argb) >>> (32 - cacheBits)] = argb;
                } else if (s < 256 + 24) {
                    int length = lz77(s - 256);
                    int distanceCode = lz77(group[4].read(bits));
                    int distance;
                    if (distanceCode > 120) distance = distanceCode - 120;
                    else distance = Math.max(1, DISTANCE_MAP[2 * distanceCode - 2] + DISTANCE_MAP[2 * distanceCode - 1] * w);
                    if (distance > at || length > total - at) throw new IOException("Bad lossless WebP back reference");
                    for (int i = 0; i < length; i++, at++) {
                        int argb = out[at - distance];
                        out[at] = argb;
                        if (cache != null) cache[(0x1e35a7bd * argb) >>> (32 - cacheBits)] = argb;
                    }
                } else {
                    int index = s - 256 - 24;
                    if (cache == null || index >= cacheSize) throw new IOException("Bad lossless WebP colour cache code");
                    int argb = cache[index];
                    out[at++] = argb;
                    cache[(0x1e35a7bd * argb) >>> (32 - cacheBits)] = argb;
                }
            }
            return out;
        }

        private int lz77(int prefix) throws IOException {
            if (prefix < 4) return prefix + 1;
            int extra = (prefix - 2) >> 1;
            int offset = (2 + (prefix & 1)) << extra;
            return offset + bits.read(extra) + 1;
        }

        private Code readCode(int alphabet) throws IOException {
            int[] lengths = new int[alphabet];
            if (bits.read(1) == 1) {
                int symbols = bits.read(1) + 1;
                int first = bits.read(bits.read(1) == 1 ? 8 : 1);
                if (first >= alphabet) throw new IOException("Bad simple prefix code");
                lengths[first] = 1;
                if (symbols == 2) {
                    int second = bits.read(8);
                    if (second >= alphabet) throw new IOException("Bad simple prefix code");
                    lengths[second] = 1;
                }
                // Two lengths of 1 on one symbol (allowed, if wasteful) leave a single leaf.
                return new Code(lengths);
            }
            int[] lengthLengths = new int[19];
            int count = 4 + bits.read(4);
            for (int i = 0; i < count; i++) lengthLengths[CODE_LENGTH_ORDER[i]] = bits.read(3);
            Code lengthCode = new Code(lengthLengths);
            int maxSymbol = alphabet;
            if (bits.read(1) == 1) {
                int lengthBits = 2 + 2 * bits.read(3);
                maxSymbol = 2 + bits.read(lengthBits);
                if (maxSymbol > alphabet) throw new IOException("Bad prefix code length count");
            }
            int previous = 8;
            for (int symbol = 0; symbol < alphabet; ) {
                if (maxSymbol-- == 0) break;
                int token = lengthCode.read(bits);
                if (token < 16) {
                    lengths[symbol++] = token;
                    if (token != 0) previous = token;
                    continue;
                }
                int repeat, value;
                if (token == 16) { repeat = 3 + bits.read(2); value = previous; }
                else if (token == 17) { repeat = 3 + bits.read(3); value = 0; }
                else { repeat = 11 + bits.read(7); value = 0; }
                if (symbol + repeat > alphabet) throw new IOException("Prefix code lengths overrun");
                for (int i = 0; i < repeat; i++) lengths[symbol++] = value;
            }
            return new Code(lengths);
        }

        private int[] inverse(int t, int[] in) throws IOException {
            int w = transformWidths[t];
            int[] data = transformData[t];
            int sizeBits = transformBits[t];
            switch (transformTypes[t]) {
                case 0 -> {
                    int blocksAcross = divUp(w, 1 << sizeBits);
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < w; x++) {
                            int at = y * w + x;
                            int predicted;
                            if (y == 0) predicted = x == 0 ? 0xFF000000 : in[at - 1];
                            else if (x == 0) predicted = in[at - w];
                            else {
                                int mode = (data[(y >> sizeBits) * blocksAcross + (x >> sizeBits)] >> 8) & 0xF;
                                predicted = predict(mode, in[at - 1], in[at - w], in[at - w - 1], in[at - w + 1]);
                            }
                            in[at] = addPixels(in[at], predicted);
                        }
                    }
                    return in;
                }
                case 1 -> {
                    int blocksAcross = divUp(w, 1 << sizeBits);
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < w; x++) {
                            int element = data[(y >> sizeBits) * blocksAcross + (x >> sizeBits)];
                            int greenToRed = (byte) element, greenToBlue = (byte) (element >> 8), redToBlue = (byte) (element >> 16);
                            int at = y * w + x, p = in[at];
                            int green = (byte) (p >> 8);
                            int red = (p >> 16) + ((greenToRed * green) >> 5);
                            int blue = p + ((greenToBlue * green) >> 5) + ((redToBlue * (byte) red) >> 5);
                            in[at] = (p & 0xFF00FF00) | (red & 0xFF) << 16 | (blue & 0xFF);
                        }
                    }
                    return in;
                }
                case 2 -> {
                    for (int i = 0; i < in.length; i++) {
                        int p = in[i], green = (p >> 8) & 0xFF;
                        in[i] = (p & 0xFF00FF00) | ((((p >> 16) & 0xFF) + green) & 0xFF) << 16 | (((p & 0xFF) + green) & 0xFF);
                    }
                    return in;
                }
                default -> {
                    // Colour indexing; sizeBits holds width_bits, and w the width before bundling.
                    int packedWidth = divUp(w, 1 << sizeBits);
                    int perPixel = 8 >> sizeBits, mask = (1 << perPixel) - 1;
                    int[] out = new int[w * height];
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < w; x++) {
                            int green = (in[y * packedWidth + (x >> sizeBits)] >> 8) & 0xFF;
                            int index = (green >> ((x & ((1 << sizeBits) - 1)) * perPixel)) & mask;
                            out[y * w + x] = index < data.length ? data[index] : 0;
                        }
                    }
                    return out;
                }
            }
        }
    }

    private static int predict(int mode, int l, int t, int tl, int tr) {
        return switch (mode) {
            case 0 -> 0xFF000000;
            case 1 -> l;
            case 2 -> t;
            case 3 -> tr;
            case 4 -> tl;
            case 5 -> average(average(l, tr), t);
            case 6 -> average(l, tl);
            case 7 -> average(l, t);
            case 8 -> average(tl, t);
            case 9 -> average(t, tr);
            case 10 -> average(average(l, tl), average(t, tr));
            case 11 -> select(l, t, tl);
            case 12 -> clampFull(l, t, tl);
            case 13 -> clampHalf(average(l, t), tl);
            default -> 0xFF000000; // modes 14 and 15 are unused; treat them as mode 0
        };
    }

    private static int average(int a, int b) {
        // Per-channel floor((a + b) / 2) without carries between channels.
        return (((a ^ b) & 0xFEFEFEFE) >>> 1) + (a & b);
    }

    private static int select(int l, int t, int tl) {
        int pl = 0, pt = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int cl = (l >>> shift) & 0xFF, ct = (t >>> shift) & 0xFF, ctl = (tl >>> shift) & 0xFF;
            int p = cl + ct - ctl;
            pl += Math.abs(p - cl);
            pt += Math.abs(p - ct);
        }
        return pl < pt ? l : t;
    }

    private static int clampFull(int a, int b, int c) {
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8)
            out |= clamp(((a >>> shift) & 0xFF) + ((b >>> shift) & 0xFF) - ((c >>> shift) & 0xFF)) << shift;
        return out;
    }

    private static int clampHalf(int a, int b) {
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int ca = (a >>> shift) & 0xFF, cb = (b >>> shift) & 0xFF;
            out |= clamp(ca + (ca - cb) / 2) << shift;
        }
        return out;
    }

    private static int clamp(int v) { return v < 0 ? 0 : Math.min(v, 255); }

    private static int addPixels(int a, int b) {
        return (((a & 0xFF00FF00) + (b & 0xFF00FF00)) & 0xFF00FF00) | (((a & 0x00FF00FF) + (b & 0x00FF00FF)) & 0x00FF00FF);
    }

    private static int divUp(int num, int den) { return (num + den - 1) / den; }
}
