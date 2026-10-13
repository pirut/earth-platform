package io.earthplatform.geography.globe;

import io.earthplatform.geography.MissingTerrainException;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * The Reference Elevation Model of Antarctica (REMA) v2.0 mosaic, read straight from the Polar
 * Geospatial Center's public AWS Open Data bucket: heights in metres above the WGS 84 ellipsoid on
 * Antarctic Polar Stereographic (EPSG:3031) metres.
 *
 * <p>Two products are read. {@link #FINE} is the 2 m mosaic, one cloud-optimised GeoTIFF per 50 km
 * square, named {@code 2m/RR_CC/RR_CC_SR_SC_2m_v2.0_dem.tif}; {@link #COARSE} is the 32 m mosaic,
 * one file per 100 km square, {@code 32m/RR_CC/RR_CC_32m_v2.0_dem.tif}. Squares count from
 * (-3100 km, -3100 km); {@code SR}/{@code SC} pick the north/east half when 2. Each file overlaps its
 * neighbours by about 100 m, holds halving overviews and 512-pixel blocks of LZW-compressed floats
 * with the floating-point predictor. Squares with no land have no file.
 *
 * <p>Only a file's header and the blocks a caller touches are fetched, with HTTP range requests, and
 * both are kept in an optional disk cache, so a world regenerates from the same bytes offline. Loads
 * are single-flight per block and bounded in concurrency and memory. Callers block.
 */
public final class RemaSource implements AutoCloseable {
    public static final String DATASET_ID = "rema-v2.0";
    public static final URI AWS_REMA = URI.create("https://pgc-opendata-dems.s3.us-west-2.amazonaws.com/rema/mosaics/v2.0/");
    public static final int FINE = 0, COARSE = 1;
    /** Metres per pixel of each product at full resolution. */
    static final double[] RESOLUTION = {2, 32};
    /** Overviews each product holds below full resolution. */
    static final int[] MAX_OVERVIEW = {6, 4};
    private static final double ORIGIN = -3_100_000, SQUARE = 100_000;
    private static final float NO_DATA = -9999f;
    private static final int BLOCK = 512, HEADER_BYTES = 65536, MAX_HEADER_BYTES = 1 << 20, MAX_BLOCK_BYTES = 4 << 20;

    private final URI base;
    private final Path cacheRoot;
    private final boolean allowNetwork;
    private final int maxBlocksInMemory;
    private final RangeFiles files;
    private final Map<Integer, CompletableFuture<Header>> headers = new ConcurrentHashMap<>();
    private final LinkedHashMap<Long, float[]> memory = new LinkedHashMap<>(32, 0.75f, true);
    private final Map<Long, CompletableFuture<float[]>> inFlight = new ConcurrentHashMap<>();
    /** Each thread's last few blocks, so reads that stay in them skip the shared cache. */
    private final ThreadLocal<Recent> recent = ThreadLocal.withInitial(Recent::new);

    private static final class Recent {
        final long[] keys = {-1, -1, -1, -1};
        final float[][] blocks = new float[4][];
        int next;
    }

    /**
     * @param base              URI prefix the products are named under
     * @param cacheRoot         optional disk cache directory, or null for memory only
     * @param allowNetwork      false reads only the disk cache
     * @param maxBlocksInMemory decoded blocks kept (1 MiB each)
     */
    public RemaSource(URI base, Path cacheRoot, boolean allowNetwork, int maxBlocksInMemory, int maxConcurrentTransfers, Duration timeout) throws IOException {
        this.base = Objects.requireNonNull(base);
        if (!base.toString().endsWith("/")) throw new IllegalArgumentException("REMA base URI must end with /");
        if (maxBlocksInMemory < 1 || maxConcurrentTransfers < 1 || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Invalid REMA source budget");
        if (cacheRoot != null) Files.createDirectories(cacheRoot);
        this.cacheRoot = cacheRoot;
        this.allowNetwork = allowNetwork;
        this.maxBlocksInMemory = maxBlocksInMemory;
        this.files = new RangeFiles("REMA elevation", DATASET_ID, "earth-rema-io", maxConcurrentTransfers, timeout);
    }

    /**
     * Height above the ellipsoid at polar stereographic {@code x, y}, bilinear over the valid pixels
     * of a product's overview (0 for full resolution); NaN where there is no data.
     */
    public double metres(int product, int overview, double x, double y) {
        Grid grid = grid(product, overview, x, y);
        if (grid == null) return Double.NaN;
        double fx = (x - grid.x0) / grid.pixel - 0.5, fy = (grid.y0 - y) / grid.pixel - 0.5;
        int i = (int) Math.floor(fx), j = (int) Math.floor(fy);
        double tx = fx - i, ty = fy - j, sum = 0, weights = 0;
        for (int dj = 0; dj <= 1; dj++) for (int di = 0; di <= 1; di++) {
            double w = (di == 0 ? 1 - tx : tx) * (dj == 0 ? 1 - ty : ty);
            if (w == 0) continue;
            float v = pixel(grid, i + di, j + dj);
            if (v == NO_DATA || !Float.isFinite(v)) continue;
            sum += w * v;
            weights += w;
        }
        return weights > 1e-9 ? sum / weights : Double.NaN;
    }

    /**
     * The share of a product's overview that holds data around {@code x, y}, bilinear between pixel
     * centres: 1 inside the data, 0 outside, a ramp one pixel wide at its edge.
     */
    public double coverage(int product, int overview, double x, double y) {
        Grid grid = grid(product, overview, x, y);
        if (grid == null) return 0;
        double fx = (x - grid.x0) / grid.pixel - 0.5, fy = (grid.y0 - y) / grid.pixel - 0.5;
        int i = (int) Math.floor(fx), j = (int) Math.floor(fy);
        double tx = fx - i, ty = fy - j, share = 0;
        for (int dj = 0; dj <= 1; dj++) for (int di = 0; di <= 1; di++) {
            float v = pixel(grid, i + di, j + dj);
            if (v != NO_DATA && Float.isFinite(v)) share += (di == 0 ? 1 - tx : tx) * (dj == 0 ? 1 - ty : ty);
        }
        return share;
    }

    // Files and blocks -----------------------------------------------------------------------------

    /** One overview of one file: where its pixels lie and where each block is. */
    private record Level(int width, int height, long[] offsets, long[] counts) {}
    private record Header(Level[] levels, double x0, double y0, double pixel, boolean bigEndian) {
        static final Header MISSING = new Header(new Level[0], 0, 0, 0, false);
    }
    /** An overview of the file that holds a point. */
    private record Grid(int file, int overview, Header header, Level level, double x0, double y0, double pixel) {}

    /** The overview of the file whose own square holds {@code x, y}, or null where there is none. */
    private Grid grid(int product, int overview, double x, double y) {
        if (product != FINE && product != COARSE) throw new IllegalArgumentException("Unknown REMA product " + product);
        if (overview < 0 || overview > MAX_OVERVIEW[product]) throw new IllegalArgumentException("Unsupported REMA overview " + overview);
        if (!Double.isFinite(x) || !Double.isFinite(y)) return null;
        int column = (int) Math.floor((x - ORIGIN) / SQUARE), row = (int) Math.floor((y - ORIGIN) / SQUARE);
        if (column < 1 || row < 1 || column > 61 || row > 61) return null;
        int east = 0, north = 0;
        if (product == FINE) {
            east = x - ORIGIN - column * SQUARE >= SQUARE / 2 ? 1 : 0;
            north = y - ORIGIN - row * SQUARE >= SQUARE / 2 ? 1 : 0;
        }
        int file = fileKey(product, row, column, north, east);
        Header header = header(file);
        if (header.levels.length == 0) return null;
        // A small file holds fewer overviews; its coarsest stands in for the rest.
        overview = Math.min(overview, header.levels.length - 1);
        Level level = header.levels[overview];
        // Overviews are rounded up, so their pixels span the full image a little unevenly; GDAL
        // places them by the ratio of sizes.
        double pixel = header.pixel * header.levels[0].width / level.width;
        double right = header.x0 + header.pixel * header.levels[0].width, bottom = header.y0 - header.pixel * header.levels[0].height;
        if (x < header.x0 || x >= right || y > header.y0 || y <= bottom) return null;
        return new Grid(file, overview, header, level, header.x0, header.y0, pixel);
    }

    private float pixel(Grid grid, int column, int row) {
        Level level = grid.level;
        column = Math.max(0, Math.min(level.width - 1, column));
        row = Math.max(0, Math.min(level.height - 1, row));
        int across = Math.ceilDiv(level.width, BLOCK);
        int index = (row / BLOCK) * across + column / BLOCK;
        long key = (long) grid.file << 24 | (long) grid.overview << 20 | index;
        Recent cache = recent.get();
        float[] block = null;
        for (int k = 0; k < 4; k++) if (cache.keys[k] == key) { block = cache.blocks[k]; break; }
        if (block == null) {
            block = block(grid, index, key);
            cache.keys[cache.next] = key;
            cache.blocks[cache.next] = block;
            cache.next = (cache.next + 1) & 3;
        }
        return block[(row % BLOCK) * BLOCK + column % BLOCK];
    }

    private static int fileKey(int product, int row, int column, int north, int east) {
        return ((product * 64 + row) * 64 + column) * 4 + north * 2 + east;
    }

    static String name(int file) {
        int half = file & 3, column = (file >> 2) & 63, row = (file >> 8) & 63, product = file >> 14;
        String square = String.format(Locale.ROOT, "%02d_%02d", row, column);
        return product == FINE
                ? "2m/" + square + "/" + square + "_" + ((half >> 1) + 1) + "_" + ((half & 1) + 1) + "_2m_v2.0_dem.tif"
                : "32m/" + square + "/" + square + "_32m_v2.0_dem.tif";
    }

    private float[] block(Grid grid, int index, long key) {
        synchronized (memory) {
            float[] hit = memory.get(key);
            if (hit != null) return hit;
        }
        float[] decoded = files.singleFlight(inFlight, key, () -> readBlock(grid, index), "REMA block");
        synchronized (memory) {
            memory.put(key, decoded);
            var iterator = memory.entrySet().iterator();
            while (memory.size() > maxBlocksInMemory && iterator.hasNext()) { iterator.next(); iterator.remove(); }
        }
        return decoded;
    }

    private Header header(int file) {
        CompletableFuture<Header> mine = new CompletableFuture<>(), shared = headers.putIfAbsent(file, mine);
        if (shared == null) {
            try { mine.complete(readHeader(file)); }
            catch (Throwable failure) {
                files.failures.incrementAndGet();
                mine.completeExceptionally(failure);
                // A failed header is retried by the next caller.
                headers.remove(file, mine);
            }
            shared = mine;
        }
        return files.await(shared, "REMA header " + name(file));
    }

    private Path cached(String name, String suffix) {
        return cacheRoot == null ? null : cacheRoot.resolve(name.replace(".tif", "") + suffix);
    }

    private Header readHeader(int file) throws IOException, InterruptedException {
        String name = name(file);
        Path cached = cached(name, ".header"), missing = cached(name, ".none");
        if (missing != null && Files.exists(missing)) return Header.MISSING;
        if (cached != null && Files.isRegularFile(cached, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Header header = parse(Files.readAllBytes(cached));
                files.diskHits.incrementAndGet();
                return header;
            } catch (IOException | RuntimeException corrupt) {
                Files.deleteIfExists(cached);
            }
        }
        if (!allowNetwork) throw new MissingTerrainException("REMA " + name + " not cached and network access is disabled");
        int size = HEADER_BYTES;
        while (true) {
            byte[] bytes = files.fetch(base.resolve(name), 0, size);
            if (bytes == null) {
                if (missing != null) RangeFiles.write(missing, new byte[0]);
                return Header.MISSING;
            }
            try {
                Header header = parse(bytes);
                if (cached != null) RangeFiles.write(cached, bytes);
                return header;
            } catch (HeaderTooShort tooShort) {
                if (bytes.length < size || size >= MAX_HEADER_BYTES) throw new IOException("Unreadable REMA header " + name);
                size *= 4;
            }
        }
    }

    private float[] readBlock(Grid grid, int index) throws IOException, InterruptedException {
        String name = name(grid.file);
        Path cached = cached(name, "/" + grid.overview + "/" + index + ".lzw");
        if (cached != null && Files.isRegularFile(cached, LinkOption.NOFOLLOW_LINKS)) {
            try {
                float[] decoded = decode(Files.readAllBytes(cached), grid.header.bigEndian);
                files.diskHits.incrementAndGet();
                return decoded;
            } catch (IOException corrupt) {
                Files.deleteIfExists(cached);
            }
        }
        long offset = grid.level.offsets[index], count = grid.level.counts[index];
        if (offset == 0 || count == 0) {
            float[] empty = new float[BLOCK * BLOCK];
            java.util.Arrays.fill(empty, NO_DATA);
            return empty;
        }
        if (count > MAX_BLOCK_BYTES) throw new MissingTerrainException("Oversized REMA block in " + name);
        if (!allowNetwork) throw new MissingTerrainException("REMA block of " + name + " not cached and network access is disabled");
        byte[] raw = files.fetch(base.resolve(name), offset, (int) count);
        if (raw == null || raw.length != count) throw new MissingTerrainException("REMA block of " + name + " is missing");
        float[] decoded = decode(raw, grid.header.bigEndian);
        if (cached != null) RangeFiles.write(cached, raw);
        return decoded;
    }

    /** A block's floats from its LZW bytes, undoing the floating-point predictor row by row. */
    static float[] decode(byte[] raw, boolean bigEndian) throws IOException {
        int rowBytes = BLOCK * 4;
        byte[] bytes = lzw(raw, rowBytes * BLOCK);
        float[] out = new float[BLOCK * BLOCK];
        for (int row = 0; row < BLOCK; row++) {
            int start = row * rowBytes;
            for (int i = start + 1; i < start + rowBytes; i++) bytes[i] += bytes[i - 1];
            for (int i = 0; i < BLOCK; i++) {
                // Byte planes run from the most significant byte, whatever the file's byte order.
                int bits = (bytes[start + i] & 0xFF) << 24 | (bytes[start + BLOCK + i] & 0xFF) << 16
                        | (bytes[start + 2 * BLOCK + i] & 0xFF) << 8 | (bytes[start + 3 * BLOCK + i] & 0xFF);
                out[row * BLOCK + i] = Float.intBitsToFloat(bits);
            }
        }
        return out;
    }

    /** TIFF LZW: 9 to 12-bit codes, most significant bit first, widths changing one code early. */
    static byte[] lzw(byte[] in, int size) throws IOException {
        byte[] out = new byte[size];
        int[] prefix = new int[4096];
        byte[] suffix = new byte[4096], first = new byte[4096];
        int[] length = new int[4096];
        for (int c = 0; c < 256; c++) { suffix[c] = (byte) c; first[c] = (byte) c; length[c] = 1; }
        int done = 0, next = 258, width = 9, old = -1;
        long bitPos = 0, bitEnd = (long) in.length * 8;
        while (true) {
            if (bitPos + width > bitEnd) break;
            int code = 0;
            for (int b = 0; b < width; b++, bitPos++) code = code << 1 | (in[(int) (bitPos >>> 3)] >>> (7 - (bitPos & 7))) & 1;
            if (code == 257) break;
            if (code == 256) {
                next = 258;
                width = 9;
                old = -1;
                continue;
            }
            int emit;
            if (old == -1) {
                if (code > 255) throw new IOException("Bad LZW code after clear");
                emit = code;
            } else if (code < next) {
                emit = code;
            } else if (code == next) {
                emit = -1;
            } else {
                throw new IOException("Bad LZW code " + code);
            }
            int start = done;
            if (emit >= 0) {
                int n = length[emit];
                if (done + n > size) throw new IOException("LZW block overruns its size");
                for (int c = emit, at = done + n - 1; at >= done; c = prefix[c], at--) out[at] = suffix[c];
                done += n;
            } else {
                // The code being defined: the previous string and its own first byte.
                int n = length[old] + 1;
                if (done + n > size) throw new IOException("LZW block overruns its size");
                for (int c = old, at = done + n - 2; at >= done; c = prefix[c], at--) out[at] = suffix[c];
                out[done + n - 1] = first[old];
                done += n;
            }
            if (old != -1 && next < 4096) {
                prefix[next] = old;
                suffix[next] = out[start];
                first[next] = first[old];
                length[next] = length[old] + 1;
                next++;
                if (next + 1 >= 1 << width && width < 12) width++;
            }
            old = code;
        }
        if (done != size) throw new IOException("Short LZW block: " + done + " of " + size + " bytes");
        return out;
    }

    private static final class HeaderTooShort extends IOException {
        HeaderTooShort() { super("Header extends past the bytes read"); }
    }

    /**
     * Reads a tiled single-band float TIFF's directories (the image and its overviews) and its
     * georeferencing, classic or BigTIFF.
     */
    static Header parse(byte[] bytes) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(bytes);
        if (bytes.length < 16) throw new HeaderTooShort();
        boolean bigEndian = bytes[0] == 'M';
        in.order(bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        int version = in.getShort(2);
        if (version != 42 && version != 43) throw new IOException("Not a TIFF");
        boolean big = version == 43;
        if (big && in.getShort(4) != 8) throw new IOException("Unsupported BigTIFF offset size");
        // Sizes of a directory's entry count, an entry, and an offset.
        int countSize = big ? 8 : 2, entrySize = big ? 20 : 12, offsetSize = big ? 8 : 4;
        long next = big ? in.getLong(8) : Integer.toUnsignedLong(in.getInt(4));
        java.util.List<Level> levels = new java.util.ArrayList<>();
        double x0 = Double.NaN, y0 = Double.NaN, pixel = Double.NaN;
        while (next != 0 && levels.size() <= 8) {
            if (next + countSize > bytes.length) throw new HeaderTooShort();
            long entries = big ? in.getLong((int) next) : Short.toUnsignedInt(in.getShort((int) next));
            if (entries > 4096) throw new IOException("Oversized TIFF directory");
            if (next + countSize + entries * entrySize + offsetSize > bytes.length) throw new HeaderTooShort();
            int width = 0, height = 0, tileWidth = 0, tileHeight = 0, compression = 1, bits = 0, samples = 1, predictor = 1, format = 1, subfile = 0;
            long[] offsets = null, counts = null;
            for (int e = 0; e < entries; e++) {
                int at = (int) (next + countSize + (long) e * entrySize);
                int tag = Short.toUnsignedInt(in.getShort(at));
                Entry entry = new Entry(in, bytes.length, at, big);
                switch (tag) {
                    case 254 -> subfile = (int) entry.integers()[0];
                    case 256 -> width = (int) entry.integers()[0];
                    case 257 -> height = (int) entry.integers()[0];
                    case 258 -> bits = (int) entry.integers()[0];
                    case 259 -> compression = (int) entry.integers()[0];
                    case 277 -> samples = (int) entry.integers()[0];
                    case 317 -> predictor = (int) entry.integers()[0];
                    case 322 -> tileWidth = (int) entry.integers()[0];
                    case 323 -> tileHeight = (int) entry.integers()[0];
                    case 324 -> offsets = entry.integers();
                    case 325 -> counts = entry.integers();
                    case 339 -> format = (int) entry.integers()[0];
                    case 33550 -> { if (levels.isEmpty()) pixel = entry.doubles()[0]; }
                    case 33922 -> {
                        if (levels.isEmpty()) {
                            double[] tie = entry.doubles();
                            if (tie.length < 6 || tie[0] != 0 || tie[1] != 0) throw new IOException("Unsupported REMA tie point");
                            x0 = tie[3];
                            y0 = tie[4];
                        }
                    }
                    default -> { }
                }
            }
            int link = (int) (next + countSize + entries * entrySize);
            next = big ? in.getLong(link) : Integer.toUnsignedLong(in.getInt(link));
            // Masks are not overviews.
            if ((subfile & 4) != 0) continue;
            if (bits != 32 || samples != 1 || format != 3 || compression != 5 || predictor != 3)
                throw new IOException("Unsupported REMA encoding");
            if (width <= 0 || height <= 0 || tileWidth != BLOCK || tileHeight != BLOCK || offsets == null || counts == null || offsets.length != counts.length
                    || offsets.length != Math.ceilDiv(width, BLOCK) * Math.ceilDiv(height, BLOCK))
                throw new IOException("Unsupported REMA layout");
            levels.add(new Level(width, height, offsets, counts));
        }
        if (levels.isEmpty() || !(pixel > 0) || !Double.isFinite(x0) || !Double.isFinite(y0)) throw new IOException("REMA file lacks georeferencing");
        return new Header(levels.toArray(Level[]::new), x0, y0, pixel, bigEndian);
    }

    /** One directory entry: its type, count and where its values are, inline or elsewhere. */
    private record Entry(ByteBuffer in, int length, int type, long count, long start) {
        Entry(ByteBuffer in, int length, int at, boolean big) throws IOException {
            this(in, length, Short.toUnsignedInt(in.getShort(at + 2)),
                    big ? in.getLong(at + 4) : Integer.toUnsignedLong(in.getInt(at + 4)), at + (big ? 12 : 8));
        }

        private Entry(ByteBuffer in, int length, int type, long count, int valueAt) throws IOException {
            this(in, length, type, count, place(in, type, count, valueAt, length));
        }

        private static long place(ByteBuffer in, int type, long count, int valueAt, int length) throws IOException {
            int size = switch (type) { case 3 -> 2; case 4 -> 4; case 12, 16 -> 8; default -> 1; };
            if (count > 1 << 20) throw new IOException("Oversized TIFF array");
            // Classic entries hold four bytes inline, BigTIFF eight.
            int inline = in.getShort(2) == 43 ? 8 : 4;
            long start = count * size <= inline ? valueAt
                    : inline == 8 ? in.getLong(valueAt) : Integer.toUnsignedLong(in.getInt(valueAt));
            if (start + count * size > length) throw new HeaderTooShort();
            return start;
        }

        long[] integers() throws IOException {
            long[] values = new long[(int) count];
            for (int i = 0; i < count; i++) {
                values[i] = switch (type) {
                    case 3 -> Short.toUnsignedInt(in.getShort((int) (start + i * 2L)));
                    case 4 -> Integer.toUnsignedLong(in.getInt((int) (start + i * 4L)));
                    case 16 -> in.getLong((int) (start + i * 8L));
                    default -> throw new IOException("Unsupported TIFF integer type " + type);
                };
            }
            return values;
        }

        double[] doubles() throws IOException {
            if (type != 12) throw new IOException("Unsupported TIFF double type " + type);
            double[] values = new double[(int) count];
            for (int i = 0; i < count; i++) values[i] = in.getDouble((int) (start + i * 8L));
            return values;
        }
    }

    public Stats stats() {
        int resident;
        synchronized (memory) { resident = memory.size(); }
        return new Stats(files.downloads.get(), files.diskHits.get(), files.failures.get(), resident);
    }

    public record Stats(long downloads, long diskHits, long failures, int residentBlocks) {}

    @Override public void close() { files.close(); }
}
