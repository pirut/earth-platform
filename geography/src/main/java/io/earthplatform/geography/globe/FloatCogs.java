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
    private final RangeFiles files;
    private final Map<Integer, CompletableFuture<Header>> headers = new ConcurrentHashMap<>();
    private final LinkedHashMap<Long, float[]> memory = new LinkedHashMap<>(32, 0.75f, true);
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
    private record Level(int width, int height, int block, long[] offsets, long[] counts) {}
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
                Header header = parse(Pieces.of(DiskCache.read(cached)));
                files.diskHits.incrementAndGet();
                return header;
            } catch (IOException | RuntimeException corrupt) {
                Files.deleteIfExists(cached);
            }
        }
        if (!allowNetwork) throw new MissingTerrainException(what + " " + name + " not cached and network access is disabled");
        byte[] start = files.fetch(base.resolve(name), 0, HEADER_BYTES);
        if (start == null) {
            if (missing != null) RangeFiles.write(missing, new byte[0]);
            return Header.MISSING;
        }
        Pieces pieces = new Pieces();
        pieces.add(0, start);
        // Directories and their arrays may lie anywhere in the file, even at its end: each part the
        // parse finds it lacks is fetched where it lies, and the parse runs again.
        while (true) {
            try {
                Header header = parse(pieces);
                if (cached != null) RangeFiles.write(cached, pieces.bytes());
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
                if (more == null || more.length < need.length) throw new IOException("Unreadable " + what + " header " + name);
                pieces.add(need.offset, more);
            }
        }
    }

    /**
     * The parts of a file read so far, by where they start. Kept on disk as the bytes from the
     * file's start alone when that is all there is, as a cloud-optimised file needs; otherwise
     * marked and listed piece by piece.
     */
    static final class Pieces {
        private static final long MARK = 0x5049454345530A00L; // "PIECES\n\0"
        private final java.util.TreeMap<Long, byte[]> read = new java.util.TreeMap<>();
        private ByteOrder order = ByteOrder.LITTLE_ENDIAN;

        static Pieces of(byte[] bytes) throws IOException {
            Pieces pieces = new Pieces();
            ByteBuffer in = ByteBuffer.wrap(bytes);
            if (bytes.length < 8 || in.getLong(0) != MARK) {
                pieces.add(0, bytes);
                return pieces;
            }
            in.position(8);
            while (in.hasRemaining()) {
                if (in.remaining() < 12) throw new IOException("Truncated header pieces");
                long offset = in.getLong();
                int length = in.getInt();
                if (length < 0 || length > in.remaining()) throw new IOException("Truncated header pieces");
                byte[] piece = new byte[length];
                in.get(piece);
                pieces.add(offset, piece);
            }
            return pieces;
        }

        void add(long offset, byte[] bytes) {
            byte[] was = read.get(offset);
            if (was == null || was.length < bytes.length) read.put(offset, bytes);
        }

        long size() { return read.values().stream().mapToLong(piece -> piece.length).sum(); }

        byte[] bytes() {
            if (read.size() == 1 && read.firstKey() == 0) return read.firstEntry().getValue();
            ByteBuffer out = ByteBuffer.allocate((int) (8 + 12L * read.size() + size()));
            out.putLong(MARK);
            read.forEach((offset, piece) -> out.putLong(offset).putInt(piece.length).put(piece));
            return out.array();
        }

        /** {@code length} bytes from {@code offset}, in the file's byte order, from its first byte. */
        ByteBuffer at(long offset, long length) throws HeaderTooShort {
            var piece = offset < 0 ? null : read.floorEntry(offset);
            if (piece == null || length > Integer.MAX_VALUE || offset + length > piece.getKey() + piece.getValue().length)
                throw new HeaderTooShort(offset, length);
            return ByteBuffer.wrap(piece.getValue(), (int) (offset - piece.getKey()), (int) length).slice().order(order);
        }

        int u16(long offset) throws HeaderTooShort { return Short.toUnsignedInt(at(offset, 2).getShort(0)); }

        long u32(long offset) throws HeaderTooShort { return Integer.toUnsignedLong(at(offset, 4).getInt(0)); }

        long u64(long offset) throws HeaderTooShort { return at(offset, 8).getLong(0); }
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
        if (cached != null) RangeFiles.write(cached, raw);
        return decoded;
    }

    static float[] decode(byte[] raw, Grid grid) throws IOException {
        return decode(raw, grid.level.block, grid.header.bigEndian, grid.header.predictor, grid.header.compressed);
    }

    /**
     * A block's floats from its bytes, LZW-compressed or not, undoing the floating-point predictor
     * (3) row by row if it is used. The bytes given are left as they are.
     */
    static float[] decode(byte[] raw, int block, boolean bigEndian, int predictor, boolean compressed) throws IOException {
        int rowBytes = block * 4;
        if (!compressed && raw.length != rowBytes * block) throw new IOException("Uncompressed block of " + raw.length + " bytes");
        byte[] bytes = compressed ? lzw(raw, rowBytes * block) : predictor == 1 ? raw : raw.clone();
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

    /**
     * Reads a tiled single-band float TIFF's directories (the image and its overviews) and its
     * georeferencing, classic or BigTIFF.
     */
    static Header parse(byte[] bytes) throws IOException { return parse(Pieces.of(bytes)); }

    static Header parse(Pieces in) throws IOException {
        ByteBuffer start = in.at(0, 16);
        boolean bigEndian = start.get(0) == 'M';
        in.order = bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
        int version = in.u16(2);
        if (version != 42 && version != 43) throw new UnsupportedFile("Not a TIFF");
        boolean big = version == 43;
        if (big && in.u16(4) != 8) throw new UnsupportedFile("Unsupported BigTIFF offset size");
        // Sizes of a directory's entry count and an entry.
        int countSize = big ? 8 : 2, entrySize = big ? 20 : 12;
        long next = big ? in.u64(8) : in.u32(4);
        java.util.List<Level> levels = new java.util.ArrayList<>();
        double x0 = Double.NaN, y0 = Double.NaN, pixelX = Double.NaN, pixelY = Double.NaN;
        int firstPredictor = 0, firstCompression = 0;
        while (next != 0 && levels.size() <= 8) {
            long entries = big ? in.u64(next) : in.u16(next);
            if (entries > 4096) throw new UnsupportedFile("Oversized TIFF directory");
            // The whole directory, and the link to the next, at once.
            in.at(next, countSize + entries * entrySize + (big ? 8 : 4));
            int width = 0, height = 0, tileWidth = 0, tileHeight = 0, compression = 1, bits = 0, samples = 1, predictor = 1, format = 1, subfile = 0;
            long[] offsets = null, counts = null;
            for (int e = 0; e < entries; e++) {
                long at = next + countSize + (long) e * entrySize;
                int tag = in.u16(at);
                Entry entry = new Entry(in, at, big);
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
                            if (scale.length < 2) throw new UnsupportedFile("Unsupported pixel scale");
                            pixelX = scale[0];
                            pixelY = scale[1];
                        }
                    }
                    case 33922 -> {
                        if (levels.isEmpty()) {
                            double[] tie = entry.doubles();
                            if (tie.length < 6 || tie[0] != 0 || tie[1] != 0) throw new UnsupportedFile("Unsupported tie point");
                            x0 = tie[3];
                            y0 = tie[4];
                        }
                    }
                    default -> { }
                }
            }
            long link = next + countSize + entries * entrySize;
            next = big ? in.u64(link) : in.u32(link);
            // Masks are not overviews.
            if ((subfile & 4) != 0) continue;
            if (bits != 32 || samples != 1 || format != 3 || (compression != 1 && compression != 5) || (predictor != 1 && predictor != 3)
                    || (firstPredictor != 0 && predictor != firstPredictor) || (firstCompression != 0 && compression != firstCompression))
                throw new UnsupportedFile("Unsupported COG encoding");
            firstPredictor = predictor;
            firstCompression = compression;
            // Tiles are square and a multiple of 16 pixels, as TIFF requires; overviews may tile smaller than the full image.
            if (width <= 0 || height <= 0 || tileWidth != tileHeight || tileWidth < 16 || tileWidth > 1024 || tileWidth % 16 != 0
                    || offsets == null || counts == null || offsets.length != counts.length
                    || offsets.length != Math.ceilDiv(width, tileWidth) * Math.ceilDiv(height, tileWidth))
                throw new UnsupportedFile("Unsupported COG layout");
            levels.add(new Level(width, height, tileWidth, offsets, counts));
        }
        if (levels.isEmpty() || !(pixelX > 0) || !(pixelY > 0) || !Double.isFinite(x0) || !Double.isFinite(y0)) throw new UnsupportedFile("COG lacks georeferencing");
        return new Header(levels.toArray(Level[]::new), x0, y0, pixelX, pixelY, bigEndian, firstPredictor, firstCompression == 5);
    }

    private record Entry(Pieces in, int type, long count, long start) {
        Entry(Pieces in, long at, boolean big) throws IOException {
            this(in, in.u16(at + 2), big ? in.u64(at + 4) : in.u32(at + 4), at + (big ? 12 : 8), big);
        }

        private Entry(Pieces in, int type, long count, long valueAt, boolean big) throws IOException {
            this(in, type, count, place(in, type, count, valueAt, big));
        }

        private static int size(int type) { return switch (type) { case 3 -> 2; case 4 -> 4; case 12, 16 -> 8; default -> 1; }; }

        private static long place(Pieces in, int type, long count, long valueAt, boolean big) throws IOException {
            if (count > 1 << 20) throw new UnsupportedFile("Oversized TIFF array");
            // Classic entries hold four bytes inline, BigTIFF eight.
            int inline = big ? 8 : 4;
            return count * size(type) <= inline ? valueAt : big ? in.u64(valueAt) : in.u32(valueAt);
        }

        long[] integers() throws IOException {
            ByteBuffer values = in.at(start, count * size(type));
            long[] out = new long[(int) count];
            for (int i = 0; i < count; i++) {
                out[i] = switch (type) {
                    case 3 -> Short.toUnsignedInt(values.getShort(i * 2));
                    case 4 -> Integer.toUnsignedLong(values.getInt(i * 4));
                    case 16 -> values.getLong(i * 8);
                    default -> throw new UnsupportedFile("Unsupported TIFF integer type " + type);
                };
            }
            return out;
        }

        double[] doubles() throws IOException {
            if (type != 12) throw new UnsupportedFile("Unsupported TIFF double type " + type);
            ByteBuffer values = in.at(start, count * 8);
            double[] out = new double[(int) count];
            for (int i = 0; i < count; i++) out[i] = values.getDouble(i * 8);
            return out;
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
