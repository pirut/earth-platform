package io.earthplatform.geography.globe;

import io.earthplatform.geography.MissingTerrainException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
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
 * How often the ground was under water from 1984 to 2021, from the JRC Global Surface Water
 * occurrence layer v1.4, dataset id {@code jrc-gsw-occurrence-v1.4-2021}: a percentage per pixel
 * of 1/4000 degree (about 30 m), 0 where water was never seen, from 80 N to 60 S.
 *
 * <p>The source is the public Google Cloud bucket: one GeoTIFF per 10 x 10 degree square, tiled
 * in 256-pixel LZW blocks, its directory at the end of the file. Only the directory and the
 * blocks a caller touches are fetched, with HTTP range requests, and both are kept in an optional
 * disk cache, so a world regenerates from the same bytes offline. Loads are single-flight per
 * block and bounded in concurrency and memory. Callers block; use it from generation workers.
 */
public final class SurfaceWaterSource implements AutoCloseable {
    public static final String DATASET_ID = "jrc-gsw-occurrence-v1.4-2021";
    public static final URI JRC_GSW = URI.create("https://storage.googleapis.com/global-surface-water/downloads2021/occurrence/");
    public static final String ATTRIBUTION = "JRC Global Surface Water v1.4 (Source: EC JRC/Google; Pekel et al. 2016, Nature 540, 418-422), Copernicus Programme";
    /** Pixels per degree, and degrees per file square. */
    public static final int PIXELS_PER_DEGREE = 4000, SQUARE = 10;
    /** Northern edge of the data, and the southern. */
    public static final int NORTH = 80, SOUTH = -60;
    private static final int TILE = 256, MAX_BLOCK_BYTES = 1 << 20, DIRECTORY_BYTES = 4096;

    private final URI base;
    private final Path cacheRoot;
    private final boolean allowNetwork;
    private final int maxBlocksInMemory;
    private final RangeFiles files;
    private final Map<Integer, CompletableFuture<Index>> indexes = new ConcurrentHashMap<>();
    private final LinkedHashMap<Long, byte[]> memory = new LinkedHashMap<>(64, 0.75f, true);
    private final Map<Long, CompletableFuture<byte[]>> inFlight = new ConcurrentHashMap<>();
    private final ThreadLocal<Last> recent = ThreadLocal.withInitial(Last::new);

    private static final class Last { long key = -1; byte[] block; }

    /** Where one file's blocks are; no blocks for a square without a file. */
    private record Index(int width, int height, int compression, long[] offsets, long[] counts) {
        static final Index MISSING = new Index(0, 0, 1, new long[0], new long[0]);
    }

    /**
     * @param base              URI prefix the files are named under
     * @param cacheRoot         optional disk cache directory, or null for memory only
     * @param allowNetwork      false reads only the disk cache
     * @param maxBlocksInMemory decoded blocks kept (each 64 KiB)
     */
    public SurfaceWaterSource(URI base, Path cacheRoot, boolean allowNetwork, int maxBlocksInMemory, int maxConcurrentTransfers, Duration timeout) throws IOException {
        this.base = Objects.requireNonNull(base);
        if (!base.toString().endsWith("/")) throw new IllegalArgumentException("Surface water base URI must end with /");
        if (maxBlocksInMemory < 1 || maxConcurrentTransfers < 1 || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Invalid surface water source budget");
        if (cacheRoot != null) Files.createDirectories(cacheRoot);
        this.cacheRoot = cacheRoot;
        this.allowNetwork = allowNetwork;
        this.maxBlocksInMemory = maxBlocksInMemory;
        this.files = new RangeFiles("Surface water", DATASET_ID, "earth-surface-water-io", maxConcurrentTransfers, timeout);
    }

    /** Pixels round the globe east to west, and from {@link #NORTH} to {@link #SOUTH}. */
    public static final int ACROSS = 360 * PIXELS_PER_DEGREE, DOWN = (NORTH - SOUTH) * PIXELS_PER_DEGREE;

    /**
     * Occurrence (0 to 100 percent) at a pixel, counted east from 180 W and south from
     * {@value #NORTH} N; east wraps round the globe, and outside the data it is 0.
     */
    public int occurrence(int column, int row) {
        if (row < 0 || row >= DOWN) return 0;
        column = Math.floorMod(column, ACROSS);
        int squareColumn = column / (SQUARE * PIXELS_PER_DEGREE), squareRow = row / (SQUARE * PIXELS_PER_DEGREE);
        int px = column - squareColumn * SQUARE * PIXELS_PER_DEGREE, py = row - squareRow * SQUARE * PIXELS_PER_DEGREE;
        int square = squareRow * 64 + squareColumn;
        int across = Math.ceilDiv(SQUARE * PIXELS_PER_DEGREE, TILE), index = (py / TILE) * across + px / TILE;
        long key = (long) square << 32 | index;
        Last cached = recent.get();
        if (cached.key != key) {
            cached.block = block(square, index);
            cached.key = key;
        }
        byte[] block = cached.block;
        if (block.length == 0) return 0;
        int value = block[(py % TILE) * TILE + px % TILE] & 0xFF;
        return value > 100 ? 0 : value;
    }

    /** Occurrence at a latitude and longitude. */
    public int occurrence(double latitude, double longitude) {
        return occurrence((int) Math.floor((longitude + 180) * PIXELS_PER_DEGREE), (int) Math.floor((NORTH - latitude) * PIXELS_PER_DEGREE));
    }

    static String name(int squareRow, int squareColumn) {
        int north = NORTH - squareRow * SQUARE, west = squareColumn * SQUARE - 180;
        return String.format(Locale.ROOT, "occurrence_%d%s_%d%sv1_4_2021.tif",
                Math.abs(west), west < 0 ? "W" : "E", Math.abs(north), north < 0 ? "S" : "N");
    }

    private byte[] block(int square, int index) {
        Index header = index(square);
        if (index >= header.offsets.length) return new byte[0];
        long key = (long) square << 32 | index;
        synchronized (memory) {
            byte[] hit = memory.get(key);
            if (hit != null) return hit;
        }
        byte[] decoded = files.singleFlight(inFlight, key, () -> readBlock(square, index, header), "surface water block");
        synchronized (memory) {
            memory.put(key, decoded);
            var iterator = memory.entrySet().iterator();
            while (memory.size() > maxBlocksInMemory && iterator.hasNext()) { iterator.next(); iterator.remove(); }
        }
        return decoded;
    }

    private Index index(int square) {
        CompletableFuture<Index> mine = new CompletableFuture<>(), shared = indexes.putIfAbsent(square, mine);
        if (shared == null) {
            try { mine.complete(readIndex(square)); }
            catch (Throwable failure) {
                files.failures.incrementAndGet();
                mine.completeExceptionally(failure);
                indexes.remove(square, mine);
            }
            shared = mine;
        }
        return files.await(shared, "surface water index " + name(square / 64, square % 64));
    }

    private Index readIndex(int square) throws IOException, InterruptedException {
        String name = name(square / 64, square % 64);
        Path cached = cacheRoot == null ? null : cacheRoot.resolve(name + ".index");
        Path missing = cacheRoot == null ? null : cacheRoot.resolve(name + ".none");
        if (missing != null && Files.exists(missing)) return Index.MISSING;
        if (cached != null && Files.isRegularFile(cached, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Index index = load(DiskCache.read(cached));
                files.diskHits.incrementAndGet();
                return index;
            } catch (IOException | RuntimeException corrupt) {
                Files.deleteIfExists(cached);
            }
        }
        if (!allowNetwork) throw new MissingTerrainException("Surface water " + name + " not cached and network access is disabled");
        URI uri = base.resolve(name);
        byte[] head = files.fetch(uri, 0, 8);
        if (head == null) {
            if (missing != null) RangeFiles.write(missing, new byte[0]);
            return Index.MISSING;
        }
        if (head.length < 8) throw new IOException("Short surface water header " + name);
        ByteOrder order = head[0] == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
        ByteBuffer in = ByteBuffer.wrap(head).order(order);
        if (in.getShort(2) != 42) throw new IOException("Not a classic TIFF: " + name);
        long at = Integer.toUnsignedLong(in.getInt(4));
        byte[] directory = files.fetch(uri, at, DIRECTORY_BYTES);
        if (directory == null) throw new IOException("Surface water directory missing in " + name);
        Index index = parse(directory, order, (offset, length) -> {
            byte[] bytes = files.fetch(uri, offset, length);
            if (bytes == null || bytes.length != length) throw new IOException("Short surface water block table in " + name);
            return bytes;
        });
        if (cached != null) RangeFiles.write(cached, save(index));
        return index;
    }

    interface Ranges { byte[] read(long offset, int length) throws IOException, InterruptedException; }

    /** Reads an image file directory of a tiled 8-bit single-band TIFF, whose block tables lie elsewhere in the file. */
    static Index parse(byte[] directory, ByteOrder order, Ranges ranges) throws IOException, InterruptedException {
        ByteBuffer in = ByteBuffer.wrap(directory).order(order);
        int entries = Short.toUnsignedInt(in.getShort(0));
        if (2 + entries * 12 > directory.length) throw new IOException("Oversized surface water directory");
        int width = 0, height = 0, tileWidth = 0, tileHeight = 0, compression = 1, bits = 8, samples = 1, predictor = 1;
        long offsetsAt = -1, countsAt = -1, offsetsCount = 0, countsCount = 0;
        int offsetsType = 4, countsType = 4;
        for (int e = 0; e < entries; e++) {
            int at = 2 + e * 12;
            int tag = Short.toUnsignedInt(in.getShort(at)), type = Short.toUnsignedInt(in.getShort(at + 2));
            long count = Integer.toUnsignedLong(in.getInt(at + 4));
            long value = type == 3 ? Short.toUnsignedInt(in.getShort(at + 8)) : Integer.toUnsignedLong(in.getInt(at + 8));
            switch (tag) {
                case 256 -> width = (int) value;
                case 257 -> height = (int) value;
                case 258 -> bits = (int) value;
                case 259 -> compression = (int) value;
                case 277 -> samples = (int) value;
                case 317 -> predictor = (int) value;
                case 322 -> tileWidth = (int) value;
                case 323 -> tileHeight = (int) value;
                case 324 -> { offsetsAt = Integer.toUnsignedLong(in.getInt(at + 8)); offsetsCount = count; offsetsType = type; }
                case 325 -> { countsAt = Integer.toUnsignedLong(in.getInt(at + 8)); countsCount = count; countsType = type; }
                default -> { }
            }
        }
        if (bits != 8 || samples != 1 || predictor != 1 || (compression != 1 && compression != 5 && compression != 8 && compression != 32946))
            throw new IOException("Unsupported surface water encoding");
        long blocks = (long) Math.ceilDiv(width, Math.max(1, tileWidth)) * Math.ceilDiv(height, Math.max(1, tileHeight));
        if (tileWidth != TILE || tileHeight != TILE || width <= 0 || height <= 0 || offsetsCount != blocks || countsCount != blocks || blocks > 1 << 20)
            throw new IOException("Unsupported surface water layout");
        long[] offsets = table(ranges, offsetsAt, offsetsType, (int) blocks, order), counts = table(ranges, countsAt, countsType, (int) blocks, order);
        return new Index(width, height, compression, offsets, counts);
    }

    private static long[] table(Ranges ranges, long at, int type, int count, ByteOrder order) throws IOException, InterruptedException {
        if (type != 3 && type != 4) throw new IOException("Unsupported TIFF array type " + type);
        int size = type == 3 ? 2 : 4;
        ByteBuffer in = ByteBuffer.wrap(ranges.read(at, count * size)).order(order);
        long[] values = new long[count];
        for (int i = 0; i < count; i++) values[i] = size == 2 ? Short.toUnsignedInt(in.getShort(i * 2)) : Integer.toUnsignedLong(in.getInt(i * 4));
        return values;
    }

    private static byte[] save(Index index) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new java.util.zip.DeflaterOutputStream(bytes))) {
            out.writeInt(index.width); out.writeInt(index.height); out.writeInt(index.compression); out.writeInt(index.offsets.length);
            for (int i = 0; i < index.offsets.length; i++) { out.writeLong(index.offsets[i]); out.writeInt((int) index.counts[i]); }
        }
        return bytes.toByteArray();
    }

    private static Index load(byte[] saved) throws IOException {
        try (DataInputStream in = new DataInputStream(new java.util.zip.InflaterInputStream(new ByteArrayInputStream(saved)))) {
            int width = in.readInt(), height = in.readInt(), compression = in.readInt(), n = in.readInt();
            if (n < 0 || n > 1 << 20) throw new IOException("Corrupt surface water index");
            long[] offsets = new long[n], counts = new long[n];
            for (int i = 0; i < n; i++) { offsets[i] = in.readLong(); counts[i] = Integer.toUnsignedLong(in.readInt()); }
            return new Index(width, height, compression, offsets, counts);
        }
    }

    private byte[] readBlock(int square, int index, Index header) throws IOException, InterruptedException {
        String name = name(square / 64, square % 64);
        Path cached = cacheRoot == null ? null : cacheRoot.resolve(name.replace(".tif", "") + "/" + index + ".lzw");
        if (cached != null && Files.isRegularFile(cached, LinkOption.NOFOLLOW_LINKS)) {
            byte[] raw = DiskCache.read(cached);
            try {
                byte[] decoded = decode(raw, header.compression);
                files.diskHits.incrementAndGet();
                return decoded;
            } catch (IOException corrupt) {
                Files.deleteIfExists(cached);
            }
        }
        long offset = header.offsets[index], count = header.counts[index];
        // An empty block never saw water.
        if (offset == 0 || count == 0) return new byte[0];
        if (count > MAX_BLOCK_BYTES) throw new MissingTerrainException("Oversized surface water block in " + name);
        if (!allowNetwork) throw new MissingTerrainException("Surface water block of " + name + " not cached and network access is disabled");
        byte[] raw = files.fetch(base.resolve(name), offset, (int) count);
        if (raw == null || raw.length != count) throw new MissingTerrainException("Surface water block of " + name + " is missing");
        byte[] decoded = decode(raw, header.compression);
        if (cached != null) RangeFiles.write(cached, raw);
        return decoded;
    }

    static byte[] decode(byte[] raw, int compression) throws IOException {
        int size = TILE * TILE;
        byte[] out = switch (compression) {
            case 1 -> raw.clone();
            case 5 -> lzw(raw, size);
            default -> inflate(raw, size);
        };
        if (out.length != size) throw new IOException("Short surface water block");
        return out;
    }

    /**
     * Decodes TIFF LZW (most significant bit first, codes widening one step early, 256 clears and
     * 257 ends) into exactly {@code size} bytes.
     */
    static byte[] lzw(byte[] in, int size) throws IOException {
        byte[] out = new byte[size];
        // Each code's string is its prefix code's string and one last byte.
        int[] prefix = new int[4096];
        byte[] last = new byte[4096], first = new byte[4096];
        int[] length = new int[4096];
        for (int i = 0; i < 256; i++) { last[i] = first[i] = (byte) i; length[i] = 1; prefix[i] = -1; }
        int next = 258, width = 9, done = 0, previous = -1;
        long bits = 0;
        int held = 0, at = 0;
        while (done < size) {
            while (held < width) {
                if (at >= in.length) return finish(out, done, size);
                bits = bits << 8 | (in[at++] & 0xFF);
                held += 8;
            }
            int code = (int) (bits >>> (held - width)) & ((1 << width) - 1);
            held -= width;
            if (code == 257) break;
            if (code == 256) { next = 258; width = 9; previous = -1; continue; }
            if (previous == -1) {
                if (code > 255) throw new IOException("Malformed surface water block");
                out[done++] = (byte) code;
                previous = code;
                continue;
            }
            int entry;
            if (code < next) {
                entry = code;
                if (next < 4096) { prefix[next] = previous; last[next] = first[code]; first[next] = first[previous]; length[next] = length[previous] + 1; next++; }
            } else if (code == next && next < 4096) {
                prefix[next] = previous; last[next] = first[previous]; first[next] = first[previous]; length[next] = length[previous] + 1;
                entry = next++;
            } else throw new IOException("Malformed surface water block");
            int n = length[entry];
            if (done + n > size) n = size - done;
            // Write the string back to front, dropping any bytes past the block's end.
            int skip = length[entry] - n, c = entry;
            for (int s = 0; s < skip; s++) c = prefix[c];
            for (int i = done + n - 1; i >= done; i--) { out[i] = last[c]; c = prefix[c]; }
            done += n;
            previous = entry;
            if (next + 1 >= 1 << width && width < 12) width++;
        }
        return finish(out, done, size);
    }

    private static byte[] finish(byte[] out, int done, int size) throws IOException {
        if (done != size) throw new IOException("Short surface water block");
        return out;
    }

    private static byte[] inflate(byte[] raw, int size) throws IOException {
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
            return finish(out, done, size);
        } catch (DataFormatException malformed) {
            throw new IOException("Malformed surface water block", malformed);
        } finally {
            inflater.end();
        }
    }

    public WorldCoverSource.Stats stats() {
        int resident;
        synchronized (memory) { resident = memory.size(); }
        return new WorldCoverSource.Stats(files.downloads.get(), files.diskHits.get(), files.failures.get(), resident);
    }

    @Override public void close() { files.close(); }
}
