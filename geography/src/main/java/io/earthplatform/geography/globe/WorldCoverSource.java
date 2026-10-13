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
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Global land cover from ESA WorldCover 2021 v200, dataset id {@code esa-worldcover-v200}: one
 * class code per pixel of 1/12000 degree (about 10 m), as {@code 10} tree cover, {@code 20}
 * shrubland, {@code 30} grassland, {@code 40} cropland, {@code 50} built-up, {@code 60} bare or
 * sparse vegetation, {@code 70} snow and ice, {@code 80} permanent water, {@code 90} herbaceous
 * wetland, {@code 95} mangroves, {@code 100} moss and lichen, and {@code 0} where there is no data
 * (open ocean has no files at all).
 *
 * <p>The source is the public AWS Open Data bucket: one cloud-optimised GeoTIFF per 3 x 3 degree
 * square, tiled in 1024-pixel deflated blocks with six halving overviews. Only the header and the
 * blocks a caller touches are fetched, with HTTP range requests, and both are kept in an optional
 * disk cache, so a world regenerates from the same bytes offline. Loads are single-flight per
 * block and bounded in concurrency and memory. Callers block; use it from generation workers.
 */
public final class WorldCoverSource implements AutoCloseable {
    public static final String DATASET_ID = "esa-worldcover-v200";
    public static final URI AWS_WORLDCOVER = URI.create("https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/map/");
    public static final String ATTRIBUTION = "ESA WorldCover project 2021 / Contains modified Copernicus Sentinel data (2021) processed by ESA WorldCover consortium (CC BY 4.0)";
    public static final int TREE_COVER = 10, SHRUBLAND = 20, GRASSLAND = 30, CROPLAND = 40, BUILT_UP = 50, BARE = 60,
            SNOW_AND_ICE = 70, WATER = 80, WETLAND = 90, MANGROVES = 95, MOSS_AND_LICHEN = 100, NO_DATA = 0;
    /** Full-resolution pixels per degree. */
    public static final int PIXELS_PER_DEGREE = 12000;
    /** Degrees per file square. */
    public static final int SQUARE = 3;
    /** Coarsest overview: pixels 64 times the full resolution. */
    public static final int MAX_OVERVIEW = 6;
    private static final int HEADER_BYTES = 65536, MAX_HEADER_BYTES = 1 << 20, MAX_BLOCK_BYTES = 4 << 20;

    private final URI base;
    private final Path cacheRoot;
    private final boolean allowNetwork;
    private final int maxBlocksInMemory;
    private final RangeFiles files;
    private final Map<Integer, CompletableFuture<Header>> headers = new ConcurrentHashMap<>();
    private final LinkedHashMap<Long, byte[]> memory = new LinkedHashMap<>(64, 0.75f, true);
    private final Map<Long, CompletableFuture<byte[]>> inFlight = new ConcurrentHashMap<>();
    private final ThreadLocal<long[]> lastKey = ThreadLocal.withInitial(() -> new long[]{-1});
    private final ThreadLocal<byte[][]> lastBlock = ThreadLocal.withInitial(() -> new byte[1][]);

    /**
     * @param base              URI prefix the files are named under
     * @param cacheRoot         optional disk cache directory, or null for memory only
     * @param allowNetwork      false reads only the disk cache
     * @param maxBlocksInMemory decoded blocks kept (each up to 1 MiB)
     */
    public WorldCoverSource(URI base, Path cacheRoot, boolean allowNetwork, int maxBlocksInMemory, int maxConcurrentTransfers, Duration timeout) throws IOException {
        this.base = Objects.requireNonNull(base);
        if (!base.toString().endsWith("/")) throw new IllegalArgumentException("WorldCover base URI must end with /");
        if (maxBlocksInMemory < 1 || maxConcurrentTransfers < 1 || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Invalid land cover source budget");
        if (cacheRoot != null) Files.createDirectories(cacheRoot);
        this.cacheRoot = cacheRoot;
        this.allowNetwork = allowNetwork;
        this.maxBlocksInMemory = maxBlocksInMemory;
        this.files = new RangeFiles("Land cover", DATASET_ID, "earth-worldcover-io", maxConcurrentTransfers, timeout);
    }

    /**
     * The class code at a latitude and longitude, read at an overview (0 for full resolution,
     * each step halving it, up to {@value #MAX_OVERVIEW}); 0 where there is no data.
     */
    public int code(double latitude, double longitude, int overview) {
        if (overview < 0 || overview > MAX_OVERVIEW) throw new IllegalArgumentException("Unsupported WorldCover overview " + overview);
        if (!(Math.abs(latitude) <= 90) || !Double.isFinite(longitude)) throw new IllegalArgumentException("Invalid position " + latitude + ", " + longitude);
        longitude = wrap(longitude);
        int south = Math.floorDiv((int) Math.floor(latitude), SQUARE) * SQUARE, west = Math.floorDiv((int) Math.floor(longitude), SQUARE) * SQUARE;
        if (south >= 90) south = 90 - SQUARE;
        int square = square(south, west);
        // Pixel in the full-resolution grid of this square, rows from its north edge.
        double fx = (longitude - west) * PIXELS_PER_DEGREE, fy = (south + SQUARE - latitude) * PIXELS_PER_DEGREE;
        long full = (long) SQUARE * PIXELS_PER_DEGREE;
        // Overviews are rounded down, so the last full-resolution pixels fold into the last overview pixel.
        long last = (full >> overview) - 1;
        long px = Math.min(last, Math.min(full - 1, (long) fx) >> overview), py = Math.min(last, Math.min(full - 1, Math.max(0, (long) fy)) >> overview);
        long key = blockKey(square, overview, (int) (py >> 10), (int) (px >> 10));
        long[] previous = lastKey.get();
        byte[][] cached = lastBlock.get();
        if (previous[0] != key) {
            cached[0] = block(square, overview, (int) (py >> 10), (int) (px >> 10));
            previous[0] = key;
        }
        byte[] block = cached[0];
        if (block.length == 0) return NO_DATA;
        return block[(int) ((py & 1023) << 10 | (px & 1023))] & 0xFF;
    }

    /** Loads every block an area needs at an overview, several at a time, so later reads never wait. */
    public void prefetch(double south, double west, double north, double east, int overview) {
        double step = 1024.0 * (1 << overview) / PIXELS_PER_DEGREE;
        java.util.List<double[]> points = new java.util.ArrayList<>();
        for (double lat = Math.max(-89.99, south); lat <= Math.min(89.99, north) + step; lat += step)
            for (double lon = west; lon <= east + step; lon += step) points.add(new double[]{Math.min(lat, Math.min(89.99, north)), Math.min(lon, east)});
        points.parallelStream().forEach(p -> code(p[0], p[1], overview));
    }

    // Files and blocks -----------------------------------------------------------------------------

    /** One file's header: per overview, its size, block size and where each block is. */
    private record Level(int width, int height, int tileWidth, int tileHeight, long[] offsets, long[] counts) {}
    private record Header(Level[] levels, boolean deflate) {
        static final Header MISSING = new Header(new Level[0], false);
    }

    private static int square(int south, int west) { return (south + 90) * 1000 + (west + 180); }
    private static int south(int square) { return square / 1000 - 90; }
    private static int west(int square) { return square % 1000 - 180; }
    private static long blockKey(int square, int overview, int row, int column) {
        return (long) square << 32 | (long) overview << 28 | (long) row << 14 | column;
    }

    static String name(int south, int west) {
        return String.format(Locale.ROOT, "ESA_WorldCover_10m_2021_v200_%s%02d%s%03d_Map.tif",
                south < 0 ? "S" : "N", Math.abs(south), west < 0 ? "W" : "E", Math.abs(west));
    }

    private byte[] block(int square, int overview, int row, int column) {
        Header header = header(square);
        if (header.levels.length == 0) return new byte[0];
        Level level = header.levels[overview];
        int across = Math.ceilDiv(level.width, level.tileWidth);
        int index = row * across + column;
        if (index < 0 || index >= level.offsets.length) return new byte[0];
        long key = blockKey(square, overview, row, column);
        synchronized (memory) {
            byte[] hit = memory.get(key);
            if (hit != null) return hit;
        }
        byte[] decoded = files.singleFlight(inFlight, key, () -> readBlock(square, overview, index, header, level), "land cover block");
        synchronized (memory) {
            memory.put(key, decoded);
            var iterator = memory.entrySet().iterator();
            while (memory.size() > maxBlocksInMemory && iterator.hasNext()) { iterator.next(); iterator.remove(); }
        }
        return decoded;
    }

    private Header header(int square) {
        CompletableFuture<Header> mine = new CompletableFuture<>(), shared = headers.putIfAbsent(square, mine);
        if (shared == null) {
            try { mine.complete(readHeader(square)); }
            catch (Throwable failure) {
                files.failures.incrementAndGet();
                mine.completeExceptionally(failure);
                // A failed header is retried by the next caller.
                headers.remove(square, mine);
            }
            shared = mine;
        }
        return files.await(shared, "land cover header " + name(south(square), west(square)));
    }

    private Header readHeader(int square) throws IOException, InterruptedException {
        String name = name(south(square), west(square));
        Path cached = cacheRoot == null ? null : cacheRoot.resolve(name + ".header");
        Path missing = cacheRoot == null ? null : cacheRoot.resolve(name + ".none");
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
        if (!allowNetwork) throw new MissingTerrainException("Land cover " + name + " not cached and network access is disabled");
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
                if (bytes.length < size || size >= MAX_HEADER_BYTES) throw new IOException("Unreadable land cover header " + name);
                size *= 4;
            }
        }
    }

    private byte[] readBlock(int square, int overview, int index, Header header, Level level) throws IOException, InterruptedException {
        String name = name(south(square), west(square));
        Path cached = cacheRoot == null ? null : cacheRoot.resolve(name.replace(".tif", "") + "/" + overview + "/" + index + ".z");
        byte[] raw = null;
        if (cached != null && Files.isRegularFile(cached, LinkOption.NOFOLLOW_LINKS)) {
            raw = Files.readAllBytes(cached);
            try {
                byte[] decoded = decode(raw, header, level);
                files.diskHits.incrementAndGet();
                return decoded;
            } catch (IOException corrupt) {
                Files.deleteIfExists(cached);
            }
        }
        long offset = level.offsets[index], count = level.counts[index];
        // An empty block is all no-data.
        if (offset == 0 || count == 0) return new byte[level.tileWidth * level.tileHeight];
        if (count > MAX_BLOCK_BYTES) throw new MissingTerrainException("Oversized land cover block in " + name);
        if (!allowNetwork) throw new MissingTerrainException("Land cover block of " + name + " not cached and network access is disabled");
        raw = files.fetch(base.resolve(name), offset, (int) count);
        if (raw == null || raw.length != count) throw new MissingTerrainException("Land cover block of " + name + " is missing");
        byte[] decoded = decode(raw, header, level);
        if (cached != null) RangeFiles.write(cached, raw);
        return decoded;
    }

    private static byte[] decode(byte[] raw, Header header, Level level) throws IOException {
        int size = level.tileWidth * level.tileHeight;
        if (!header.deflate) {
            if (raw.length != size) throw new IOException("Unexpected land cover block size");
            return raw;
        }
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(raw);
            byte[] out = new byte[size];
            int done = 0;
            while (done < size && !inflater.finished()) {
                int n = inflater.inflate(out, done, size - done);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                done += n;
            }
            if (done != size) throw new IOException("Short land cover block");
            return out;
        } catch (DataFormatException malformed) {
            throw new IOException("Malformed land cover block", malformed);
        } finally {
            inflater.end();
        }
    }

    private static final class HeaderTooShort extends IOException {
        HeaderTooShort() { super("Header extends past the bytes read"); }
    }

    /** Reads the image file directories of a tiled 8-bit single-band TIFF: the image and its overviews. */
    static Header parse(byte[] bytes) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(bytes);
        if (bytes.length < 8) throw new HeaderTooShort();
        in.order(bytes[0] == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        if (in.getShort(2) != 42) throw new IOException("Not a classic TIFF");
        long next = Integer.toUnsignedLong(in.getInt(4));
        java.util.List<Level> levels = new java.util.ArrayList<>();
        boolean deflate = true;
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
            if (bits != 8 || samples != 1 || predictor != 1 || (compression != 1 && compression != 8 && compression != 32946))
                throw new IOException("Unsupported land cover encoding");
            if (width <= 0 || height <= 0 || tileWidth != 1024 || tileHeight != 1024 || offsets == null || counts == null || offsets.length != counts.length
                    || offsets.length != Math.ceilDiv(width, tileWidth) * Math.ceilDiv(height, tileHeight))
                throw new IOException("Unsupported land cover layout");
            deflate = compression != 1;
            levels.add(new Level(width, height, tileWidth, tileHeight, offsets, counts));
            next = Integer.toUnsignedLong(in.getInt((int) next + 2 + entries * 12));
        }
        if (levels.size() <= MAX_OVERVIEW) throw new IOException("Land cover file lacks overviews");
        return new Header(levels.toArray(Level[]::new), deflate);
    }

    private static long scalar(ByteBuffer in, int at, int type) {
        return type == 3 ? Short.toUnsignedInt(in.getShort(at + 8)) : Integer.toUnsignedLong(in.getInt(at + 8));
    }

    private static long[] array(ByteBuffer in, int length, int at, int type, long count) throws IOException {
        if (type != 3 && type != 4) throw new IOException("Unsupported TIFF array type " + type);
        int size = type == 3 ? 2 : 4;
        if (count > 1 << 20) throw new IOException("Oversized TIFF array");
        long start = count * size <= 4 ? at + 8 : Integer.toUnsignedLong(in.getInt(at + 8));
        if (start + count * size > length) throw new HeaderTooShort();
        long[] values = new long[(int) count];
        for (int i = 0; i < count; i++) {
            int p = (int) (start + (long) i * size);
            values[i] = size == 2 ? Short.toUnsignedInt(in.getShort(p)) : Integer.toUnsignedLong(in.getInt(p));
        }
        return values;
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
