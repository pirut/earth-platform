package io.earthplatform.geography.globe;

import io.earthplatform.geography.MissingTerrainException;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.IntFunction;

/**
 * A set of cloud-optimised GeoTIFFs of one float band, read with HTTP range requests: in square
 * tiles (256 to 1024 pixels), LZW-compressed with or without the floating-point predictor, with halving
 * overviews, classic TIFF or BigTIFF. Files are numbered by their dataset and named by it; a file
 * that does not exist reads as no data.
 *
 * <p>Only a file's header and the blocks a caller touches are fetched, and both are kept in an
 * optional disk cache, so a world regenerates from the same bytes offline. Loads are single-flight
 * per block and bounded in concurrency and memory. Callers block.
 */
final class FloatCogs implements AutoCloseable {
    private static final int HEADER_BYTES = 65536, MAX_HEADER_BYTES = 1 << 20, MAX_BLOCK_BYTES = 4 << 20;

    private final String what;
    private final URI base;
    private final IntFunction<String> names;
    private final float noData;
    private final Path cacheRoot;
    private final boolean allowNetwork;
    private final int maxBlocksInMemory;
    private final RangeFiles files;
    private final Map<Integer, CompletableFuture<Header>> headers = new ConcurrentHashMap<>();
    private final LinkedHashMap<Long, float[]> memory = new LinkedHashMap<>(32, 0.75f, true);
    private final Map<Long, CompletableFuture<float[]>> inFlight = new ConcurrentHashMap<>();
    /** Each thread's last few blocks, so reads that stay in them skip the shared cache. */
    private final ThreadLocal<Recent> recent = ThreadLocal.withInitial(() -> new Recent(4));

    private static final class Recent {
        final long[] keys;
        final float[][] blocks;
        int next;
        /** Blocks a reader loaded ahead and holds until it is done, or null. */
        Map<Long, float[]> held;

        Recent(int size) {
            keys = new long[size];
            java.util.Arrays.fill(keys, -1);
            blocks = new float[size][];
        }
    }

    /**
     * @param what              what is read, for messages ("REMA elevation")
     * @param datasetId         names the dataset to servers
     * @param base              URI prefix the files are named under
     * @param names             a file's name under {@code base}, from its number
     * @param noData            the value that marks no data
     * @param cacheRoot         optional disk cache directory, or null for memory only
     * @param allowNetwork      false reads only the disk cache
     * @param maxBlocksInMemory decoded blocks kept (1 MiB each)
     */
    FloatCogs(String what, String datasetId, String threadName, URI base, IntFunction<String> names, float noData,
              Path cacheRoot, boolean allowNetwork, int maxBlocksInMemory, int maxConcurrentTransfers, Duration timeout) throws IOException {
        this.what = what;
        this.base = Objects.requireNonNull(base);
        if (!base.toString().endsWith("/")) throw new IllegalArgumentException(what + " base URI must end with /");
        if (maxBlocksInMemory < 1 || maxConcurrentTransfers < 1 || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Invalid " + what + " source budget");
        if (cacheRoot != null) Files.createDirectories(cacheRoot);
        this.names = names;
        this.noData = noData;
        this.cacheRoot = cacheRoot;
        this.allowNetwork = allowNetwork;
        this.maxBlocksInMemory = maxBlocksInMemory;
        this.files = new RangeFiles(what, datasetId, threadName, maxConcurrentTransfers, timeout);
    }

    /** One overview of one file: where its pixels lie and where each block is. */
    private record Level(int width, int height, long[] offsets, long[] counts) {}
    record Header(Level[] levels, double x0, double y0, double pixelX, double pixelY, int block, boolean bigEndian, int predictor) {
        static final Header MISSING = new Header(new Level[0], 0, 0, 0, 0, 0, false, 1);
        int overviews() { return levels.length; }
    }
    /** An overview of the file that holds a point. */
    record Grid(int file, int overview, Header header, Level level, double pixelX, double pixelY) {}

    /** Reads for one caller on one thread, such as one tile, as {@link #grid}, {@link #metres} and {@link #coverage} do. */
    Reader reader() { return new Reader(); }

    /**
     * Keeps the last file's header and the last few blocks at hand, so reads that stay in them, as a
     * tile's pixels do, skip the shared caches. Not for use across threads.
     */
    final class Reader {
        // A tile at a corner of the blocks meets four at each of the two overviews it reads.
        private final Recent recent = new Recent(8);
        private final Map<Long, Grid> wanted = new java.util.HashMap<>();
        // Neighbouring pixels nearly always share a block, so most of them skip the map.
        private long lastWanted = -1;
        private int file = -1;
        private Header header;

        Reader() { recent.held = new ConcurrentHashMap<>(); }

        Grid grid(int file, int overview, double x, double y) {
            if (overview < 0) throw new IllegalArgumentException("Unsupported " + what + " overview " + overview);
            if (!Double.isFinite(x) || !Double.isFinite(y)) return null;
            if (file != this.file) {
                header = header(file);
                this.file = file;
            }
            return FloatCogs.grid(header, file, overview, x, y);
        }

        double metres(Grid grid, double x, double y) { return FloatCogs.this.metres(grid, x, y, recent); }

        double coverage(Grid grid, double x, double y) { return FloatCogs.this.coverage(grid, x, y, recent); }

        /** Notes the blocks {@link #coverage} reads at {@code x, y}, for {@link #load}. */
        void wantCoverage(Grid grid, double x, double y) { want(grid, x, y, true); }

        /** Notes the blocks {@link #metres} reads at {@code x, y}, for {@link #load}. */
        void wantMetres(Grid grid, double x, double y) { want(grid, x, y, false); }

        private void want(Grid grid, double x, double y, boolean every) {
            double fx = (x - grid.header.x0) / grid.pixelX - 0.5, fy = (grid.header.y0 - y) / grid.pixelY - 0.5;
            int i = (int) Math.floor(fx), j = (int) Math.floor(fy);
            double tx = fx - i, ty = fy - j;
            for (int dj = 0; dj <= 1; dj++) for (int di = 0; di <= 1; di++) {
                // metres skips corners of no weight; so does this.
                if (!every && (di == 0 ? 1 - tx : tx) * (dj == 0 ? 1 - ty : ty) == 0) continue;
                long key = key(grid, index(grid, i + di, j + dj));
                if (key == lastWanted) continue;
                lastWanted = key;
                wanted.putIfAbsent(key, grid);
            }
        }

        /**
         * Loads the blocks noted since the last call all at once, each on its own thread, so a tile
         * that needs several waits for the slowest rather than for each in turn, and holds them until
         * this reader is dropped.
         */
        void load() {
            java.util.List<Runnable> loads = new java.util.ArrayList<>();
            for (var entry : wanted.entrySet()) {
                long key = entry.getKey();
                if (recent.held.containsKey(key)) continue;
                Grid grid = entry.getValue();
                loads.add(() -> recent.held.put(key, block(grid, (int) (key & 0xFFFFF), key)));
            }
            wanted.clear();
            lastWanted = -1;
            if (loads.size() == 1) loads.get(0).run();
            else if (!loads.isEmpty()) RangeFiles.all(loads);
        }
    }

    /** Loads the headers of files all at once, as {@link #load} does blocks. */
    void headers(java.util.Collection<Integer> files) {
        java.util.List<Runnable> loads = new java.util.ArrayList<>();
        for (int file : files) {
            CompletableFuture<Header> known = headers.get(file);
            // A header that fails here is asked for again, and reported, by the read that needs it.
            if (known == null || !known.isDone()) loads.add(() -> { try { header(file); } catch (RuntimeException later) { } });
        }
        if (loads.size() == 1) loads.get(0).run();
        else if (!loads.isEmpty()) RangeFiles.all(loads);
    }

    /** The block of a grid holding a pixel, its edges standing in for pixels past them. */
    private static int index(Grid grid, int column, int row) {
        Level level = grid.level;
        column = Math.max(0, Math.min(level.width - 1, column));
        row = Math.max(0, Math.min(level.height - 1, row));
        int size = grid.header.block, across = Math.ceilDiv(level.width, size);
        return (row / size) * across + column / size;
    }

    private static long key(Grid grid, int index) { return (long) grid.file << 24 | (long) grid.overview << 20 | index; }

    /**
     * An overview of a file, if the file exists and its image holds {@code x, y} (in the file's own
     * coordinates); null otherwise. A small file holds fewer overviews; its coarsest stands in for
     * the rest.
     */
    Grid grid(int file, int overview, double x, double y) {
        if (overview < 0) throw new IllegalArgumentException("Unsupported " + what + " overview " + overview);
        if (!Double.isFinite(x) || !Double.isFinite(y)) return null;
        return grid(header(file), file, overview, x, y);
    }

    private static Grid grid(Header header, int file, int overview, double x, double y) {
        if (header.levels.length == 0) return null;
        overview = Math.min(overview, header.levels.length - 1);
        Level level = header.levels[overview], full = header.levels[0];
        double right = header.x0 + header.pixelX * full.width, bottom = header.y0 - header.pixelY * full.height;
        if (x < header.x0 || x >= right || y > header.y0 || y <= bottom) return null;
        // Overviews are rounded up, so their pixels span the full image a little unevenly; GDAL
        // places them by the ratio of sizes.
        return new Grid(file, overview, header, level, header.pixelX * full.width / level.width, header.pixelY * full.height / level.height);
    }

    /** The value at {@code x, y}, bilinear over the grid's valid pixels; NaN where there is no data. */
    double metres(Grid grid, double x, double y) { return metres(grid, x, y, recent.get()); }

    private double metres(Grid grid, double x, double y, Recent cache) {
        double fx = (x - grid.header.x0) / grid.pixelX - 0.5, fy = (grid.header.y0 - y) / grid.pixelY - 0.5;
        int i = (int) Math.floor(fx), j = (int) Math.floor(fy);
        double tx = fx - i, ty = fy - j, sum = 0, weights = 0;
        for (int dj = 0; dj <= 1; dj++) for (int di = 0; di <= 1; di++) {
            double w = (di == 0 ? 1 - tx : tx) * (dj == 0 ? 1 - ty : ty);
            if (w == 0) continue;
            float v = pixel(grid, i + di, j + dj, cache);
            if (v == noData || !Float.isFinite(v)) continue;
            sum += w * v;
            weights += w;
        }
        return weights > 1e-9 ? sum / weights : Double.NaN;
    }

    /**
     * The share of the grid that holds data around {@code x, y}, bilinear between pixel centres: 1
     * inside the data, 0 outside, a ramp one pixel wide at its edge.
     */
    double coverage(Grid grid, double x, double y) { return coverage(grid, x, y, recent.get()); }

    private double coverage(Grid grid, double x, double y, Recent cache) {
        double fx = (x - grid.header.x0) / grid.pixelX - 0.5, fy = (grid.header.y0 - y) / grid.pixelY - 0.5;
        int i = (int) Math.floor(fx), j = (int) Math.floor(fy);
        double tx = fx - i, ty = fy - j, share = 0;
        for (int dj = 0; dj <= 1; dj++) for (int di = 0; di <= 1; di++) {
            float v = pixel(grid, i + di, j + dj, cache);
            if (v != noData && Float.isFinite(v)) share += (di == 0 ? 1 - tx : tx) * (dj == 0 ? 1 - ty : ty);
        }
        return share;
    }

    private float pixel(Grid grid, int column, int row, Recent cache) {
        Level level = grid.level;
        column = Math.max(0, Math.min(level.width - 1, column));
        row = Math.max(0, Math.min(level.height - 1, row));
        int size = grid.header.block, across = Math.ceilDiv(level.width, size);
        int index = (row / size) * across + column / size;
        long key = (long) grid.file << 24 | (long) grid.overview << 20 | index;
        float[] block = null;
        for (int k = 0; k < cache.keys.length; k++) if (cache.keys[k] == key) { block = cache.blocks[k]; break; }
        if (block == null) {
            if (cache.held != null) block = cache.held.get(key);
            if (block == null) block = block(grid, index, key);
            cache.keys[cache.next] = key;
            cache.blocks[cache.next] = block;
            cache.next = (cache.next + 1) % cache.keys.length;
        }
        return block[(row % size) * size + column % size];
    }

    private float[] block(Grid grid, int index, long key) {
        synchronized (memory) {
            float[] hit = memory.get(key);
            if (hit != null) return hit;
        }
        return files.singleFlight(inFlight, key, () -> {
            // A thread that missed the cache just as another finished this block finds it here, not decoding it again.
            synchronized (memory) {
                float[] hit = memory.get(key);
                if (hit != null) return hit;
            }
            float[] decoded = readBlock(grid, index);
            synchronized (memory) {
                memory.put(key, decoded);
                var iterator = memory.entrySet().iterator();
                while (memory.size() > maxBlocksInMemory && iterator.hasNext()) { iterator.next(); iterator.remove(); }
            }
            return decoded;
        }, what + " block");
    }

    private Header header(int file) {
        // Asked for every pixel read: a header already read is returned at once, without naming the file.
        CompletableFuture<Header> known = headers.get(file);
        if (known != null && known.isDone() && !known.isCompletedExceptionally()) return known.join();
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
        return files.await(shared, what + " header " + names.apply(file));
    }

    private Path cached(String name, String suffix) {
        return cacheRoot == null ? null : cacheRoot.resolve(name.replace(".tif", "") + suffix);
    }

    private Header readHeader(int file) throws IOException, InterruptedException {
        String name = names.apply(file);
        Path cached = cached(name, ".header"), missing = cached(name, ".none");
        if (missing != null && Files.exists(missing)) return Header.MISSING;
        if (cached != null && Files.isRegularFile(cached, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Header header = parse(DiskCache.read(cached));
                files.diskHits.incrementAndGet();
                return header;
            } catch (IOException | RuntimeException corrupt) {
                Files.deleteIfExists(cached);
            }
        }
        if (!allowNetwork) throw new MissingTerrainException(what + " " + name + " not cached and network access is disabled");
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
                if (bytes.length < size || size >= MAX_HEADER_BYTES) throw new IOException("Unreadable " + what + " header " + name);
                size *= 4;
            }
        }
    }

    private float[] readBlock(Grid grid, int index) throws IOException, InterruptedException {
        String name = names.apply(grid.file);
        Path cached = cached(name, "/" + grid.overview + "/" + index + ".lzw");
        if (cached != null && Files.isRegularFile(cached, LinkOption.NOFOLLOW_LINKS)) {
            try {
                float[] decoded = decode(DiskCache.read(cached), grid.header.block, grid.header.bigEndian, grid.header.predictor);
                files.diskHits.incrementAndGet();
                return decoded;
            } catch (IOException corrupt) {
                Files.deleteIfExists(cached);
            }
        }
        long offset = grid.level.offsets[index], count = grid.level.counts[index];
        if (offset == 0 || count == 0) {
            float[] empty = new float[grid.header.block * grid.header.block];
            java.util.Arrays.fill(empty, noData);
            return empty;
        }
        if (count > MAX_BLOCK_BYTES) throw new MissingTerrainException("Oversized " + what + " block in " + name);
        if (!allowNetwork) throw new MissingTerrainException(what + " block of " + name + " not cached and network access is disabled");
        byte[] raw = files.fetch(base.resolve(name), offset, (int) count);
        if (raw == null || raw.length != count) throw new MissingTerrainException(what + " block of " + name + " is missing");
        float[] decoded = decode(raw, grid.header.block, grid.header.bigEndian, grid.header.predictor);
        if (cached != null) RangeFiles.write(cached, raw);
        return decoded;
    }

    /** A block's floats from its LZW bytes, undoing the floating-point predictor (3) row by row if it is used. */
    static float[] decode(byte[] raw, int block, boolean bigEndian, int predictor) throws IOException {
        int rowBytes = block * 4;
        byte[] bytes = lzw(raw, rowBytes * block);
        float[] out = new float[block * block];
        if (predictor == 1) {
            ByteBuffer.wrap(bytes).order(bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out);
            return out;
        }
        for (int row = 0; row < block; row++) {
            int start = row * rowBytes;
            for (int i = start + 1; i < start + rowBytes; i++) bytes[i] += bytes[i - 1];
            for (int i = 0; i < block; i++) {
                // Byte planes run from the most significant byte, whatever the file's byte order.
                int bits = (bytes[start + i] & 0xFF) << 24 | (bytes[start + block + i] & 0xFF) << 16
                        | (bytes[start + 2 * block + i] & 0xFF) << 8 | (bytes[start + 3 * block + i] & 0xFF);
                out[row * block + i] = Float.intBitsToFloat(bits);
            }
        }
        return out;
    }

    /**
     * TIFF LZW: 9 to 12-bit codes, most significant bit first, widths changing one code early. Each
     * code's string already lies in the output where it was first written, so a code is copied from
     * there rather than walked back through its prefixes, and codes are cut from three bytes at once.
     */
    static byte[] lzw(byte[] in, int size) throws IOException {
        byte[] out = new byte[size];
        // Where each code's string starts in the output, and its length; codes below 256 are their own byte.
        int[] offset = new int[4096], length = new int[4096];
        int done = 0, next = 258, width = 9, old = -1, oldStart = 0;
        long bitPos = 0, bitEnd = (long) in.length * 8;
        int last = in.length - 1;
        while (true) {
            if (bitPos + width > bitEnd) break;
            int at = (int) (bitPos >>> 3), shift = (int) (bitPos & 7);
            int window = (in[at] & 0xFF) << 16 | (at < last ? (in[at + 1] & 0xFF) << 8 : 0) | (at + 1 < last ? in[at + 2] & 0xFF : 0);
            int code = window >>> (24 - shift - width) & (1 << width) - 1;
            bitPos += width;
            if (code == 257) break;
            if (code == 256) {
                next = 258;
                width = 9;
                old = -1;
                continue;
            }
            int start = done;
            if (old == -1) {
                if (code > 255) throw new IOException("Bad LZW code after clear");
                if (done >= size) throw new IOException("LZW block overruns its size");
                out[done++] = (byte) code;
            } else if (code < 256) {
                if (done >= size) throw new IOException("LZW block overruns its size");
                out[done++] = (byte) code;
            } else if (code < next) {
                int n = length[code];
                if (done + n > size) throw new IOException("LZW block overruns its size");
                System.arraycopy(out, offset[code], out, done, n);
                done += n;
            } else if (code == next) {
                // The code being defined: the previous string and its own first byte.
                int n = (old < 256 ? 1 : length[old]) + 1;
                if (done + n > size) throw new IOException("LZW block overruns its size");
                System.arraycopy(out, oldStart, out, done, n - 1);
                out[done + n - 1] = out[oldStart];
                done += n;
            } else {
                throw new IOException("Bad LZW code " + code);
            }
            if (old != -1 && next < 4096) {
                // The previous string plus this one's first byte, which follows it in the output.
                offset[next] = oldStart;
                length[next] = (old < 256 ? 1 : length[old]) + 1;
                next++;
                if (next + 1 >= 1 << width && width < 12) width++;
            }
            old = code;
            oldStart = start;
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
        double x0 = Double.NaN, y0 = Double.NaN, pixelX = Double.NaN, pixelY = Double.NaN;
        int firstPredictor = 0, block = 0;
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
                    case 33550 -> {
                        if (levels.isEmpty()) {
                            double[] scale = entry.doubles();
                            if (scale.length < 2) throw new IOException("Unsupported pixel scale");
                            pixelX = scale[0];
                            pixelY = scale[1];
                        }
                    }
                    case 33922 -> {
                        if (levels.isEmpty()) {
                            double[] tie = entry.doubles();
                            if (tie.length < 6 || tie[0] != 0 || tie[1] != 0) throw new IOException("Unsupported tie point");
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
            if (bits != 32 || samples != 1 || format != 3 || compression != 5 || (predictor != 1 && predictor != 3) || (firstPredictor != 0 && predictor != firstPredictor))
                throw new IOException("Unsupported COG encoding");
            firstPredictor = predictor;
            if (width <= 0 || height <= 0 || tileWidth != tileHeight || tileWidth < 256 || tileWidth > 1024 || Integer.bitCount(tileWidth) != 1
                    || (block != 0 && tileWidth != block) || offsets == null || counts == null || offsets.length != counts.length
                    || offsets.length != Math.ceilDiv(width, tileWidth) * Math.ceilDiv(height, tileWidth))
                throw new IOException("Unsupported COG layout");
            block = tileWidth;
            levels.add(new Level(width, height, offsets, counts));
        }
        if (levels.isEmpty() || !(pixelX > 0) || !(pixelY > 0) || !Double.isFinite(x0) || !Double.isFinite(y0)) throw new IOException("COG lacks georeferencing");
        return new Header(levels.toArray(Level[]::new), x0, y0, pixelX, pixelY, block, bigEndian, firstPredictor);
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

    Stats stats() {
        int resident;
        synchronized (memory) { resident = memory.size(); }
        return new Stats(files.downloads.get(), files.diskHits.get(), files.failures.get(), resident);
    }

    record Stats(long downloads, long diskHits, long failures, int residentBlocks) {}

    @Override public void close() { files.close(); }
}
