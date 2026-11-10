package io.earthplatform.geography.globe;

import io.earthplatform.geography.MissingTerrainException;
import io.earthplatform.geography.globe.ClassicTiff.HeaderTooShort;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.zip.DataFormatException;

import static io.earthplatform.geography.globe.ClassicTiff.array;
import static io.earthplatform.geography.globe.ClassicTiff.scalar;

/**
 * How tall the world's trees stand, from Meta and the World Resources Institute's global canopy
 * height map (CHM v2), dataset id {@code meta-wri-chm-v2-p95}: the 95th percentile of canopy
 * height in whole metres per Web Mercator pixel of about 10 m at the equator, {@code 0} where
 * nothing stands above the ground and {@link #UNKNOWN} where the map has no reading (cloud, open
 * ocean, no file).
 *
 * <p>The source is the public AWS Open Data bucket: one cloud-optimised GeoTIFF per zoom 7 Web
 * Mercator tile, named by its quadkey, 32,768 pixels square in 512-pixel deflated blocks with six
 * halving overviews. Only the header and the blocks a caller touches are fetched, with HTTP range
 * requests, and both are kept in an optional disk cache, so a world regenerates from the same
 * bytes offline. Loads are single-flight per block and bounded in concurrency and memory. Callers
 * block; use it from generation workers.
 */
public final class CanopySource implements AutoCloseable {
    public static final String DATASET_ID = "meta-wri-chm-v2-p95";
    public static final URI AWS_CANOPY = URI.create("https://dataforgood-fb-data.s3.amazonaws.com/forests/v2/global/dinov3_global_chm_v2_ml3_10m_p95/chm/");
    public static final String ATTRIBUTION = "Canopy height: Meta and World Resources Institute, High Resolution Canopy Height Maps v2 (CC BY 4.0)";
    /** No reading: cloud in the source imagery, open ocean, or no file. */
    public static final int UNKNOWN = -1;
    /** Coarsest overview: pixels 64 times the full resolution. */
    public static final int MAX_OVERVIEW = 6;
    /** The zoom of the file tiles, and the pixels across the world at full resolution, as a power of two. */
    private static final int TILE_ZOOM = 7, WORLD_BITS = 22, TILE_BITS = WORLD_BITS - TILE_ZOOM, BLOCK_BITS = 9, BLOCK = 1 << BLOCK_BITS;
    private static final int NO_DATA = 255, HEADER_BYTES = 65536, MAX_HEADER_BYTES = 1 << 20, MAX_BLOCK_BYTES = 2 << 20;
    private static final double MAX_LATITUDE = 85.05112878;

    private final URI base;
    private final Path cacheRoot;
    private final boolean allowNetwork;
    private final int maxBlocksInMemory;
    private final RangeFiles files;
    private final Map<Integer, CompletableFuture<Header>> headers = new ConcurrentHashMap<>();
    private final LruMap<Long, byte[]> memory = new LruMap<>(64);
    private final Map<Long, CompletableFuture<byte[]>> inFlight = new ConcurrentHashMap<>();
    /** Each thread's last block, so reads that stay in one block skip the shared cache. */
    private final ThreadLocal<Last> recent = ThreadLocal.withInitial(Last::new);

    private static final class Last { long key = -1; byte[] block; }

    /**
     * @param base              URI prefix the files are named under
     * @param cacheRoot         optional disk cache directory, or null for memory only
     * @param allowNetwork      false reads only the disk cache
     * @param maxBlocksInMemory decoded blocks kept (each 256 KiB)
     */
    public CanopySource(URI base, Path cacheRoot, boolean allowNetwork, int maxBlocksInMemory, int maxConcurrentTransfers, Duration timeout) throws IOException {
        this.base = Objects.requireNonNull(base);
        if (!base.toString().endsWith("/")) throw new IllegalArgumentException("Canopy base URI must end with /");
        if (maxBlocksInMemory < 1 || maxConcurrentTransfers < 1 || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Invalid canopy source budget");
        if (cacheRoot != null) Files.createDirectories(cacheRoot);
        this.cacheRoot = cacheRoot;
        this.allowNetwork = allowNetwork;
        this.maxBlocksInMemory = maxBlocksInMemory;
        this.files = new RangeFiles("Canopy height", DATASET_ID, "earth-canopy-io", maxConcurrentTransfers, timeout);
    }

    /**
     * The canopy's height in metres at a latitude and longitude, read at an overview (0 for full
     * resolution, each step halving it, up to {@value #MAX_OVERVIEW}); {@link #UNKNOWN} where the
     * map has no reading.
     */
    public int metres(double latitude, double longitude, int overview) {
        if (overview < 0 || overview > MAX_OVERVIEW) throw new IllegalArgumentException("Unsupported canopy overview " + overview);
        if (!(Math.abs(latitude) <= 90) || !Double.isFinite(longitude)) throw new IllegalArgumentException("Invalid position " + latitude + ", " + longitude);
        if (Math.abs(latitude) >= MAX_LATITUDE) return UNKNOWN;
        double world = 1L << WORLD_BITS, fx = (wrap(longitude) + 180) / 360 * world;
        double radians = Math.toRadians(latitude), fy = (1 - Math.log(Math.tan(radians) + 1 / Math.cos(radians)) / Math.PI) / 2 * world;
        int gx = (int) Math.min(world - 1, Math.max(0, fx)), gy = (int) Math.min(world - 1, Math.max(0, fy));
        int tile = (gy >> TILE_BITS) << TILE_ZOOM | gx >> TILE_BITS;
        int px = (gx & (1 << TILE_BITS) - 1) >> overview, py = (gy & (1 << TILE_BITS) - 1) >> overview;
        long key = blockKey(tile, overview, py >> BLOCK_BITS, px >> BLOCK_BITS);
        Last cached = recent.get();
        if (cached.key != key) {
            cached.block = block(tile, overview, py >> BLOCK_BITS, px >> BLOCK_BITS);
            cached.key = key;
        }
        byte[] block = cached.block;
        if (block.length == 0) return UNKNOWN;
        int metres = block[(py & BLOCK - 1) << BLOCK_BITS | px & BLOCK - 1] & 0xFF;
        return metres == NO_DATA ? UNKNOWN : metres;
    }

    /** Loads every block an area needs at an overview, several at a time, so later reads never wait. */
    public void prefetch(double south, double west, double north, double east, int overview) {
        // A block's width in degrees of longitude; rows are closer together in latitude away from the equator.
        double step = 360.0 * (BLOCK << overview) / (1L << WORLD_BITS);
        south = Math.max(-MAX_LATITUDE + 0.01, south);
        north = Math.min(MAX_LATITUDE - 0.01, north);
        List<double[]> points = new ArrayList<>();
        for (double lat = south; lat <= north + step; lat += step * Math.max(0.05, Math.cos(Math.toRadians(lat))))
            for (double lon = west; lon <= east + step; lon += step) points.add(new double[]{Math.min(lat, north), Math.min(lon, east)});
        List<Runnable> loads = new ArrayList<>(points.size());
        for (double[] p : points) loads.add(() -> metres(p[0], p[1], overview));
        RangeFiles.all(loads);
    }

    // Files and blocks -----------------------------------------------------------------------------

    /** One file's header: per overview, its size and where each block is. */
    private record Level(int width, int height, long[] offsets, long[] counts) {}
    private record Header(Level[] levels, boolean deflate, boolean differenced) {
        static final Header MISSING = new Header(new Level[0], false, false);
    }

    private static long blockKey(int tile, int overview, int row, int column) {
        return (long) tile << 32 | (long) overview << 28 | (long) row << 14 | column;
    }

    /** A zoom 7 tile's file: its quadkey, one digit per zoom from the top. */
    static String name(int tile) {
        int x = tile & (1 << TILE_ZOOM) - 1, y = tile >> TILE_ZOOM;
        StringBuilder key = new StringBuilder(TILE_ZOOM + 4);
        for (int bit = TILE_ZOOM - 1; bit >= 0; bit--) key.append((char) ('0' + (x >> bit & 1) + 2 * (y >> bit & 1)));
        return key.append(".tif").toString();
    }

    private byte[] block(int tile, int overview, int row, int column) {
        Header header = header(tile);
        if (header.levels.length == 0) return new byte[0];
        Level level = header.levels[overview];
        int across = Math.ceilDiv(level.width, BLOCK);
        int index = row * across + column;
        if (column >= across || index < 0 || index >= level.offsets.length) return new byte[0];
        long key = blockKey(tile, overview, row, column);
        synchronized (memory) {
            byte[] hit = memory.get(key);
            if (hit != null) return hit;
        }
        byte[] decoded = files.singleFlight(inFlight, key, () -> readBlock(tile, overview, index, header, level), "canopy height block");
        synchronized (memory) {
            memory.putAndTrim(key, decoded, maxBlocksInMemory);
        }
        return decoded;
    }

    private Header header(int tile) {
        CompletableFuture<Header> mine = new CompletableFuture<>(), shared = headers.putIfAbsent(tile, mine);
        if (shared == null) {
            try { mine.complete(readHeader(tile)); }
            catch (Throwable failure) {
                files.failures.incrementAndGet();
                mine.completeExceptionally(failure);
                // A failed header is retried by the next caller.
                headers.remove(tile, mine);
            }
            shared = mine;
        }
        return files.await(shared, "canopy height header " + name(tile));
    }

    private Header readHeader(int tile) throws IOException, InterruptedException {
        String name = name(tile);
        Path cached = cacheRoot == null ? null : cacheRoot.resolve(name + ".header");
        Path missing = cacheRoot == null ? null : cacheRoot.resolve(name + ".none");
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
        if (!allowNetwork) throw new MissingTerrainException("Canopy height " + name + " not cached and network access is disabled");
        int size = HEADER_BYTES;
        while (true) {
            byte[] bytes = files.fetch(base.resolve(name), 0, size);
            if (bytes == null) {
                if (missing != null) DiskCache.write(missing, new byte[0]);
                return Header.MISSING;
            }
            try {
                Header header = parse(bytes);
                if (cached != null) DiskCache.write(cached, bytes);
                return header;
            } catch (HeaderTooShort tooShort) {
                if (bytes.length < size || size >= MAX_HEADER_BYTES) throw new IOException("Unreadable canopy height header " + name);
                size *= 4;
            }
        }
    }

    private byte[] readBlock(int tile, int overview, int index, Header header, Level level) throws IOException, InterruptedException {
        String name = name(tile);
        Path cached = cacheRoot == null ? null : cacheRoot.resolve(name.replace(".tif", "") + "/" + overview + "/" + index + ".z");
        byte[] raw;
        if (cached != null && Files.isRegularFile(cached, LinkOption.NOFOLLOW_LINKS)) {
            raw = DiskCache.read(cached);
            try {
                byte[] decoded = decode(raw, header);
                files.diskHits.incrementAndGet();
                return decoded;
            } catch (IOException corrupt) {
                Files.deleteIfExists(cached);
            }
        }
        long offset = level.offsets[index], count = level.counts[index];
        // An empty block has no reading anywhere.
        if (offset == 0 || count == 0) return new byte[0];
        if (count > MAX_BLOCK_BYTES) throw new MissingTerrainException("Oversized canopy height block in " + name);
        if (!allowNetwork) throw new MissingTerrainException("Canopy height block of " + name + " not cached and network access is disabled");
        raw = files.fetch(base.resolve(name), offset, (int) count);
        if (raw == null || raw.length != count) throw new MissingTerrainException("Canopy height block of " + name + " is missing");
        byte[] decoded = decode(raw, header);
        if (cached != null) DiskCache.write(cached, raw);
        return decoded;
    }

    private static byte[] decode(byte[] raw, Header header) throws IOException {
        int size = BLOCK * BLOCK;
        byte[] out;
        if (!header.deflate) {
            if (raw.length != size) throw new IOException("Unexpected canopy height block size");
            out = raw.clone();
        } else {
            try {
                out = new byte[size];
                if (Deflate.inflate(raw, out) != size) throw new IOException("Short canopy height block");
            } catch (DataFormatException malformed) {
                throw new IOException("Malformed canopy height block", malformed);
            }
        }
        // Horizontal differencing: each pixel is stored as its step from the one before it in its row.
        if (header.differenced) for (int row = 0; row < size; row += BLOCK)
            for (int i = row + 1; i < row + BLOCK; i++) out[i] += out[i - 1];
        return out;
    }

    /** Reads the image file directories of a tiled 8-bit single-band TIFF: the image and its overviews. */
    static Header parse(byte[] bytes) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(bytes);
        if (bytes.length < 8) throw new HeaderTooShort();
        in.order(bytes[0] == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        if (in.getShort(2) != 42) throw new IOException("Not a classic TIFF");
        long next = Integer.toUnsignedLong(in.getInt(4));
        List<Level> levels = new ArrayList<>();
        boolean deflate = true, differenced = false;
        while (next != 0 && levels.size() <= MAX_OVERVIEW) {
            if (next + 2 > bytes.length) throw new HeaderTooShort();
            int entries = Short.toUnsignedInt(in.getShort((int) next));
            if (next + 2 + entries * 12L + 4 > bytes.length) throw new HeaderTooShort();
            int width = 0, height = 0, tileWidth = 0, tileHeight = 0, compression = 1, bits = 8, samples = 1, predictor = 1;
            long[] offsets = null, counts = null;
            for (int e = 0; e < entries; e++) {
                int at = (int) next + 2 + e * 12;
                int tag = Short.toUnsignedInt(in.getShort(at)), type = Short.toUnsignedInt(in.getShort(at + 2));
                long count = Integer.toUnsignedLong(in.getInt(at + 4));
                switch (tag) {
                    case 256 -> width = (int) scalar(in, at, type);
                    case 257 -> height = (int) scalar(in, at, type);
                    case 258 -> bits = (int) scalar(in, at, type);
                    case 259 -> compression = (int) scalar(in, at, type);
                    case 277 -> samples = (int) scalar(in, at, type);
                    case 317 -> predictor = (int) scalar(in, at, type);
                    case 322 -> tileWidth = (int) scalar(in, at, type);
                    case 323 -> tileHeight = (int) scalar(in, at, type);
                    case 324 -> offsets = array(in, bytes.length, at, type, count);
                    case 325 -> counts = array(in, bytes.length, at, type, count);
                    default -> { }
                }
            }
            if (bits != 8 || samples != 1 || predictor < 1 || predictor > 2 || (compression != 1 && compression != 8 && compression != 32946))
                throw new IOException("Unsupported canopy height encoding");
            if (width != 1 << TILE_BITS - levels.size() || height != width || tileWidth != BLOCK || tileHeight != BLOCK || offsets == null || counts == null
                    || offsets.length != counts.length || offsets.length != Math.ceilDiv(width, BLOCK) * Math.ceilDiv(height, BLOCK))
                throw new IOException("Unsupported canopy height layout");
            deflate = compression != 1;
            differenced = predictor == 2;
            levels.add(new Level(width, height, offsets, counts));
            next = Integer.toUnsignedLong(in.getInt((int) next + 2 + entries * 12));
        }
        if (levels.size() <= MAX_OVERVIEW) throw new IOException("Canopy height file lacks overviews");
        return new Header(levels.toArray(Level[]::new), deflate, differenced);
    }

    private static double wrap(double longitude) {
        double wrapped = ((longitude + 180) % 360 + 360) % 360 - 180;
        return wrapped == 180 ? -180 : wrapped;
    }

    public Stats stats() {
        int resident;
        synchronized (memory) { resident = memory.size(); }
        return new Stats(files.downloads.get(), files.diskHits.get(), files.failures.get(), resident);
    }

    public record Stats(long downloads, long diskHits, long failures, int residentBlocks) {}

    @Override public void close() { files.close(); }
}
