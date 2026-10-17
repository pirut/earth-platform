package io.earthplatform.geography.globe;

import io.earthplatform.geography.MissingTerrainException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A national elevation source served as Web Mercator tiles of 256 pixels, the composite's own grid,
 * laid over the composite where its {@link TileCoverage} says it has data. A tile reads its
 * source's tiles in order, finest first: each pixel takes the first that has data there, read at
 * the tile's own pixels or, from a coarser source tile, bilinear across its pixels. Coarse tiles,
 * read far out, are left to the composite, so a first visit downloads little more than before.
 *
 * <p>Like {@link UsgsElevation}, the source eases in over a few pixels from the edge of its data;
 * open sea (exactly 0) stays sea unless the source has data all around and stands well above it;
 * and the composite's bathymetry stays where the source reads sea level or below. Source tiles are
 * kept on disk, and a tile the server lacks is remembered as lacking.
 */
abstract class MercatorTileLayer implements ElevationLayer, AutoCloseable {
    static final int SIZE = TerrariumTileSource.TILE_SIZE;
    /** Pixels over which the source eases in from the edge of its data. */
    private static final int EASE = 3;
    /** Metres the source must read where the composite has exactly 0 for that 0 to be a void, not sea. */
    static final double VOID_ABOVE_SEA = UsgsElevation.VOID_ABOVE_SEA;
    private static final int MAX_BYTES = 4 << 20;

    /** One source tile to read: its zoom and position, named for the disk cache and fetched from {@code uri}. */
    record Piece(String product, int zoom, int x, int y, URI uri) {
        String file() { return product + "/" + zoom + "/" + x + "/" + y; }
    }

    private final String id, what;
    private final TileCoverage coverage;
    private final int minZoom;
    private final Path cacheRoot;
    private final boolean allowNetwork;
    private final RangeFiles files;
    private final Map<String, CompletableFuture<float[]>> inFlight = new ConcurrentHashMap<>();
    private final LruMap<String, float[]> memory = new LruMap<>(32);
    private final int kept;

    MercatorTileLayer(String id, String what, String threadName, TileCoverage coverage, int minZoom, Path cacheRoot, boolean allowNetwork,
                      int maxTilesInMemory, int maxConcurrentTransfers, Duration timeout) throws IOException {
        this.id = id;
        this.what = what;
        this.coverage = coverage;
        this.minZoom = minZoom;
        if (maxTilesInMemory < 1 || maxConcurrentTransfers < 1 || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Invalid " + what + " source budget");
        if (cacheRoot != null) Files.createDirectories(cacheRoot);
        this.cacheRoot = cacheRoot;
        this.allowNetwork = allowNetwork;
        this.kept = maxTilesInMemory;
        this.files = new RangeFiles(what, id, threadName, maxConcurrentTransfers, timeout);
    }

    /** The source tiles a tile reads, finest first; all of zoom {@code zoom} or coarser. */
    abstract List<Piece> pieces(int zoom, int x, int y);

    /** A source tile's metres from its bytes, row by row from the north; NaN where it has no data. */
    abstract float[] decode(Piece piece, byte[] bytes) throws IOException;

    /** Whether a source tile is kept on disk gzipped, as raw floats are. */
    boolean gzip() { return false; }

    @Override public String id() { return id; }

    /** Whether a tile is read from this source at all. */
    boolean reads(int zoom, int x, int y) { return zoom >= minZoom && coverage.covers(zoom, x, y); }

    @Override public void prefetch(int zoom, int x, int y) {
        if (!reads(zoom, x, y)) return;
        load(pieces(zoom, x, y));
    }

    @Override public void refine(int zoom, int x, int y, float[] values) {
        if (!reads(zoom, x, y)) return;
        List<Piece> pieces = pieces(zoom, x, y);
        List<float[]> sources = load(pieces);
        float[] metres = new float[SIZE * SIZE];
        java.util.Arrays.fill(metres, Float.NaN);
        boolean any = false;
        for (int p = 0; p < pieces.size(); p++) {
            float[] source = sources.get(p);
            if (source != null) any |= fill(metres, source, zoom, x, y, pieces.get(p));
        }
        if (any) blend(values, metres);
    }

    /** Fills the pixels still without data from a source tile; whether any was filled. */
    private static boolean fill(float[] metres, float[] source, int zoom, int x, int y, Piece piece) {
        boolean any = false;
        int shift = zoom - piece.zoom();
        if (shift == 0) {
            for (int at = 0; at < metres.length; at++)
                if (Float.isNaN(metres[at]) && !Float.isNaN(source[at])) { metres[at] = source[at]; any = true; }
            return any;
        }
        // This tile is a part of the coarser source tile: its pixel centres, in the source's pixels.
        double scale = 1.0 / (1 << shift);
        double left = (x - ((long) piece.x() << shift)) * SIZE * scale, top = (y - ((long) piece.y() << shift)) * SIZE * scale;
        for (int j = 0; j < SIZE; j++) {
            double fy = top + (j + 0.5) * scale - 0.5;
            for (int i = 0; i < SIZE; i++) {
                int at = j * SIZE + i;
                if (!Float.isNaN(metres[at])) continue;
                double value = bilinear(source, left + (i + 0.5) * scale - 0.5, fy);
                if (!Double.isNaN(value)) { metres[at] = (float) value; any = true; }
            }
        }
        return any;
    }

    /** Bilinear over the valid pixels of a source tile, edges standing in for pixels past them. */
    static double bilinear(float[] source, double fx, double fy) {
        int i = (int) Math.floor(fx), j = (int) Math.floor(fy);
        double tx = fx - i, ty = fy - j, sum = 0, weights = 0;
        for (int dj = 0; dj <= 1; dj++) for (int di = 0; di <= 1; di++) {
            double w = (di == 0 ? 1 - tx : tx) * (dj == 0 ? 1 - ty : ty);
            if (w == 0) continue;
            int column = Math.clamp(i + di, 0, SIZE - 1), row = Math.clamp(j + dj, 0, SIZE - 1);
            float v = source[row * SIZE + column];
            if (Float.isNaN(v)) continue;
            sum += w * v;
            weights += w;
        }
        return weights > 1e-9 ? sum / weights : Double.NaN;
    }

    /**
     * Lays the source's metres over the tile, easing in over {@link #EASE} pixels from the edge of
     * its data: a pixel's weight comes from the share of the pixels around it that have data.
     */
    static void blend(float[] values, float[] metres) {
        // Counts of pixels with data in each pixel's square, from a summed-area table.
        int side = SIZE + 1;
        int[] sums = new int[side * side];
        for (int j = 0; j < SIZE; j++) {
            int row = 0;
            for (int i = 0; i < SIZE; i++) {
                row += Float.isNaN(metres[j * SIZE + i]) ? 0 : 1;
                sums[(j + 1) * side + i + 1] = sums[j * side + i + 1] + row;
            }
        }
        for (int j = 0; j < SIZE; j++) {
            int top = Math.max(0, j - EASE), bottom = Math.min(SIZE, j + EASE + 1);
            for (int i = 0; i < SIZE; i++) {
                int at = j * SIZE + i;
                float source = metres[at];
                if (Float.isNaN(source)) continue;
                int left = Math.max(0, i - EASE), right = Math.min(SIZE, i + EASE + 1);
                int count = sums[bottom * side + right] - sums[top * side + right] - sums[bottom * side + left] + sums[top * side + left];
                double share = (double) count / ((bottom - top) * (right - left));
                if (share <= 0.5) continue;
                float own = values[at];
                // The source has no bathymetry; the composite's stays.
                if (source <= 0 && own < 0) continue;
                // Exactly 0 is open sea to the generator, and stays sea unless the source has data all around it and
                // stands well above the sea there, filling the composite's voids inland but not its coasts.
                if (own == 0 && (share < 1 || source < VOID_ABOVE_SEA)) continue;
                double s = Math.min(1, (share - 0.5) * 2), weight = s * s * (3 - 2 * s);
                float out = (float) (own + weight * (source - own));
                values[at] = out == 0 ? Float.MIN_VALUE : out;
            }
        }
    }

    /** The source tiles, read at once; null for one the server lacks. */
    private List<float[]> load(List<Piece> pieces) {
        List<float[]> out = new ArrayList<>(java.util.Collections.nCopies(pieces.size(), null));
        if (pieces.size() == 1) {
            out.set(0, tile(pieces.get(0)));
            return out;
        }
        List<Runnable> loads = new ArrayList<>();
        for (int p = 0; p < pieces.size(); p++) {
            int at = p;
            loads.add(() -> out.set(at, tile(pieces.get(at))));
        }
        RangeFiles.all(loads);
        return out;
    }

    private static final float[] NONE = new float[0], BROKEN = new float[0];

    private float[] tile(Piece piece) {
        String key = piece.file();
        synchronized (memory) {
            float[] hit = memory.get(key);
            if (hit != null) return hit == NONE ? null : hit;
        }
        float[] read = files.singleFlight(inFlight, key, () -> {
            float[] decoded = read(piece);
            if (decoded == BROKEN) return BROKEN;
            float[] kept = decoded == null ? NONE : decoded;
            synchronized (memory) {
                memory.putAndTrim(key, kept, this.kept);
            }
            return kept;
        }, what + " tile " + key);
        if (read == BROKEN) throw new IllegalStateException(what + " answered " + piece.uri() + " with something other than a tile");
        return read == NONE ? null : read;
    }

    /** A source tile from the disk cache or else the server; null where it has none, or no data at all. */
    private float[] read(Piece piece) throws IOException, InterruptedException {
        Path cached = cacheRoot == null ? null : cacheRoot.resolve(piece.file() + (gzip() ? ".gz" : ".tile"));
        Path none = cacheRoot == null ? null : cacheRoot.resolve(piece.file() + ".none");
        if (none != null && Files.exists(none, LinkOption.NOFOLLOW_LINKS)) {
            DiskCache.used(none);
            files.diskHits.incrementAndGet();
            return null;
        }
        if (cached != null && Files.isRegularFile(cached, LinkOption.NOFOLLOW_LINKS)) {
            try {
                byte[] bytes = DiskCache.read(cached);
                float[] decoded = decode(piece, gzip() ? gunzip(bytes) : bytes);
                files.diskHits.incrementAndGet();
                return decoded;
            } catch (IOException | RuntimeException corrupt) {
                Files.deleteIfExists(cached);
            }
        }
        if (!allowNetwork) throw new MissingTerrainException(what + " tile " + piece.file() + " not cached and network access is disabled");
        byte[] bytes = files.fetch(piece.uri(), 0, MAX_BYTES);
        float[] decoded;
        try {
            decoded = bytes == null ? null : decode(piece, bytes);
        } catch (IOException malformed) {
            // The server answered with something other than a tile: not a missing tile to try again
            // later, but a source that is not working, which leaves the tile to the composite.
            return BROKEN;
        }
        if (decoded != null) {
            boolean any = false;
            for (float v : decoded) if (!Float.isNaN(v)) { any = true; break; }
            if (!any) decoded = null;
        }
        if (decoded == null) {
            if (none != null) DiskCache.write(none, new byte[0]);
            return null;
        }
        if (cached != null) DiskCache.write(cached, gzip() ? gzipped(bytes) : bytes);
        return decoded;
    }

    private static byte[] gzipped(byte[] bytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length / 2);
        try (GZIPOutputStream zip = new GZIPOutputStream(out)) { zip.write(bytes); }
        return out.toByteArray();
    }

    private static byte[] gunzip(byte[] bytes) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) { return in.readAllBytes(); }
    }

    /** Latitude in degrees of a Web Mercator pixel row. */
    static double latitude(double world, double row) {
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * row / world))));
    }

    public RangeStats stats() { return new RangeStats(files.downloads.get(), files.diskHits.get(), files.failures.get()); }

    public record RangeStats(long downloads, long diskHits, long failures) {}

    @Override public void close() { files.close(); }
}
