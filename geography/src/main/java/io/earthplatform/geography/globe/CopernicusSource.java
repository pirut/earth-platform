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
 * Global elevation from the Copernicus DEM GLO-30, dataset id {@code copernicus-glo30-v1}: a
 * surface model from the TanDEM-X mission, in metres above the EGM2008 geoid, with the sea at
 * exactly 0 m and lakes and rivers flattened to their water level.
 *
 * <p>The source is the public AWS Open Data bucket: one cloud-optimised GeoTIFF of 32-bit floats
 * per 1 x 1 degree square, 3600 rows by 3600 columns at the equator and fewer columns toward the
 * poles, tiled in 1024-pixel deflated blocks with three halving overviews. Squares of open ocean
 * have no file. Only the header and the blocks a caller touches are fetched, with HTTP range
 * requests, and both are kept in an optional disk cache, so a world regenerates from the same
 * bytes offline. Callers block; use it from generation workers.
 */
public final class CopernicusSource implements AutoCloseable {
    public static final String DATASET_ID = "copernicus-glo30-v1";
    public static final URI AWS_COPERNICUS = URI.create("https://copernicus-dem-30m.s3.amazonaws.com/");
    /** The notice the licence asks for wherever the data reaches the public adapted or modified (Article 6 b). */
    public static final String ATTRIBUTION = "produced using Copernicus WorldDEM-30 © DLR e.V. 2010-2014 and © Airbus Defence and Space GmbH 2014-2018 "
            + "provided under COPERNICUS by the European Union and ESA; all rights reserved";
    /** Coarsest overview: pixels 8 times the full resolution. */
    public static final int MAX_OVERVIEW = 3;
    /** Full-resolution rows per degree. */
    public static final int ROWS_PER_DEGREE = 3600;
    private static final int HEADER_BYTES = 16384, MAX_HEADER_BYTES = 1 << 20, MAX_BLOCK_BYTES = 8 << 20;

    private final URI base;
    private final Path cacheRoot;
    private final boolean allowNetwork;
    private final int maxBlocksInMemory;
    private final RangeFiles files;
    private final Map<Integer, CompletableFuture<Header>> headers = new ConcurrentHashMap<>();
    private final LinkedHashMap<Long, Block> memory = new LinkedHashMap<>(32, 0.75f, true);
    private final Map<Long, CompletableFuture<Block>> inFlight = new ConcurrentHashMap<>();
    /** Each thread's last block, so reads that stay in one block skip the shared cache. */
    private final ThreadLocal<Last> recent = ThreadLocal.withInitial(Last::new);

    private static final class Last { long key = -1; Block block; }

    /** A decoded block, cut to the part inside the image: {@code width} x {@code height} metres. */
    private record Block(int width, int height, float[] metres) {}

    /**
     * @param base              URI prefix the files are named under
     * @param cacheRoot         optional disk cache directory, or null for memory only
     * @param allowNetwork      false reads only the disk cache
     * @param maxBlocksInMemory decoded blocks kept (each up to 4 MiB)
     */
    public CopernicusSource(URI base, Path cacheRoot, boolean allowNetwork, int maxBlocksInMemory, int maxConcurrentTransfers, Duration timeout) throws IOException {
        this.base = Objects.requireNonNull(base);
        if (!base.toString().endsWith("/")) throw new IllegalArgumentException("Copernicus base URI must end with /");
        if (maxBlocksInMemory < 1 || maxConcurrentTransfers < 1 || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Invalid Copernicus source budget");
        if (cacheRoot != null) Files.createDirectories(cacheRoot);
        this.cacheRoot = cacheRoot;
        this.allowNetwork = allowNetwork;
        this.maxBlocksInMemory = maxBlocksInMemory;
        this.files = new RangeFiles("Copernicus elevation", DATASET_ID, "earth-copernicus-io", maxConcurrentTransfers, timeout);
    }

    /** Whether the degree square holding a point has a file; squares of open ocean have none. */
    public boolean covers(double latitude, double longitude) {
        longitude = wrap(longitude);
        return header(square(south(latitude), (int) Math.floor(longitude))).levels.length > 0;
    }

    /** Pixels across and down the degree square holding a point at an overview, or null where it has no file. */
    int[] size(double latitude, double longitude, int overview) {
        Header header = header(square(south(latitude), (int) Math.floor(wrap(longitude))));
        if (header.levels.length == 0) return null;
        Level level = header.levels[overview];
        return new int[]{level.width, level.height};
    }

    /**
     * Metres at a latitude and longitude, bilinear between pixel centres at an overview (0 for full
     * resolution, each step halving it, up to {@value #MAX_OVERVIEW}), reaching into the squares
     * around at the edges; NaN where the square has no file. A neighbouring square with no file is
     * open sea, 0 m.
     */
    public double metres(double latitude, double longitude, int overview) {
        if (overview < 0 || overview > MAX_OVERVIEW) throw new IllegalArgumentException("Unsupported Copernicus overview " + overview);
        if (!(Math.abs(latitude) <= 90) || !Double.isFinite(longitude)) throw new IllegalArgumentException("Invalid position " + latitude + ", " + longitude);
        longitude = wrap(longitude);
        int south = south(latitude), west = (int) Math.floor(longitude);
        Header header = header(square(south, west));
        if (header.levels.length == 0) return Double.NaN;
        Level level = header.levels[overview];
        double fy = (south + 1 - latitude) * level.height - 0.5, fx = (longitude - west) * level.width - 0.5;
        int y0 = (int) Math.floor(fy), x0 = (int) Math.floor(fx);
        double ty = fy - y0, tx = fx - x0;
        double v00, v01, v10, v11;
        if (y0 >= 0 && x0 >= 0 && y0 + 1 < level.height && x0 + 1 < level.width) {
            v00 = pixel(south, west, overview, level, y0, x0); v01 = pixel(south, west, overview, level, y0, x0 + 1);
            v10 = pixel(south, west, overview, level, y0 + 1, x0); v11 = pixel(south, west, overview, level, y0 + 1, x0 + 1);
        } else {
            v00 = spill(south, west, overview, level, y0, x0); v01 = spill(south, west, overview, level, y0, x0 + 1);
            v10 = spill(south, west, overview, level, y0 + 1, x0); v11 = spill(south, west, overview, level, y0 + 1, x0 + 1);
        }
        return (v00 * (1 - tx) + v01 * tx) * (1 - ty) + (v10 * (1 - tx) + v11 * tx) * ty;
    }

    /**
     * A pixel of a square by row and column, either of which may lie past its edge: then the
     * nearest pixel to that place in the square there, whose columns may be fewer or more.
     */
    private double spill(int south, int west, int overview, Level level, int row, int column) {
        if (row >= 0 && column >= 0 && row < level.height && column < level.width) return pixel(south, west, overview, level, row, column);
        double latitude = south + 1 - (row + 0.5) / level.height, longitude = west + (column + 0.5) / level.width;
        if (latitude >= 90 || latitude <= -90) return pixel(south, west, overview, level, Math.max(0, Math.min(level.height - 1, row)), Math.max(0, Math.min(level.width - 1, column)));
        longitude = wrap(longitude);
        int s = south(latitude), w = (int) Math.floor(longitude);
        Header header = header(square(s, w));
        if (header.levels.length == 0) return 0;
        Level there = header.levels[overview];
        int r = (int) Math.max(0, Math.min(there.height - 1, Math.floor((s + 1 - latitude) * there.height)));
        int c = (int) Math.max(0, Math.min(there.width - 1, Math.floor((longitude - w) * there.width)));
        return pixel(s, w, overview, there, r, c);
    }

    private float pixel(int south, int west, int overview, Level level, int row, int column) {
        int square = square(south, west);
        int blockRow = row / level.tileHeight, blockColumn = column / level.tileWidth;
        long key = blockKey(square, overview, blockRow, blockColumn);
        Last cached = recent.get();
        if (cached.key != key) {
            cached.block = block(square, overview, level, blockRow, blockColumn);
            cached.key = key;
        }
        Block block = cached.block;
        return block.metres[(row - blockRow * level.tileHeight) * block.width + column - blockColumn * level.tileWidth];
    }

    // Files and blocks -----------------------------------------------------------------------------

    /** One file's overview: its size, block size and where each block is. */
    private record Level(int width, int height, int tileWidth, int tileHeight, long[] offsets, long[] counts) {}
    private record Header(Level[] levels, boolean deflate, boolean predicted, boolean bigEndian) {
        static final Header MISSING = new Header(new Level[0], false, false, false);
    }

    private static int south(double latitude) { return Math.min(89, (int) Math.floor(latitude)); }
    private static int square(int south, int west) { return (south + 90) * 1000 + (west + 180); }
    private static int south(int square) { return square / 1000 - 90; }
    private static int west(int square) { return square % 1000 - 180; }
    private static long blockKey(int square, int overview, int row, int column) {
        return (long) square << 32 | (long) overview << 28 | (long) row << 14 | column;
    }

    static String name(int south, int west) {
        return String.format(Locale.ROOT, "Copernicus_DSM_COG_10_%s%02d_00_%s%03d_00_DEM",
                south < 0 ? "S" : "N", Math.abs(south), west < 0 ? "W" : "E", Math.abs(west));
    }

    private Block block(int square, int overview, Level level, int row, int column) {
        long key = blockKey(square, overview, row, column);
        synchronized (memory) {
            Block hit = memory.get(key);
            if (hit != null) return hit;
        }
        Header header = header(square);
        int across = Math.ceilDiv(level.width, level.tileWidth), index = row * across + column;
        Block decoded = files.singleFlight(inFlight, key, () -> readBlock(square, overview, index, row, column, header, level), "Copernicus elevation block");
        synchronized (memory) {
            memory.put(key, decoded);
            var iterator = memory.entrySet().iterator();
            while (memory.size() > maxBlocksInMemory && iterator.hasNext()) { iterator.next(); iterator.remove(); }
        }
        return decoded;
    }

    private Header header(int square) {
        CompletableFuture<Header> known = headers.get(square);
        // Every read asks for its square's header: skip the wait once it is in.
        if (known != null && known.isDone() && !known.isCompletedExceptionally()) return known.join();
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
        return files.await(shared, "Copernicus elevation header " + name(south(square), west(square)));
    }

    private URI uri(String name) { return base.resolve(name + "/" + name + ".tif"); }

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
        if (!allowNetwork) throw new MissingTerrainException("Copernicus elevation " + name + " not cached and network access is disabled");
        int size = HEADER_BYTES;
        while (true) {
            byte[] bytes = files.fetch(uri(name), 0, size);
            if (bytes == null) {
                if (missing != null) RangeFiles.write(missing, new byte[0]);
                return Header.MISSING;
            }
            try {
                Header header = parse(bytes);
                if (cached != null) RangeFiles.write(cached, bytes);
                return header;
            } catch (HeaderTooShort tooShort) {
                if (bytes.length < size || size >= MAX_HEADER_BYTES) throw new IOException("Unreadable Copernicus header " + name);
                size *= 4;
            }
        }
    }

    private Block readBlock(int square, int overview, int index, int row, int column, Header header, Level level) throws IOException, InterruptedException {
        String name = name(south(square), west(square));
        int width = Math.min(level.tileWidth, level.width - column * level.tileWidth), height = Math.min(level.tileHeight, level.height - row * level.tileHeight);
        Path cached = cacheRoot == null ? null : cacheRoot.resolve(name + "/" + overview + "/" + index + ".z");
        if (cached != null && Files.isRegularFile(cached, LinkOption.NOFOLLOW_LINKS)) {
            byte[] raw = Files.readAllBytes(cached);
            try {
                Block decoded = new Block(width, height, decode(raw, header, level, width, height));
                files.diskHits.incrementAndGet();
                return decoded;
            } catch (IOException corrupt) {
                Files.deleteIfExists(cached);
            }
        }
        if (index >= level.offsets.length) throw new MissingTerrainException("Copernicus block outside " + name);
        long offset = level.offsets[index], count = level.counts[index];
        // An empty block is all sea.
        if (offset == 0 || count == 0) return new Block(width, height, new float[width * height]);
        if (count > MAX_BLOCK_BYTES) throw new MissingTerrainException("Oversized Copernicus block in " + name);
        if (!allowNetwork) throw new MissingTerrainException("Copernicus block of " + name + " not cached and network access is disabled");
        byte[] raw = files.fetch(uri(name), offset, (int) count);
        if (raw == null || raw.length != count) throw new MissingTerrainException("Copernicus block of " + name + " is missing");
        Block decoded = new Block(width, height, decode(raw, header, level, width, height));
        if (cached != null) RangeFiles.write(cached, raw);
        return decoded;
    }

    /**
     * A block's metres, cut to {@code width} x {@code height}. With the floating-point predictor
     * each row holds its values' bytes as four planes, most significant first, each byte stored as
     * its difference from the byte before it along the row.
     */
    static float[] decode(byte[] raw, Header header, Level level, int width, int height) throws IOException {
        int rowBytes = level.tileWidth * 4, size = rowBytes * level.tileHeight;
        byte[] bytes;
        if (!header.deflate) {
            if (raw.length != size) throw new IOException("Unexpected Copernicus block size");
            bytes = raw;
        } else {
            Inflater inflater = new Inflater();
            try {
                inflater.setInput(raw);
                bytes = new byte[size];
                int done = 0;
                while (done < size && !inflater.finished()) {
                    int n = inflater.inflate(bytes, done, size - done);
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                    done += n;
                }
                if (done != size) throw new IOException("Short Copernicus block");
            } catch (DataFormatException malformed) {
                throw new IOException("Malformed Copernicus block", malformed);
            } finally {
                inflater.end();
            }
        }
        float[] metres = new float[width * height];
        int tw = level.tileWidth;
        for (int y = 0; y < height; y++) {
            int at = y * rowBytes;
            if (header.predicted) {
                for (int i = 1; i < rowBytes; i++) bytes[at + i] += bytes[at + i - 1];
                for (int x = 0; x < width; x++)
                    metres[y * width + x] = Float.intBitsToFloat((bytes[at + x] & 0xFF) << 24 | (bytes[at + tw + x] & 0xFF) << 16
                            | (bytes[at + 2 * tw + x] & 0xFF) << 8 | bytes[at + 3 * tw + x] & 0xFF);
            } else {
                ByteBuffer row = ByteBuffer.wrap(bytes).order(header.bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
                for (int x = 0; x < width; x++) metres[y * width + x] = row.getFloat(at + 4 * x);
            }
        }
        return metres;
    }

    private static final class HeaderTooShort extends IOException {
        HeaderTooShort() { super("Header extends past the bytes read"); }
    }

    /** Reads the image file directories of a tiled 32-bit float single-band TIFF: the image and its overviews. */
    static Header parse(byte[] bytes) throws IOException {
        ByteBuffer in = ByteBuffer.wrap(bytes);
        if (bytes.length < 8) throw new HeaderTooShort();
        in.order(bytes[0] == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        if (in.getShort(2) != 42) throw new IOException("Not a classic TIFF");
        long next = Integer.toUnsignedLong(in.getInt(4));
        java.util.List<Level> levels = new java.util.ArrayList<>();
        boolean deflate = true, predicted = false;
        while (next != 0 && levels.size() <= MAX_OVERVIEW) {
            if (next + 2 > bytes.length) throw new HeaderTooShort();
            int entries = Short.toUnsignedInt(in.getShort((int) next));
            if (next + 2 + entries * 12L + 4 > bytes.length) throw new HeaderTooShort();
            int width = 0, height = 0, tileWidth = 0, tileHeight = 0, compression = 1, bits = 32, samples = 1, predictor = 1, format = 1;
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
                    case 339 -> format = (int) scalar(in, at, type);
                    default -> { }
                }
            }
            if (bits != 32 || samples != 1 || format != 3 || (predictor != 1 && predictor != 3) || (compression != 1 && compression != 8 && compression != 32946))
                throw new IOException("Unsupported Copernicus encoding");
            if (width <= 0 || height <= 0 || tileWidth <= 0 || tileHeight <= 0 || tileWidth > 4096 || tileHeight > 4096 || offsets == null || counts == null
                    || offsets.length != counts.length || offsets.length != Math.ceilDiv(width, tileWidth) * Math.ceilDiv(height, tileHeight))
                throw new IOException("Unsupported Copernicus layout");
            deflate = compression != 1;
            predicted = predictor == 3;
            levels.add(new Level(width, height, tileWidth, tileHeight, offsets, counts));
            next = Integer.toUnsignedLong(in.getInt((int) next + 2 + entries * 12));
        }
        if (levels.size() <= MAX_OVERVIEW) throw new IOException("Copernicus file lacks overviews");
        return new Header(levels.toArray(Level[]::new), deflate, predicted, in.order() == ByteOrder.BIG_ENDIAN);
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

    static double wrap(double longitude) {
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
