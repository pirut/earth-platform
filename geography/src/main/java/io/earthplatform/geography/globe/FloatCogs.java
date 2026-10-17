package io.earthplatform.geography.globe;

import io.earthplatform.geography.MissingTerrainException;
import io.earthplatform.geography.globe.CogHeader.Pieces;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.IntFunction;

/**
 * A set of tiled GeoTIFFs of one float band, read with HTTP range requests: in square tiles,
 * LZW-compressed or not, with or without the floating-point predictor, with halving overviews,
 * classic TIFF or BigTIFF. Most are cloud-optimised, their directories first, but some keep them
 * at the end of the file, and those read too. Files are numbered by their dataset and named by
 * it; a file that does not exist reads as no data.
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
    /** The finest levels whose block tables are never read; see the constructor. */
    private final int skipLevels;
    private final RangeFiles files;
    private final Map<Integer, CompletableFuture<Header>> headers = new ConcurrentHashMap<>();
    private final LruMap<Long, float[]> memory = new LruMap<>(32);
    private long memoryBytes;
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
     * @param maxBlocksInMemory decoded blocks kept, counted as 1 MiB each; smaller blocks are kept by the same memory
     */
    FloatCogs(String what, String datasetId, String threadName, URI base, IntFunction<String> names, float noData,
              Path cacheRoot, boolean allowNetwork, int maxBlocksInMemory, int maxConcurrentTransfers, Duration timeout) throws IOException {
        this(what, datasetId, threadName, base, names, noData, cacheRoot, allowNetwork, maxBlocksInMemory, maxConcurrentTransfers, timeout, 0);
    }

    /**
     * @param skipLevels the finest levels of each file that are never read: their block tables, megabytes in
     *                   the largest files, are left unfetched, and a read of one reads the next coarser
     *                   level that is kept
     */
    FloatCogs(String what, String datasetId, String threadName, URI base, IntFunction<String> names, float noData,
              Path cacheRoot, boolean allowNetwork, int maxBlocksInMemory, int maxConcurrentTransfers, Duration timeout, int skipLevels) throws IOException {
        if (skipLevels < 0 || skipLevels > 4) throw new IllegalArgumentException("Unsupported " + what + " levels skipped: " + skipLevels);
        this.skipLevels = skipLevels;
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
    record Level(int width, int height, int block, long[] offsets, long[] counts) {}
    record Header(Level[] levels, double x0, double y0, double pixelX, double pixelY, boolean bigEndian, int predictor, boolean compressed) {
        static final Header MISSING = new Header(new Level[0], 0, 0, 0, 0, false, 1, true);
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
        int size = level.block, across = Math.ceilDiv(level.width, size);
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
        // A level skipped stands in for nothing: the next coarser one is read.
        while (header.levels[overview].offsets == null && overview < header.levels.length - 1) overview++;
        Level level = header.levels[overview], full = header.levels[0];
        if (level.offsets == null) return null;
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
        int size = level.block, across = Math.ceilDiv(level.width, size);
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
                float[] was = memory.put(key, decoded);
                memoryBytes += decoded.length * 4L - (was == null ? 0 : was.length * 4L);
                var iterator = memory.entrySet().iterator();
                while (memoryBytes > (long) maxBlocksInMemory << 20 && memory.size() > 1 && iterator.hasNext()) {
                    memoryBytes -= iterator.next().getValue().length * 4L;
                    iterator.remove();
                }
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
                Header header = CogHeader.parse(Pieces.of(DiskCache.read(cached)), skipLevels);
                files.diskHits.incrementAndGet();
                return header;
            } catch (IOException | RuntimeException corrupt) {
                Files.deleteIfExists(cached);
            }
        }
        if (!allowNetwork) throw new MissingTerrainException(what + " " + name + " not cached and network access is disabled");
        byte[] start = files.fetch(base.resolve(name), 0, HEADER_BYTES);
        if (start == null) {
            if (missing != null) DiskCache.write(missing, new byte[0]);
            return Header.MISSING;
        }
        Pieces pieces = new Pieces();
        pieces.add(0, start);
        // Directories and their arrays may lie anywhere in the file, even at its end: each part the
        // parse finds it lacks is fetched where it lies, and the parse runs again.
        while (true) {
            try {
                Header header = CogHeader.parse(pieces, skipLevels);
                if (cached != null) DiskCache.write(cached, pieces.bytes());
                return header;
            } catch (UnsupportedFile unsupported) {
                // The same for every read: a file laid out in a way this cannot read is no data, so
                // tiles about it ease off its edge as at any edge of the data, rather than each failing.
                // Not marked on disk, so a later version that reads it fetches it again.
                TerrariumTileSource.warnOnce(what + " " + name, "Cannot read " + what + " " + name + "; leaving it out", unsupported);
                return Header.MISSING;
            } catch (HeaderTooShort need) {
                int length = (int) Math.max(need.length, HEADER_BYTES);
                if (need.offset < 0 || need.length > MAX_HEADER_BYTES || pieces.size() + length > MAX_HEADER_BYTES * 4L) {
                    TerrariumTileSource.warnOnce(what + " " + name, "Cannot read " + what + " " + name + "; leaving it out", need);
                    return Header.MISSING;
                }
                byte[] more = files.fetch(base.resolve(name), need.offset, length);
                // A file that ends before its own header says is as unreadable however often it is asked for.
                if (more == null || more.length < need.length) {
                    TerrariumTileSource.warnOnce(what + " " + name, "Cannot read " + what + " " + name + ": it ends before its header does; leaving it out", need);
                    return Header.MISSING;
                }
                pieces.add(need.offset, more);
            }
        }
    }

    private float[] readBlock(Grid grid, int index) throws IOException, InterruptedException {
        String name = names.apply(grid.file);
        Path cached = cached(name, "/" + grid.overview + "/" + index + ".lzw");
        if (cached != null && Files.isRegularFile(cached, LinkOption.NOFOLLOW_LINKS)) {
            try {
                float[] decoded = decode(DiskCache.read(cached), grid);
                files.diskHits.incrementAndGet();
                return decoded;
            } catch (IOException corrupt) {
                Files.deleteIfExists(cached);
            }
        }
        long offset = grid.level.offsets[index], count = grid.level.counts[index];
        if (offset == 0 || count == 0) {
            float[] empty = new float[grid.level.block * grid.level.block];
            java.util.Arrays.fill(empty, noData);
            return empty;
        }
        if (count > MAX_BLOCK_BYTES) throw new MissingTerrainException("Oversized " + what + " block in " + name);
        if (!allowNetwork) throw new MissingTerrainException(what + " block of " + name + " not cached and network access is disabled");
        byte[] raw = files.fetch(base.resolve(name), offset, (int) count);
        if (raw == null || raw.length != count) throw new MissingTerrainException(what + " block of " + name + " is missing");
        float[] decoded = decode(raw, grid);
        if (cached != null) DiskCache.write(cached, raw);
        return decoded;
    }

    static float[] decode(byte[] raw, Grid grid) throws IOException {
        return CogBlocks.decode(raw, grid.level.block, grid.header.bigEndian, grid.header.predictor, grid.header.compressed);
    }

    /** A file this cannot read whatever is fetched of it. */
    static final class UnsupportedFile extends IOException {
        UnsupportedFile(String message) { super(message); }
    }

    /** A part of the header not read yet: {@code length} bytes from {@code offset}. */
    static final class HeaderTooShort extends IOException {
        final long offset, length;

        HeaderTooShort(long offset, long length) {
            super("Header needs bytes " + offset + " to " + (offset + length) + ", not read yet");
            this.offset = offset;
            this.length = length;
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
