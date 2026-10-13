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
import java.util.Arrays;
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

    private float[] read(int zoom, int x, int y) throws IOException, InterruptedException {
        Path cached = cacheRoot == null ? null : cacheRoot.resolve(zoom + "/" + x + "/" + y + ".png");
        if (cached != null && Files.isRegularFile(cached, LinkOption.NOFOLLOW_LINKS)) {
            try {
                float[] values = decode(Files.readAllBytes(cached));
                diskHits.incrementAndGet();
                return mend(values, zoom, y);
            } catch (IOException corrupt) {
                // Cache bytes are derived; discard and refetch from the source rather than keep serving a broken tile.
                Files.deleteIfExists(cached);
            }
        }
        if (!allowNetwork) throw new MissingTerrainException("Elevation tile " + zoom + "/" + x + "/" + y + " not cached and network access is disabled");
        byte[] bytes = download(zoom, x, y);
        float[] values = mend(decode(bytes), zoom, y);
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

    /**
     * Mends lone bad pixels and returns the tile. The composite holds single pixels and small
     * clusters, mostly where its source patches meet, that read hundreds or thousands of metres off
     * the ground or water around them; interpolated, each would sink a pit or raise a spire tens of
     * blocks wide. A pixel is mended when it lies more than twice a tolerance from what its
     * neighbours within two pixels predict and fewer than three of them lie within the tolerance of
     * it: 20 m plus two pixel widths, steeper than nearly all real ground. The prediction is their
     * median, or where the tile's edge or open sea cuts the window short, the plane through them,
     * so a one-sided window on a slope predicts the slope. Cliffs, ridges and valleys always keep
     * neighbours along them at their own height, so only spikes match. Two passes clear clusters.
     * Open-sea zeros neither count as neighbours nor change.
     */
    static float[] mend(float[] metres, int zoom, int tileY) {
        float[] out = metres;
        for (int pass = 0; pass < 2; pass++) out = mendPass(out, zoom, tileY);
        return out;
    }

    private static float[] mendPass(float[] metres, int zoom, int tileY) {
        float[] around = new float[24];
        int[] at = new int[24];
        float[] mended = null;
        for (int y = 0; y < TILE_SIZE; y++) {
            // Ground width of a pixel on this row: the Mercator scale shrinks it toward the poles.
            double n = Math.PI * (1 - 2.0 * (tileY * TILE_SIZE + y + 0.5) / (TILE_SIZE << zoom));
            double tolerance = 20 + 2 * 40_075_016.7 / Math.cosh(n) / (TILE_SIZE << zoom);
            for (int x = 0; x < TILE_SIZE; x++) {
                float value = metres[y * TILE_SIZE + x];
                if (value == 0f) continue;
                int count = 0, close = 0;
                for (int j = Math.max(0, y - 2); j <= Math.min(TILE_SIZE - 1, y + 2); j++) {
                    for (int i = Math.max(0, x - 2); i <= Math.min(TILE_SIZE - 1, x + 2); i++) {
                        float other = metres[j * TILE_SIZE + i];
                        if (i == x && j == y || other == 0f) continue;
                        at[count] = (j - y) * 8 + i - x;
                        around[count++] = other;
                        if (Math.abs(other - value) <= tolerance) close++;
                    }
                }
                if (close >= 3 || count < 8) continue;
                double expected = count == 24 ? median(around) : plane(around, at, count, 2 * tolerance);
                if (Math.abs(value - expected) <= 2 * tolerance) continue;
                if (mended == null) mended = metres.clone();
                mended[y * TILE_SIZE + x] = (float) expected;
            }
        }
        return mended == null ? metres : mended;
    }

    private static double median(float[] values) {
        float[] sorted = values.clone();
        Arrays.sort(sorted);
        return (sorted[11] + sorted[12]) / 2.0;
    }

    /**
     * Height at the centre of the least-squares plane through neighbours at offsets {@code at}
     * (dy * 8 + dx), refitted without the one furthest from it while that lies further than
     * {@code outlier}, so a few bad pixels among them cannot tilt it.
     */
    private static double plane(float[] values, int[] at, int count, double outlier) {
        boolean[] dropped = new boolean[count];
        double[] fit = new double[3];
        for (int round = 0; ; round++) {
            fit(values, at, count, dropped, fit);
            if (round == 4) return fit[0];
            int worst = -1;
            double furthest = outlier;
            for (int k = 0; k < count; k++) {
                if (dropped[k]) continue;
                int dy = Math.floorDiv(at[k] + 2, 8), dx = at[k] - dy * 8;
                double off = Math.abs(fit[0] + fit[1] * dx + fit[2] * dy - values[k]);
                if (off > furthest) { furthest = off; worst = k; }
            }
            if (worst < 0) return fit[0];
            dropped[worst] = true;
        }
    }

    /** Least-squares v = a + b dx + c dy through the neighbours not dropped, into {@code fit}; level at their mean where too few remain. */
    private static void fit(float[] values, int[] at, int count, boolean[] dropped, double[] fit) {
        double n = 0, sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0, sv = 0, sxv = 0, syv = 0;
        for (int k = 0; k < count; k++) {
            if (dropped[k]) continue;
            int dy = Math.floorDiv(at[k] + 2, 8), dx = at[k] - dy * 8;
            double v = values[k];
            n++; sx += dx; sy += dy; sxx += dx * dx; syy += dy * dy; sxy += dx * dy; sv += v; sxv += dx * v; syv += dy * v;
        }
        // Normal equations, solved by Cramer's rule.
        double det = n * (sxx * syy - sxy * sxy) - sx * (sx * syy - sxy * sy) + sy * (sx * sxy - sxx * sy);
        if (n < 3 || Math.abs(det) < 1e-9) { fit[0] = n == 0 ? 0 : sv / n; fit[1] = fit[2] = 0; return; }
        fit[0] = (sv * (sxx * syy - sxy * sxy) - sx * (sxv * syy - sxy * syv) + sy * (sxv * sxy - sxx * syv)) / det;
        fit[1] = (n * (sxv * syy - sxy * syv) - sv * (sx * syy - sxy * sy) + sy * (sx * syv - sxv * sy)) / det;
        fit[2] = (n * (sxx * syv - sxv * sxy) - sx * (sx * syv - sxv * sy) + sv * (sx * sxy - sxx * sy)) / det;
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
