package io.earthplatform.geography.globe;

import io.earthplatform.geography.MissingTerrainException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import javax.imageio.ImageIO;

/**
 * True-colour satellite imagery of the whole land surface, dataset id {@code s2cloudless-2016-v1}:
 * the EOX Sentinel-2 cloudless mosaic of 2016, a cloud-free composite of a year of Sentinel-2
 * scenes at 10 m, as Web Mercator JPEG tiles of 256 pixels. Zoom {@value #MAX_ZOOM} holds the full
 * detail (about 10 m a pixel at the equator, finer toward the poles); coarser zooms serve far
 * terrain. The 2016 mosaic is the one under CC BY 4.0; later years are non-commercial only.
 *
 * <p>Tiles with no imagery (the open sea) come back from the server as a tiny placeholder PNG and
 * read as no data. Loads are single-flight per tile, bounded in concurrency and memory, and kept in
 * an optional disk cache so a world regenerates from the same bytes offline. Callers block; use it
 * from generation workers.
 */
public final class ImagerySource implements AutoCloseable {
    public static final String DATASET_ID = "s2cloudless-2016-v1";
    public static final URI EOX_S2_CLOUDLESS_2016 = URI.create("https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless_3857/default/g/");
    public static final String ATTRIBUTION = "Sentinel-2 cloudless - https://s2maps.eu by EOX IT Services GmbH (Contains modified Copernicus Sentinel data 2016) (CC BY 4.0)";
    public static final int TILE_SIZE = 256, MAX_ZOOM = 14;
    /** A pixel with no imagery. */
    public static final int NO_DATA = -1;
    private static final int[] EMPTY = new int[0];
    private static final int MAX_TILE_BYTES = 1 << 20;

    private final URI base;
    private final Path cacheRoot;
    private final boolean allowNetwork;
    private final int maxTilesInMemory;
    private final RangeFiles files;
    private final LinkedHashMap<Long, int[]> memory = new LinkedHashMap<>(64, 0.75f, true);
    private final Map<Long, CompletableFuture<int[]>> inFlight = new ConcurrentHashMap<>();
    private final ThreadLocal<long[]> lastKey = ThreadLocal.withInitial(() -> new long[]{-1});
    private final ThreadLocal<int[][]> lastTile = ThreadLocal.withInitial(() -> new int[1][]);

    /**
     * @param base             URI prefix; tiles are fetched from {@code base + z/y/x.jpg}
     * @param cacheRoot        optional disk cache directory, or null for memory only
     * @param allowNetwork     false reads only the disk cache
     * @param maxTilesInMemory decoded tiles kept (each 256 KiB)
     */
    public ImagerySource(URI base, Path cacheRoot, boolean allowNetwork, int maxTilesInMemory, int maxConcurrentTransfers, Duration timeout) throws IOException {
        this.base = Objects.requireNonNull(base);
        if (!base.toString().endsWith("/")) throw new IllegalArgumentException("Imagery base URI must end with /");
        if (maxTilesInMemory < 1 || maxConcurrentTransfers < 1 || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Invalid imagery source budget");
        if (cacheRoot != null) Files.createDirectories(cacheRoot);
        this.cacheRoot = cacheRoot;
        this.allowNetwork = allowNetwork;
        this.maxTilesInMemory = maxTilesInMemory;
        this.files = new RangeFiles("Imagery", DATASET_ID, "earth-imagery-io", maxConcurrentTransfers, timeout);
    }

    /** Packed RGB pixels, rows north to south, 256×256, or an empty array where there is no imagery. Shared; do not modify. */
    public int[] tile(int zoom, int x, int y) {
        if (zoom < 0 || zoom > MAX_ZOOM) throw new IllegalArgumentException("Unsupported imagery zoom " + zoom);
        int n = 1 << zoom;
        if (x < 0 || x >= n || y < 0 || y >= n) throw new IllegalArgumentException("Tile outside zoom " + zoom + ": " + x + "," + y);
        long key = (long) zoom << 58 | (long) x << 29 | y;
        synchronized (memory) {
            int[] hit = memory.get(key);
            if (hit != null) return hit;
        }
        int[] pixels = files.singleFlight(inFlight, key, () -> read(zoom, x, y), "imagery tile " + zoom + "/" + x + "/" + y);
        synchronized (memory) {
            memory.put(key, pixels);
            var iterator = memory.entrySet().iterator();
            while (memory.size() > maxTilesInMemory && iterator.hasNext()) { iterator.next(); iterator.remove(); }
        }
        return pixels;
    }

    /**
     * The packed RGB of the pixel holding a global slippy-map pixel position at a zoom, or
     * {@link #NO_DATA}. X wraps; rows beyond the coverage square clamp to its edge.
     */
    public int pixel(long px, long py, int zoom) {
        long across = (long) TILE_SIZE << zoom;
        px = Math.floorMod(px, across);
        py = Math.clamp(py, 0, across - 1);
        int tx = (int) (px >> 8), ty = (int) (py >> 8);
        long key = (long) zoom << 58 | (long) tx << 29 | ty;
        long[] previous = lastKey.get();
        int[][] cached = lastTile.get();
        if (previous[0] != key) {
            cached[0] = tile(zoom, tx, ty);
            previous[0] = key;
        }
        int[] tile = cached[0];
        return tile.length == 0 ? NO_DATA : tile[(int) ((py & 255) << 8 | (px & 255))];
    }

    /**
     * The mean colour, in linear RGB 0..1 written to {@code out}, of the pixels of a square
     * {@code size} pixels wide centred on a continuous pixel position; false with {@code out}
     * untouched when any of them has no imagery.
     */
    public boolean mean(double px, double py, int zoom, int size, double[] out) {
        long x0 = (long) Math.floor(px - size / 2.0 + 0.5), y0 = (long) Math.floor(py - size / 2.0 + 0.5);
        double r = 0, g = 0, b = 0;
        for (int j = 0; j < size; j++) for (int i = 0; i < size; i++) {
            int rgb = pixel(x0 + i, y0 + j, zoom);
            if (rgb == NO_DATA) return false;
            r += LINEAR[rgb >> 16 & 255]; g += LINEAR[rgb >> 8 & 255]; b += LINEAR[rgb & 255];
        }
        double n = size * size;
        out[0] = r / n; out[1] = g / n; out[2] = b / n;
        return true;
    }

    /** sRGB byte to linear light. */
    static final double[] LINEAR = new double[256];
    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            LINEAR[i] = c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
        }
    }

    /** Loads every tile an area needs at a zoom, several at a time, so later reads never wait. */
    public void prefetch(MercatorGrid grid, double minX, double minZ, double maxX, double maxZ, int zoom) {
        int x0 = (int) Math.floor(grid.pixelX(minX, zoom) / TILE_SIZE), x1 = (int) Math.floor(grid.pixelX(maxX, zoom) / TILE_SIZE);
        int y0 = (int) Math.floor(grid.pixelY(minZ, zoom) / TILE_SIZE), y1 = (int) Math.floor(grid.pixelY(maxZ, zoom) / TILE_SIZE);
        int n = 1 << zoom;
        java.util.List<int[]> wanted = new java.util.ArrayList<>();
        for (int ty = Math.max(0, y0); ty <= Math.min(n - 1, y1); ty++) for (int tx = x0; tx <= x1; tx++) wanted.add(new int[]{Math.floorMod(tx, n), ty});
        wanted.parallelStream().forEach(t -> tile(zoom, t[0], t[1]));
    }

    private int[] read(int zoom, int x, int y) throws IOException, InterruptedException {
        String name = zoom + "/" + y + "/" + x + ".jpg";
        Path cached = cacheRoot == null ? null : cacheRoot.resolve(name);
        if (cached != null && Files.isRegularFile(cached, LinkOption.NOFOLLOW_LINKS)) {
            try {
                int[] pixels = decode(Files.readAllBytes(cached));
                files.diskHits.incrementAndGet();
                return pixels;
            } catch (IOException corrupt) {
                Files.deleteIfExists(cached);
            }
        }
        if (!allowNetwork) throw new MissingTerrainException("Imagery tile " + name + " not cached and network access is disabled");
        byte[] bytes = files.fetch(base.resolve(name), 0, MAX_TILE_BYTES);
        if (bytes == null) bytes = new byte[0];
        int[] pixels = decode(bytes);
        if (cached != null) RangeFiles.write(cached, bytes);
        return pixels;
    }

    /** Decodes a JPEG tile; anything that is not a JPEG (the server's blank placeholder, or nothing) is no imagery. */
    static int[] decode(byte[] bytes) throws IOException {
        if (bytes.length < 2 || (bytes[0] & 0xFF) != 0xFF || (bytes[1] & 0xFF) != 0xD8) return EMPTY;
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (RuntimeException malformed) {
            throw new IOException("Malformed imagery tile", malformed);
        }
        if (image == null) throw new IOException("Not a readable imagery tile");
        if (image.getWidth() != TILE_SIZE || image.getHeight() != TILE_SIZE) throw new IOException("Unexpected tile size " + image.getWidth() + "x" + image.getHeight());
        int[] argb = image.getRGB(0, 0, TILE_SIZE, TILE_SIZE, null, 0, TILE_SIZE);
        for (int i = 0; i < argb.length; i++) argb[i] &= 0xFFFFFF;
        return argb;
    }

    public Stats stats() {
        int resident;
        synchronized (memory) { resident = memory.size(); }
        return new Stats(files.downloads.get(), files.diskHits.get(), files.failures.get(), resident);
    }

    public record Stats(long downloads, long diskHits, long failures, int residentTiles) {}

    @Override public void close() { files.close(); }
}
