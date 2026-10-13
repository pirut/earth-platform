package io.earthplatform.geography.globe;

import io.earthplatform.geography.MissingTerrainException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;

/**
 * Global elevation from Terrarium-encoded Web Mercator PNG tiles, dataset id {@code terrarium-v1}.
 *
 * <p>The default source is the public AWS Open Data "Terrain Tiles" bucket (Mapzen/Tilezen composite of SRTM, GMTED,
 * ETOPO1, 3DEP, and other national surveys); no key or account is needed. Each pixel encodes
 * {@code (R*256 + G + B/256) - 32768} metres. Native resolution varies by region; resampling never creates survey detail.
 *
 * <p>Loads are single-flight per tile, bounded in concurrency and memory, and persisted atomically to an optional disk
 * cache so the same world regenerates from the same bytes offline. Callers block; this is meant for world-generation
 * worker threads, never a game tick thread. Missing or undecodable tiles throw {@link MissingTerrainException}.
 */
public final class TerrariumTileSource implements AutoCloseable {
    public static final String DATASET_ID = "terrarium-v1";
    public static final URI AWS_TERRAIN_TILES = URI.create("https://s3.amazonaws.com/elevation-tiles-prod/terrarium/");
    public static final int TILE_SIZE = 256;
    public static final int MAX_ZOOM = 15;
    private static final int MAX_TILE_BYTES = 4 << 20;
    /** Finer tiles are checked against this zoom; the source's finest tiles carry broken pixel runs in places. */
    static final int REFERENCE_ZOOM = 11;
    /**
     * Metres a fine pixel may stray from the reference, beyond the reference's own relief nearby, before it counts as
     * broken. Real cliffs and gorges stay inside that relief; broken runs do not.
     */
    static final float BROKEN_METRES = 150f;
    /** Fine pixels either side along the row and column whose detail a repaired pixel borrows. */
    private static final int REPAIR_REACH = 4;
    /** Required attribution for the Terrain Tiles sources (https://github.com/tilezen/joerd/blob/master/docs/attribution.md). */
    public static final String ATTRIBUTION = """
        ArcticDEM terrain data DEM(s) were created from DigitalGlobe, Inc., imagery and funded under National Science Foundation awards 1043681, 1559691, and 1542736;
        Australia terrain data © Commonwealth of Australia (Geoscience Australia) 2017;
        Austria terrain data © offene Daten Österreichs – Digitales Geländemodell (DGM) Österreich;
        Canada terrain data contains information licensed under the Open Government Licence – Canada;
        Europe terrain data produced using Copernicus data and information funded by the European Union - EU-DEM layers;
        Global ETOPO1 terrain data U.S. National Oceanic and Atmospheric Administration;
        Mexico terrain data source: INEGI, Continental relief, 2016;
        New Zealand terrain data Copyright 2011 Crown copyright (c) Land Information New Zealand and the New Zealand Government (All rights reserved);
        Norway terrain data © Kartverket;
        United Kingdom terrain data © Environment Agency copyright and/or database right 2015. All rights reserved;
        United States 3DEP (formerly NED) and global GMTED2010 and SRTM terrain data courtesy of the U.S. Geological Survey.
        """;

    private final URI base;
    private final Path cacheRoot;
    private final boolean allowNetwork;
    private final int maxTilesInMemory;
    private final Duration timeout;
    private final HttpClient http;
    private final Semaphore transfers;
    private final ExecutorService executor;
    private final LinkedHashMap<Long, float[]> memory = new LinkedHashMap<>(64, 0.75f, true);
    private final Map<Long, CompletableFuture<float[]>> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong downloads = new AtomicLong(), diskHits = new AtomicLong(), memoryHits = new AtomicLong(), failures = new AtomicLong();

    /**
     * @param base            URI prefix; tiles are fetched from {@code base + z/x/y.png}
     * @param cacheRoot       optional disk cache directory, or null for memory only
     * @param allowNetwork    false reads only the disk cache
     * @param maxTilesInMemory decoded tiles kept (each 256 KiB)
     * @param maxConcurrentTransfers simultaneous HTTP requests
     */
    public TerrariumTileSource(URI base, Path cacheRoot, boolean allowNetwork, int maxTilesInMemory, int maxConcurrentTransfers, Duration timeout) throws IOException {
        this.base = Objects.requireNonNull(base);
        if (!base.toString().endsWith("/")) throw new IllegalArgumentException("Tile base URI must end with /");
        if (maxTilesInMemory < 1 || maxConcurrentTransfers < 1 || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Invalid tile source budget");
        if (cacheRoot != null) Files.createDirectories(cacheRoot);
        this.cacheRoot = cacheRoot;
        this.allowNetwork = allowNetwork;
        this.maxTilesInMemory = maxTilesInMemory;
        this.timeout = timeout;
        this.transfers = new Semaphore(maxConcurrentTransfers);
        this.executor = Executors.newFixedThreadPool(maxConcurrentTransfers, runnable -> {
            Thread thread = new Thread(runnable, "earth-terrarium-io");
            thread.setDaemon(true);
            return thread;
        });
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).executor(executor).build();
    }

    /** Decoded metres, row-major north to south, 256×256. The returned array is shared and must not be modified. */
    public float[] tile(int zoom, int x, int y) {
        checkTile(zoom, x, y);
        long key = key(zoom, x, y);
        synchronized (memory) {
            float[] hit = memory.get(key);
            if (hit != null) { memoryHits.incrementAndGet(); return hit; }
        }
        CompletableFuture<float[]> mine = new CompletableFuture<>();
        CompletableFuture<float[]> shared = inFlight.putIfAbsent(key, mine);
        if (shared == null) {
            try {
                float[] values = read(zoom, x, y);
                if (zoom > REFERENCE_ZOOM + 1) repair(values, zoom, x, y);
                synchronized (memory) {
                    memory.put(key, values);
                    var iterator = memory.entrySet().iterator();
                    while (memory.size() > maxTilesInMemory && iterator.hasNext()) { iterator.next(); iterator.remove(); }
                }
                mine.complete(values);
            } catch (Throwable failure) {
                failures.incrementAndGet();
                mine.completeExceptionally(failure);
            } finally {
                inFlight.remove(key, mine);
            }
            shared = mine;
        }
        try {
            return shared.get(timeout.toMillis() * 4, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new MissingTerrainException("Interrupted waiting for elevation tile " + zoom + "/" + x + "/" + y);
        } catch (TimeoutException late) {
            throw new MissingTerrainException("Timed out waiting for elevation tile " + zoom + "/" + x + "/" + y);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof MissingTerrainException missing) throw missing;
            throw new MissingTerrainException("Elevation tile " + zoom + "/" + x + "/" + y + " failed: " + failed.getCause());
        }
    }

    /**
     * Replaces pixels that stray from the bilinear {@link #REFERENCE_ZOOM} surface by more than {@link #BROKEN_METRES}
     * plus the reference's relief over the surrounding 4x4 reference pixels. The finest source tiles hold runs of
     * wildly wrong values in places (-8,161 m beside 6,040 m on Aconcagua, -2,202 m in the Bora Bora lagoon) that
     * coarser zooms do not, and they become pits thousands of blocks deep. Exact zeros mark open sea and are left for
     * the bathymetry fill. A missing reference tile leaves the tile as it is.
     */
    private void repair(float[] values, int zoom, int x, int y) {
        int shift = zoom - REFERENCE_ZOOM;
        float[] reference;
        try {
            reference = tile(REFERENCE_ZOOM, x >> shift, y >> shift);
        } catch (MissingTerrainException missing) {
            return;
        }
        repair(values, reference, shift, x, y);
    }

    /** Repairs {@code values}, tile (x, y) at {@code shift} zooms finer than {@code reference}, the tile holding it. */
    static void repair(float[] values, float[] reference, int shift, int x, int y) {
        // This tile covers a (256 >> shift)-pixel square of the reference tile starting at (ox, oy).
        double scale = 1.0 / (1 << shift);
        int span = TILE_SIZE >> shift, ox = (x & ((1 << shift) - 1)) * span, oy = (y & ((1 << shift) - 1)) * span;
        float[] expected = new float[TILE_SIZE * TILE_SIZE];
        boolean[] broken = new boolean[TILE_SIZE * TILE_SIZE];
        boolean any = false;
        for (int py = 0; py < TILE_SIZE; py++) {
            double ry = Math.max(0, Math.min(TILE_SIZE - 1.001, oy + (py + 0.5) * scale - 0.5));
            int y0 = (int) ry;
            double fy = ry - y0;
            for (int px = 0; px < TILE_SIZE; px++) {
                double rx = Math.max(0, Math.min(TILE_SIZE - 1.001, ox + (px + 0.5) * scale - 0.5));
                int x0 = (int) rx, at = y0 * TILE_SIZE + x0;
                double fx = rx - x0;
                int i = py * TILE_SIZE + px;
                expected[i] = (float) ((reference[at] * (1 - fx) + reference[at + 1] * fx) * (1 - fy)
                    + (reference[at + TILE_SIZE] * (1 - fx) + reference[at + TILE_SIZE + 1] * fx) * fy);
                float low = Float.MAX_VALUE, high = -Float.MAX_VALUE;
                for (int j = Math.max(0, y0 - 1); j <= Math.min(TILE_SIZE - 1, y0 + 2); j++)
                    for (int k = Math.max(0, x0 - 1); k <= Math.min(TILE_SIZE - 1, x0 + 2); k++) {
                        float r = reference[j * TILE_SIZE + k];
                        low = Math.min(low, r); high = Math.max(high, r);
                    }
                broken[i] = values[i] != 0f && Math.abs(values[i] - expected[i]) > BROKEN_METRES + high - low;
                any |= broken[i];
            }
        }
        if (!any) return;
        // A broken pixel takes the reference plus the detail its sound neighbours along the row and column show
        // over it, so a thin broken run leaves no smooth groove; a wide one falls back to the reference.
        float[] fixed = new float[TILE_SIZE * TILE_SIZE];
        for (int i = 0; i < fixed.length; i++) {
            if (!broken[i]) continue;
            int px = i % TILE_SIZE, py = i / TILE_SIZE, n = 0;
            double detail = 0;
            for (int d = -REPAIR_REACH; d <= REPAIR_REACH; d++) {
                int qx = px + d, qy = py + d;
                if (d == 0) continue;
                if (qx >= 0 && qx < TILE_SIZE && sound(values, broken, py * TILE_SIZE + qx)) { detail += values[py * TILE_SIZE + qx] - expected[py * TILE_SIZE + qx]; n++; }
                if (qy >= 0 && qy < TILE_SIZE && sound(values, broken, qy * TILE_SIZE + px)) { detail += values[qy * TILE_SIZE + px] - expected[qy * TILE_SIZE + px]; n++; }
            }
            float value = (float) (expected[i] + (n == 0 ? 0 : detail / n));
            fixed[i] = value == 0f ? Math.ulp(0f) : value;
        }
        for (int i = 0; i < fixed.length; i++) if (broken[i]) values[i] = fixed[i];
    }

    private static boolean sound(float[] values, boolean[] broken, int i) { return !broken[i] && values[i] != 0f; }

    private float[] read(int zoom, int x, int y) throws IOException, InterruptedException {
        Path cached = cacheRoot == null ? null : cacheRoot.resolve(zoom + "/" + x + "/" + y + ".png");
        if (cached != null && Files.isRegularFile(cached, LinkOption.NOFOLLOW_LINKS)) {
            try {
                float[] values = decode(Files.readAllBytes(cached));
                diskHits.incrementAndGet();
                return values;
            } catch (IOException corrupt) {
                // Cache bytes are derived; discard and refetch from the source rather than keep serving a broken tile.
                Files.deleteIfExists(cached);
            }
        }
        if (!allowNetwork) throw new MissingTerrainException("Elevation tile " + zoom + "/" + x + "/" + y + " not cached and network access is disabled");
        byte[] bytes = download(zoom, x, y);
        float[] values = decode(bytes);
        if (cached != null) {
            Files.createDirectories(cached.getParent());
            Path temp = Files.createTempFile(cached.getParent(), "tile", ".part");
            try {
                Files.write(temp, bytes);
                Files.move(temp, cached, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
        return values;
    }

    private byte[] download(int zoom, int x, int y) throws IOException, InterruptedException {
        URI uri = base.resolve(zoom + "/" + x + "/" + y + ".png");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).header("User-Agent", "earth-platform/terrarium-v1").GET().build();
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) Thread.sleep(250L << attempt);
            transfers.acquire();
            try {
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status == 200) {
                    downloads.incrementAndGet();
                    if (response.body().length > MAX_TILE_BYTES) throw new MissingTerrainException("Oversized elevation tile " + uri);
                    return response.body();
                }
                if (status == 403 || status == 404) throw new MissingTerrainException("Elevation tile not available (" + status + "): " + uri);
                last = new IOException("HTTP " + status + " for " + uri);
            } catch (InterruptedIOException interrupted) {
                throw interrupted;
            } catch (IOException failure) {
                last = failure;
            } finally {
                transfers.release();
            }
        }
        throw new MissingTerrainException("Elevation tile download failed after retries: " + uri + " (" + last + ")");
    }

    static float[] decode(byte[] png) throws IOException {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(png));
        } catch (RuntimeException malformed) {
            throw new IOException("Malformed image", malformed);
        }
        if (image == null) throw new IOException("Not a readable image");
        if (image.getWidth() != TILE_SIZE || image.getHeight() != TILE_SIZE) throw new IOException("Unexpected tile size " + image.getWidth() + "x" + image.getHeight());
        int[] argb = image.getRGB(0, 0, TILE_SIZE, TILE_SIZE, null, 0, TILE_SIZE);
        float[] metres = new float[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            metres[i] = (float) ((((p >>> 16) & 0xFF) * 256 + ((p >>> 8) & 0xFF) + (p & 0xFF) / 256.0) - 32768.0);
        }
        return metres;
    }

    /** Encodes metres as a Terrarium PNG; used by fixtures and offline packs. */
    public static byte[] encode(float[] metres) throws IOException {
        if (metres.length != TILE_SIZE * TILE_SIZE) throw new IllegalArgumentException("Tile must be 256x256");
        BufferedImage image = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < metres.length; i++) {
            double v = metres[i] + 32768.0;
            if (!(v >= 0 && v < 65536)) throw new IllegalArgumentException("Elevation outside Terrarium range");
            int r = (int) (v / 256), g = (int) v % 256, b = (int) Math.floor((v - Math.floor(v)) * 256);
            image.setRGB(i % TILE_SIZE, i / TILE_SIZE, (r << 16) | (g << 8) | b);
        }
        var out = new java.io.ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static void checkTile(int zoom, int x, int y) {
        if (zoom < 0 || zoom > MAX_ZOOM) throw new IllegalArgumentException("Unsupported Terrarium zoom " + zoom);
        int n = 1 << zoom;
        if (x < 0 || x >= n || y < 0 || y >= n) throw new IllegalArgumentException("Tile outside zoom " + zoom + ": " + x + "," + y);
    }

    private static long key(int zoom, int x, int y) { return ((long) zoom << 58) | ((long) x << 29) | y; }

    public Stats stats() {
        int resident;
        synchronized (memory) { resident = memory.size(); }
        return new Stats(downloads.get(), diskHits.get(), memoryHits.get(), failures.get(), resident, inFlight.size());
    }

    public record Stats(long downloads, long diskHits, long memoryHits, long failures, int residentTiles, int inFlight) {}

    @Override public void close() { executor.shutdownNow(); }
}
