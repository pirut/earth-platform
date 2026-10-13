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
    private final CopernicusFill fill;
    private final AtomicLong downloads = new AtomicLong(), diskHits = new AtomicLong(), memoryHits = new AtomicLong(), failures = new AtomicLong();

    /**
     * @param base            URI prefix; tiles are fetched from {@code base + z/x/y.png}
     * @param cacheRoot       optional disk cache directory, or null for memory only
     * @param allowNetwork    false reads only the disk cache
     * @param maxTilesInMemory decoded tiles kept (each 256 KiB)
     * @param maxConcurrentTransfers simultaneous HTTP requests
     */
    public TerrariumTileSource(URI base, Path cacheRoot, boolean allowNetwork, int maxTilesInMemory, int maxConcurrentTransfers, Duration timeout) throws IOException {
        this(base, cacheRoot, allowNetwork, maxTilesInMemory, maxConcurrentTransfers, timeout, null);
    }

    /**
     * As above, with tiles from zoom {@value CopernicusFill#MIN_ZOOM} filled from {@code copernicus}
     * where they are wrong over a wide area ({@link CopernicusFill}), or as they are when it is null.
     */
    public TerrariumTileSource(URI base, Path cacheRoot, boolean allowNetwork, int maxTilesInMemory, int maxConcurrentTransfers, Duration timeout,
                               CopernicusSource copernicus) throws IOException {
        this.fill = copernicus == null ? null : new CopernicusFill(copernicus, this::cleaned, 512);
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

    /** Zooms from which {@link #despike} runs: coarser pixels are too wide for a building to stand out. */
    static final int SPIKE_ZOOM = 10;
    /** Metres above the ground around it that a pixel in flat land may stand before it counts as a building. */
    static final float SPIKE_METRES = 30f;
    /** Spread, in metres, of the lower half of the ground around a pixel below which that ground counts as flat. */
    static final float FLAT_METRES = 8f;
    private static final int SPIKE_REACH = 2;
    /** Most pixels in the window that may stand that high: more make a hill, a ridge or a shore, not a building. */
    private static final int SPIKE_PIXELS = 4;

    /**
     * Lowers buildings in flat land to the ground around them. The composite's surface returns
     * keep tall buildings, such as the towers along Lake Worth in West Palm Beach (86 m among 1 to
     * 5 m ground at zoom 12), which {@link #mend} keeps since they stand in groups and {@link #repair}
     * keeps since they are far smaller than broken runs. Where the lower half of the pixels within
     * two of one lies within {@link #FLAT_METRES}, a pixel more than {@link #SPIKE_METRES} above their
     * median takes the median, as long as at most {@link #SPIKE_PIXELS} of them stand that high. Hills,
     * eskers, lake shores and coastal bluffs fill more of that window or slope through it, so they
     * stay. Open-sea zeros neither count nor change.
     */
    static void despike(float[] values) {
        float[] fixed = null, window = new float[(2 * SPIKE_REACH + 1) * (2 * SPIKE_REACH + 1)];
        // The lowest pixel and the number of pixels in each window, from those along each row's stretch first.
        float[] rowLow = new float[TILE_SIZE * TILE_SIZE];
        byte[] rowCount = new byte[TILE_SIZE * TILE_SIZE];
        for (int py = 0; py < TILE_SIZE; py++) for (int px = 0; px < TILE_SIZE; px++) {
            float low = Float.MAX_VALUE;
            int n = 0;
            for (int qx = Math.max(0, px - SPIKE_REACH); qx <= Math.min(TILE_SIZE - 1, px + SPIKE_REACH); qx++) {
                float q = values[py * TILE_SIZE + qx];
                if (q != 0f) { low = Math.min(low, q); n++; }
            }
            rowLow[py * TILE_SIZE + px] = low;
            rowCount[py * TILE_SIZE + px] = (byte) n;
        }
        for (int py = 0; py < TILE_SIZE; py++) for (int px = 0; px < TILE_SIZE; px++) {
            int i = py * TILE_SIZE + px;
            float value = values[i];
            // Land only: under water a shoal standing above the bed is real.
            if (value <= 0f) continue;
            // Cheap test first: most pixels are not far above their lowest neighbours.
            float low = Float.MAX_VALUE;
            int n = 0;
            for (int qy = Math.max(0, py - SPIKE_REACH); qy <= Math.min(TILE_SIZE - 1, py + SPIKE_REACH); qy++) {
                low = Math.min(low, rowLow[qy * TILE_SIZE + px]);
                n += rowCount[qy * TILE_SIZE + px];
            }
            if (n < 9 || value - low <= SPIKE_METRES) continue;
            n = 0;
            for (int qy = Math.max(0, py - SPIKE_REACH); qy <= Math.min(TILE_SIZE - 1, py + SPIKE_REACH); qy++)
                for (int qx = Math.max(0, px - SPIKE_REACH); qx <= Math.min(TILE_SIZE - 1, px + SPIKE_REACH); qx++) {
                    float q = values[qy * TILE_SIZE + qx];
                    if (q != 0f) window[n++] = q;
                }
            // And on slopes: the median is within FLAT_METRES of the lowest only where more than half the window is.
            int flat = 0;
            for (int k = 0; k < n; k++) if (window[k] - low <= FLAT_METRES) flat++;
            if (flat <= n / 2) continue;
            java.util.Arrays.sort(window, 0, n);
            float median = window[n / 2];
            if (value - median <= SPIKE_METRES || median - window[0] > FLAT_METRES || window[n - 1 - SPIKE_PIXELS] > median + SPIKE_METRES) continue;
            if (fixed == null) fixed = values.clone();
            fixed[i] = median == 0f ? Math.ulp(0f) : median;
        }
        if (fixed != null) System.arraycopy(fixed, 0, values, 0, values.length);
    }

    /**
     * A tile's values with the composite's bad pixels made good, in one pass for each kind: lines along whole
     * degrees where its SRTM tiles meet ({@link #seams}), lone pixels and small clusters ({@link #mend}), and in
     * tiles finer than zoom 12, runs far off the zoom 11 surface ({@link #repair}). Each leaves cliffs, gorges
     * and peaks as they are. Then, where a second source is given, water filled in as land and heights off the
     * sea's datum, over wide areas, take that source ({@link CopernicusFill}); then buildings in flat land
     * ({@link #despike}).
     */
    private float[] read(int zoom, int x, int y) throws IOException, InterruptedException {
        float[] kept;
        synchronized (cleanMemory) { kept = cleanMemory.get(key(zoom, x, y)); }
        float[] values = kept != null ? kept.clone() : clean(zoom, x, y);
        if (fill != null) fill.apply(values, zoom, x, y);
        if (zoom >= SPIKE_ZOOM) despike(values);
        return values;
    }

    /** A tile's values made good as {@link #read} does, but not filled. */
    private float[] clean(int zoom, int x, int y) throws IOException, InterruptedException {
        float[] values = mend(seams(raw(zoom, x, y), zoom, x, y, this::neighbour), zoom, y);
        if (zoom > REFERENCE_ZOOM + 1) repair(values, zoom, x, y);
        return values;
    }

    /**
     * Cleaned tiles {@link CopernicusFill}'s checks read, kept a while: the checks of a degree square's parts
     * overlap, and each part would otherwise clean the same tiles again. Never modified once kept.
     */
    private final LinkedHashMap<Long, float[]> cleanMemory = new LinkedHashMap<>(64, 0.75f, true);
    private static final int CLEAN_KEEP = 48;

    /** {@link #clean} for {@link CopernicusFill}'s check, which must see the tiles as they were. */
    private float[] cleaned(int zoom, int x, int y) {
        checkTile(zoom, x, y);
        long key = key(zoom, x, y);
        synchronized (cleanMemory) {
            float[] kept = cleanMemory.get(key);
            if (kept != null) return kept;
        }
        try {
            float[] values = clean(zoom, x, y);
            synchronized (cleanMemory) {
                cleanMemory.put(key, values);
                var iterator = cleanMemory.entrySet().iterator();
                while (cleanMemory.size() > CLEAN_KEEP && iterator.hasNext()) { iterator.next(); iterator.remove(); }
            }
            return values;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new MissingTerrainException("Interrupted reading elevation tile " + zoom + "/" + x + "/" + y);
        } catch (IOException failure) {
            throw new MissingTerrainException("Elevation tile " + zoom + "/" + x + "/" + y + " failed: " + failure);
        }
    }

    /** A tile's values as the composite holds them, from the disk cache or else the network. */
    private float[] raw(int zoom, int x, int y) throws IOException, InterruptedException {
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

    /** A neighbouring tile's unmended values for {@link #seams}, or null where there is none or it can't be had. */
    private float[] neighbour(int zoom, int x, int y) {
        if (y < 0 || y >= 1 << zoom) return null;
        try {
            return raw(zoom, Math.floorMod(x, 1 << zoom), y);
        } catch (IOException | RuntimeException missing) {
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
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
        float[] plain = decodePlain(png);
        if (plain != null) return plain;
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(png));
        } catch (RuntimeException malformed) {
            throw new IOException("Malformed image", malformed);
        }
        if (image == null) throw new IOException("Not a readable image");
        if (image.getWidth() != TILE_SIZE || image.getHeight() != TILE_SIZE) throw new IOException("Unexpected tile size " + image.getWidth() + "x" + image.getHeight());
        float[] metres = new float[TILE_SIZE * TILE_SIZE];
        // Plain 8-bit sRGB tiles, nearly all of them, are read straight from their bytes; getRGB converts pixel by pixel.
        if ((image.getType() == BufferedImage.TYPE_3BYTE_BGR || image.getType() == BufferedImage.TYPE_4BYTE_ABGR)
                && image.getColorModel().getColorSpace().isCS_sRGB()
                && image.getRaster().getDataBuffer() instanceof java.awt.image.DataBufferByte buffer
                && buffer.getNumBanks() == 1 && image.getRaster().getMinX() == 0 && image.getRaster().getMinY() == 0
                && image.getRaster().getSampleModel() instanceof java.awt.image.PixelInterleavedSampleModel model
                && model.getPixelStride() == (image.getType() == BufferedImage.TYPE_3BYTE_BGR ? 3 : 4)
                && model.getScanlineStride() == TILE_SIZE * model.getPixelStride() && buffer.getOffset() == 0
                && image.getRaster().getSampleModelTranslateX() == 0 && image.getRaster().getSampleModelTranslateY() == 0) {
            byte[] bytes = buffer.getData();
            int stride = model.getPixelStride();
            int[] offsets = model.getBandOffsets();
            int red = offsets[0], green = offsets[1], blue = offsets[2];
            for (int i = 0; i < metres.length; i++) {
                int at = i * stride;
                metres[i] = (float) (((bytes[at + red] & 0xFF) * 256 + (bytes[at + green] & 0xFF) + (bytes[at + blue] & 0xFF) / 256.0) - 32768.0);
            }
            return metres;
        }
        int[] argb = image.getRGB(0, 0, TILE_SIZE, TILE_SIZE, null, 0, TILE_SIZE);
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            metres[i] = (float) ((((p >>> 16) & 0xFF) * 256 + ((p >>> 8) & 0xFF) + (p & 0xFF) / 256.0) - 32768.0);
        }
        return metres;
    }

    /**
     * A plain tile's metres read straight from its PNG: 256 x 256, 8-bit RGB or RGBA, not interlaced, with no
     * colour profile, as the composite's tiles are. Null for anything else, which ImageIO reads instead.
     */
    static float[] decodePlain(byte[] png) {
        if (png.length < 33 || (png[0] & 0xFF) != 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') return null;
        java.nio.ByteBuffer in = java.nio.ByteBuffer.wrap(png);
        in.position(8);
        int channels = 0;
        java.util.zip.Inflater inflater = new java.util.zip.Inflater();
        try {
            int stride = 0;
            byte[] data = null;
            int filled = 0;
            while (in.remaining() >= 12) {
                int length = in.getInt(), type = in.getInt();
                if (length < 0 || length > in.remaining() - 4) return null;
                int start = in.position();
                if (type == 0x49484452) { // IHDR
                    if (length != 13 || in.getInt() != TILE_SIZE || in.getInt() != TILE_SIZE) return null;
                    int depth = in.get(), colour = in.get(), compression = in.get(), filter = in.get(), interlace = in.get();
                    if (depth != 8 || (colour != 2 && colour != 6) || compression != 0 || filter != 0 || interlace != 0) return null;
                    channels = colour == 2 ? 3 : 4;
                    stride = TILE_SIZE * channels;
                    data = new byte[(stride + 1) * TILE_SIZE];
                } else if (type == 0x49444154) { // IDAT
                    if (data == null) return null;
                    inflater.setInput(png, start, length);
                    while (!inflater.needsInput() && !inflater.finished() && filled < data.length) {
                        int n = inflater.inflate(data, filled, data.length - filled);
                        if (n == 0 && (inflater.needsDictionary() || inflater.needsInput())) break;
                        filled += n;
                    }
                } else if (type == 0x49454E44) { // IEND
                    break;
                } else if (type == 0x69434350 || type == 0x504C5445) { // iCCP, PLTE
                    return null;
                }
                in.position(start + length + 4);
            }
            if (data == null || filled != data.length) return null;
            byte[] previous = new byte[stride], row = new byte[stride];
            float[] metres = new float[TILE_SIZE * TILE_SIZE];
            for (int y = 0; y < TILE_SIZE; y++) {
                int at = y * (stride + 1), filter = data[at];
                System.arraycopy(data, at + 1, row, 0, stride);
                if (!unfilter(filter, row, previous, channels)) return null;
                for (int x = 0, p = 0; x < TILE_SIZE; x++, p += channels)
                    metres[y * TILE_SIZE + x] = (float) (((row[p] & 0xFF) * 256 + (row[p + 1] & 0xFF) + (row[p + 2] & 0xFF) / 256.0) - 32768.0);
                byte[] swap = previous; previous = row; row = swap;
            }
            return metres;
        } catch (java.util.zip.DataFormatException | RuntimeException malformed) {
            return null;
        } finally {
            inflater.end();
        }
    }

    /** Undoes one PNG row filter in place; false for an unknown filter. */
    private static boolean unfilter(int filter, byte[] row, byte[] previous, int bpp) {
        switch (filter) {
            case 0 -> { }
            case 1 -> { for (int i = bpp; i < row.length; i++) row[i] += row[i - bpp]; }
            case 2 -> { for (int i = 0; i < row.length; i++) row[i] += previous[i]; }
            case 3 -> {
                for (int i = 0; i < row.length; i++)
                    row[i] += (byte) (((i >= bpp ? row[i - bpp] & 0xFF : 0) + (previous[i] & 0xFF)) >> 1);
            }
            case 4 -> {
                // With nothing to the left the predictor is the byte above.
                for (int i = 0; i < bpp; i++) row[i] += previous[i];
                for (int i = bpp; i < row.length; i++) {
                    int a = row[i - bpp] & 0xFF, b = previous[i] & 0xFF, c = previous[i - bpp] & 0xFF;
                    int pa = b - c, pb = a - c, pc = pa + pb;
                    pa = pa < 0 ? -pa : pa; pb = pb < 0 ? -pb : pb; pc = pc < 0 ? -pc : pc;
                    row[i] += (byte) (pa <= pb && pa <= pc ? a : pb <= pc ? b : c);
                }
            }
            default -> { return false; }
        }
        return true;
    }

    /**
     * Mends lone bad pixels and returns the tile. The composite holds single pixels and small
     * clusters, mostly where its source patches meet, that read hundreds or thousands of metres off
     * the ground or water around them; interpolated, each would sink a pit or raise a spire tens of
     * blocks wide. A pixel is mended when it lies more than twice a tolerance, plus the ground's
     * fall across a pixel there, from what its neighbours within two pixels predict, and fewer than
     * three of them lie within the tolerance of it: 20 m plus two pixel widths, steeper than nearly
     * all real ground. The prediction is the {@link #surface} through them, which follows a face
     * curving over its brink; the fall allows for the lip and ledges of a sheer face such as El
     * Capitan's, which no smooth surface follows. Cliffs, ridges and valleys always keep neighbours
     * along them at their own height, so only spikes match. Two passes clear clusters.
     * Open-sea zeros neither count as neighbours nor change.
     */
    static float[] mend(float[] metres, int zoom, int tileY) {
        float[] out = metres;
        for (int pass = 0; pass < 2; pass++) out = mendPass(out, zoom, tileY);
        return out;
    }

    /** Unmended tiles beside one being mended, for {@link #seams}: null where there is none. */
    interface Neighbours { float[] tile(int zoom, int x, int y); }

    /** Degrees either side of a whole-degree line within which its bad pixels lie: three quarters of an SRTM arc-second. */
    private static final double SEAM_REACH = 0.75 / 3600;
    /** Coarsest zoom {@link #seams} mends; below it a seam averages away into pixels a kilometre or more across. */
    static final int SEAM_ZOOM = 10;

    /**
     * Mends the lines of bad pixels along whole degrees of latitude and longitude, where the
     * composite's 1° SRTM source tiles meet. In places a row or column there holds void fill or
     * resampled values: a trench or ridge running dead straight for kilometres, 4 km deep along
     * 31°N in Tibet and 80 m along 5°W in the Highlands. Its pixels agree with each other, so
     * {@link #mend} passes them. Across each line, a run of up to {@value #SEAM_RUN} pixels within
     * {@link #SEAM_REACH} of it is replaced by the line through the two pixels either side of it
     * when every pixel of the run lies further from that line than 4 m and a pixel's width, than
     * twice the step between either pair, and than four times as far as the pairs stray from it,
     * so cliff edges and rough ground keep their shape; and only where pixels about as far from
     * the line are off too at most of the five places along it. Pixels beyond the tile's edge come from
     * its neighbours, unmended as this tile is, so both sides of a seam agree.
     */
    static float[] seams(float[] metres, int zoom, int tileX, int tileY, Neighbours neighbours) {
        if (zoom < SEAM_ZOOM) return metres;
        long size = (long) TILE_SIZE << zoom;
        double degrees = 360.0 / size;
        double pixel = 40_075_016.7 / Math.cosh(Math.PI * (1 - 2.0 * (tileY + 0.5) / (1 << zoom))) / size;
        float[] out = metres;
        int band = (int) Math.ceil(SEAM_REACH / degrees) + 1;
        long left = (long) tileX * TILE_SIZE;
        for (long lon = (long) Math.ceil((left - band - 3) * degrees - 180); lon <= (left + TILE_SIZE + band + 3) * degrees - 180; lon++) {
            int line = (int) ((long) Math.floor((lon + 180) / 360.0 * size) - left);
            out = across(metres, out, true, line, band, pixel, zoom, tileX, tileY, neighbours);
        }
        long top = (long) tileY * TILE_SIZE;
        double north = latitude(top - 4 * band - 3, size), south = latitude(top + TILE_SIZE + 4 * band + 3, size);
        for (long lat = (long) Math.ceil(south); lat <= north; lat++) {
            double phi = Math.toRadians(lat);
            int line = (int) ((long) Math.floor((1 - Math.log(Math.tan(phi) + 1 / Math.cos(phi)) / Math.PI) / 2 * size) - top);
            int rows = (int) Math.ceil(SEAM_REACH / (degrees * Math.cos(phi))) + 1;
            out = across(metres, out, false, line, rows, pixel, zoom, tileX, tileY, neighbours);
        }
        return out;
    }

    /** Latitude in degrees of the top edge of a global pixel row, clamped to the map. */
    private static double latitude(long row, long size) {
        double clamped = Math.max(0, Math.min(size, row));
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * clamped / size))));
    }

    /** Widest run of bad pixels across a seam that {@link #seams} mends. */
    private static final int SEAM_RUN = 5;

    /** One seam of {@link #seams}: column {@code line} (a meridian) or row {@code line} (a parallel), in this tile's pixels. */
    private static float[] across(float[] metres, float[] out, boolean meridian, int line, int band, double pixel,
                                  int zoom, int tileX, int tileY, Neighbours neighbours) {
        int reach = band + 2;
        if (line + band < 0 || line - band >= TILE_SIZE) return out;
        float[] before = null, after = null;
        if (line - reach < 0) before = meridian ? neighbours.tile(zoom, tileX - 1, tileY) : neighbours.tile(zoom, tileX, tileY - 1);
        if (line + reach >= TILE_SIZE) after = meridian ? neighbours.tile(zoom, tileX + 1, tileY) : neighbours.tile(zoom, tileX, tileY + 1);
        int width = 2 * band + 1;
        double[] profile = new double[2 * reach + 1], expected = new double[TILE_SIZE * width], misfit = new double[TILE_SIZE * width];
        boolean[] off = new boolean[TILE_SIZE * width];
        for (int s = 0; s < TILE_SIZE; s++) {
            for (int k = -reach; k <= reach; k++) {
                float v = at(metres, before, after, meridian, line + k, s);
                profile[k + reach] = v == 0f ? Double.NaN : v;
            }
            // Runs across the line, within the reach, that lie off the line through the ground two pixels either side
            // of them throughout; a pixel in several takes the line that fits its ground best.
            for (int run = 1; run <= SEAM_RUN; run++) {
                for (int first = 2; first + run + 2 <= profile.length; first++) {
                    int last = first + run - 1;
                    double l2 = profile[first - 2], l1 = profile[first - 1], r1 = profile[last + 1], r2 = profile[last + 2];
                    if (Double.isNaN(l2) || Double.isNaN(l1) || Double.isNaN(r1) || Double.isNaN(r2)) continue;
                    // Least-squares line through the four, at offsets -2, -1, run, run + 1 from the run's first pixel.
                    double mean = (run - 1) / 2.0, x0 = -2 - mean, x1 = -1 - mean, x2 = run - mean, x3 = run + 1 - mean;
                    double slope = (x0 * l2 + x1 * l1 + x2 * r1 + x3 * r2) / (x0 * x0 + x1 * x1 + x2 * x2 + x3 * x3);
                    double centre = (l2 + l1 + r1 + r2) / 4;
                    double m0 = l2 - centre - slope * x0, m1 = l1 - centre - slope * x1, m2 = r1 - centre - slope * x2, m3 = r2 - centre - slope * x3;
                    double rms = Math.sqrt((m0 * m0 + m1 * m1 + m2 * m2 + m3 * m3) / 2);
                    double limit = Math.max(Math.max(4 + pixel, 2 * Math.max(Math.abs(l1 - l2), Math.abs(r2 - r1))), 4 * rms);
                    boolean all = true;
                    for (int c = first; c <= last && all; c++) all = Math.abs(profile[c] - (centre + slope * (c - first - mean))) > limit;
                    if (!all) continue;
                    for (int c = first; c <= last; c++) {
                        int k = c - reach, at = s * width + k + band;
                        if (Math.abs(k) > band || line + k < 0 || line + k >= TILE_SIZE || off[at] && misfit[at] <= rms) continue;
                        off[at] = true;
                        misfit[at] = rms;
                        expected[at] = centre + slope * (c - first - mean);
                    }
                }
            }
        }
        // A seam runs along its line: a pixel is only mended where pixels at about its offset are off for most of
        // the five places around it, so a gully or knoll the line happens to cross keeps its shape. A pixel the fit
        // missed, as beside a knoll, between three or four off by about the same at its own offset is off by that too.
        for (int s = 0; s < TILE_SIZE; s++) {
            for (int k = 0; k < width; k++) {
                int c = line + k - band;
                if (c < 0 || c >= TILE_SIZE) continue;
                int index = meridian ? s * TILE_SIZE + c : c * TILE_SIZE + s;
                if (metres[index] == 0f) continue;
                int along = 0, same = 0;
                double low = Double.MAX_VALUE, high = -Double.MAX_VALUE, sum = 0;
                for (int t = Math.max(0, s - 2); t <= Math.min(TILE_SIZE - 1, s + 2); t++) {
                    boolean near = false;
                    for (int j = Math.max(0, k - 1); j <= Math.min(width - 1, k + 1); j++) near |= off[t * width + j];
                    if (near) along++;
                    if (t == s || !off[t * width + k]) continue;
                    double miss = (meridian ? metres[t * TILE_SIZE + c] : metres[c * TILE_SIZE + t]) - expected[t * width + k];
                    low = Math.min(low, miss); high = Math.max(high, miss); sum += miss; same++;
                }
                double value;
                if (off[s * width + k]) {
                    if (along < 3) continue;
                    value = expected[s * width + k];
                } else {
                    double miss = same == 0 ? 0 : sum / same;
                    if (same < 3 || Math.abs(miss) <= 4 + pixel || high - low > Math.abs(miss) / 2) continue;
                    value = metres[index] - miss;
                }
                if (out == metres) out = metres.clone();
                out[index] = (float) value;
            }
        }
        return out;
    }

    /** A pixel across a seam, {@code c} pixels into this tile's frame (negative or past its edge in a neighbour); NaN when missing. */
    private static float at(float[] metres, float[] before, float[] after, boolean meridian, int c, int s) {
        float[] tile = metres;
        if (c < 0) { tile = before; c += TILE_SIZE; } else if (c >= TILE_SIZE) { tile = after; c -= TILE_SIZE; }
        if (tile == null) return Float.NaN;
        return meridian ? tile[s * TILE_SIZE + c] : tile[c * TILE_SIZE + s];
    }

    private static float[] mendPass(float[] metres, int zoom, int tileY) {
        float[] around = new float[24];
        int[] at = new int[24];
        double[] scratch = new double[6 * 7], fit = new double[6];
        float[] mended = null;
        for (int y = 0; y < TILE_SIZE; y++) {
            // Ground width of a pixel on this row: the Mercator scale shrinks it toward the poles.
            double n = Math.PI * (1 - 2.0 * (tileY * TILE_SIZE + y + 0.5) / (TILE_SIZE << zoom));
            double tolerance = 20 + 2 * 40_075_016.7 / Math.cosh(n) / (TILE_SIZE << zoom);
            for (int x = 0; x < TILE_SIZE; x++) {
                float value = metres[y * TILE_SIZE + x];
                if (value == 0f) continue;
                int count = 0, close = 0;
                // Most pixels have three close neighbours, which settles them, so the scan stops there.
                scan:
                for (int j = Math.max(0, y - 2); j <= Math.min(TILE_SIZE - 1, y + 2); j++) {
                    for (int i = Math.max(0, x - 2); i <= Math.min(TILE_SIZE - 1, x + 2); i++) {
                        float other = metres[j * TILE_SIZE + i];
                        if (i == x && j == y || other == 0f) continue;
                        at[count] = (j - y) * 8 + i - x;
                        around[count++] = other;
                        if (Math.abs(other - value) <= tolerance && ++close >= 3) break scan;
                    }
                }
                if (close >= 3 || count < 8) continue;
                double expected = surface(around, at, count, 2 * tolerance, scratch, fit);
                if (Math.abs(value - expected) <= 2 * tolerance + Math.hypot(fit[1], fit[2])) continue;
                if (mended == null) mended = metres.clone();
                mended[y * TILE_SIZE + x] = (float) expected;
            }
        }
        return mended == null ? metres : mended;
    }

    /**
     * Height at the centre of the least-squares surface through neighbours at offsets {@code at}
     * (dy * 8 + dx), its terms left in {@code fit}: a quadratic where they surround the pixel, which follows a cliff's curving
     * brink and foot, or a plane where the tile's edge or open sea cuts the window short, so a
     * one-sided window on a slope predicts the slope. Refitted without the one furthest from it
     * while that lies further than {@code outlier}, so a few bad pixels among them cannot bend it.
     */
    private static double surface(float[] values, int[] at, int count, double outlier, double[] scratch, double[] fit) {
        int terms = count >= 15 ? 6 : 3;
        boolean[] dropped = new boolean[count];
        for (int round = 0; ; round++) {
            fit(values, at, count, dropped, terms, fit, scratch);
            if (round == 4) return fit[0];
            int worst = -1;
            double furthest = outlier;
            for (int k = 0; k < count; k++) {
                if (dropped[k]) continue;
                int dy = Math.floorDiv(at[k] + 2, 8), dx = at[k] - dy * 8;
                double off = Math.abs(fit[0] + fit[1] * dx + fit[2] * dy + fit[3] * dx * dx + fit[4] * dx * dy + fit[5] * dy * dy - values[k]);
                if (off > furthest) { furthest = off; worst = k; }
            }
            if (worst < 0) return fit[0];
            dropped[worst] = true;
        }
    }

    /**
     * Least-squares fit of the first {@code terms} of 1, dx, dy, dx², dx dy, dy² through the
     * neighbours not dropped, into {@code fit}, the rest zero; level at their mean where too few
     * remain to fix them.
     */
    private static void fit(float[] values, int[] at, int count, boolean[] dropped, int terms, double[] fit, double[] a) {
        int width = terms + 1, n = 0;
        double sum = 0;
        Arrays.fill(a, 0);
        Arrays.fill(fit, 0);
        for (int k = 0; k < count; k++) {
            if (dropped[k]) continue;
            int dy = Math.floorDiv(at[k] + 2, 8), dx = at[k] - dy * 8;
            double v = values[k];
            double[] basis = {1, dx, dy, dx * dx, dx * dy, dy * dy};
            for (int r = 0; r < terms; r++) {
                for (int c = 0; c < terms; c++) a[r * width + c] += basis[r] * basis[c];
                a[r * width + terms] += basis[r] * v;
            }
            n++;
            sum += v;
        }
        fit[0] = n == 0 ? 0 : sum / n;
        if (n < terms + 1) return;
        // Normal equations, by Gaussian elimination with partial pivoting.
        for (int col = 0; col < terms; col++) {
            int pivot = col;
            for (int r = col + 1; r < terms; r++) if (Math.abs(a[r * width + col]) > Math.abs(a[pivot * width + col])) pivot = r;
            if (Math.abs(a[pivot * width + col]) < 1e-9) { Arrays.fill(fit, 0); fit[0] = sum / n; return; }
            for (int c = 0; c < width; c++) { double t = a[col * width + c]; a[col * width + c] = a[pivot * width + c]; a[pivot * width + c] = t; }
            for (int r = col + 1; r < terms; r++) {
                double f = a[r * width + col] / a[col * width + col];
                for (int c = col; c < width; c++) a[r * width + c] -= f * a[col * width + c];
            }
        }
        for (int r = terms - 1; r >= 0; r--) {
            double v = a[r * width + terms];
            for (int c = r + 1; c < terms; c++) v -= a[r * width + c] * fit[c];
            fit[r] = v / a[r * width + r];
        }
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
