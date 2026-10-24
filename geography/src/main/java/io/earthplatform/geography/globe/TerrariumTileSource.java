package io.earthplatform.geography.globe;

import io.earthplatform.geography.MissingTerrainException;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private final LruMap<Long, float[]> memory = new LruMap<>(64);
    private final Map<Long, CompletableFuture<float[]>> inFlight = new ConcurrentHashMap<>();
    /** Tiles' raw values being read, so a tile and its neighbours' seams wanting the same one fetch it once. */
    private final Map<Long, CompletableFuture<float[]>> rawInFlight = new ConcurrentHashMap<>();
    /** Better sources laid over the composite, in order; see {@link ElevationLayer}. */
    private final java.util.List<ElevationLayer> layers;
    private final AtomicLong downloads = new AtomicLong(), diskHits = new AtomicLong(), memoryHits = new AtomicLong(), failures = new AtomicLong();

    /**
     * @param base            URI prefix; tiles are fetched from {@code base + z/x/y.png}
     * @param cacheRoot       optional disk cache directory, or null for memory only
     * @param allowNetwork    false reads only the disk cache
     * @param maxTilesInMemory decoded tiles kept (each 256 KiB)
     * @param maxConcurrentTransfers simultaneous HTTP requests
     */
    public TerrariumTileSource(URI base, Path cacheRoot, boolean allowNetwork, int maxTilesInMemory, int maxConcurrentTransfers, Duration timeout) throws IOException {
        this(base, cacheRoot, allowNetwork, maxTilesInMemory, maxConcurrentTransfers, timeout, java.util.List.of());
    }

    /** @param layers better sources laid over each tile after its cleaning, in order (later ones win) */
    public TerrariumTileSource(URI base, Path cacheRoot, boolean allowNetwork, int maxTilesInMemory, int maxConcurrentTransfers, Duration timeout,
                               java.util.List<ElevationLayer> layers) throws IOException {
        this.layers = java.util.List.copyOf(layers);
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
        TickShare.giveWay();
        CompletableFuture<float[]> mine = new CompletableFuture<>();
        CompletableFuture<float[]> shared = inFlight.putIfAbsent(key, mine);
        if (shared == null) {
            try {
                early(zoom, x, y);
                float[] values = read(zoom, x, y);
                for (ElevationLayer layer : layers) refine(layer, zoom, x, y, values);
                if (zoom >= TileDespike.SPIKE_ZOOM) TileDespike.despike(values);
                synchronized (memory) {
                    memory.putAndTrim(key, values, maxTilesInMemory);
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
            return DataWaits.get(shared, timeout.toMillis() * 4, TimeUnit.MILLISECONDS);
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
     * Replaces pixels that stray from the bilinear {@link #REFERENCE_ZOOM} surface by more than
     * {@link TileRepair#BROKEN_METRES} plus the reference's relief over the surrounding 4x4 reference pixels. The
     * finest source tiles hold runs of wildly wrong values in places (-8,161 m beside 6,040 m on Aconcagua, -2,202 m
     * in the Bora Bora lagoon) that coarser zooms do not, and they become pits thousands of blocks deep. Exact zeros
     * mark open sea and are left for the bathymetry fill. A missing reference tile leaves the tile as it is.
     */
    private void repair(float[] values, int zoom, int x, int y) {
        int shift = zoom - REFERENCE_ZOOM;
        float[] reference;
        try {
            reference = tile(REFERENCE_ZOOM, x >> shift, y >> shift);
        } catch (MissingTerrainException missing) {
            return;
        }
        TileRepair.repair(values, reference, shift, x, y);
    }

    /**
     * A tile's values with the composite's bad pixels made good, in one pass for each kind: lines along whole
     * degrees where its SRTM tiles meet ({@link TileSeams#seams}), lone pixels and small clusters
     * ({@link TileMending#mend}), and in tiles finer than zoom 12, runs far off the zoom 11 surface
     * ({@link #repair}). Each leaves cliffs, gorges and peaks as they are. The layers then lay better sources
     * over it, and buildings in flat land go last ({@link TileDespike#despike}).
     */
    private float[] read(int zoom, int x, int y) throws IOException, InterruptedException {
        float[] kept;
        synchronized (cleanMemory) { kept = cleanMemory.get(key(zoom, x, y)); }
        if (kept != null) return kept.clone();
        float[] values = TileMending.mend(TileSeams.seams(StrayZeros.strayZeros(raw(zoom, x, y), zoom, x, y, this::neighbour), zoom, x, y, this::neighbour), zoom, y);
        if (zoom > REFERENCE_ZOOM + 1) repair(values, zoom, x, y);
        return values;
    }

    /**
     * A tile cleaned as {@link #read} leaves it, before any {@link ElevationLayer}, for a layer that must
     * compare against the composite as it was. Kept a while, as the Copernicus checks of a degree square's
     * parts overlap and would otherwise clean the same tiles again; the array is shared and must not be modified.
     */
    private final LruMap<Long, float[]> cleanMemory = new LruMap<>(64);
    private static final int CLEAN_KEEP = 48;

    public float[] cleaned(int zoom, int x, int y) {
        checkTile(zoom, x, y);
        long key = key(zoom, x, y);
        synchronized (cleanMemory) {
            float[] kept = cleanMemory.get(key);
            if (kept != null) return kept;
        }
        try {
            float[] values = read(zoom, x, y);
            synchronized (cleanMemory) {
                cleanMemory.putAndTrim(key, values, CLEAN_KEEP);
            }
            return values;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new MissingTerrainException("Interrupted reading elevation tile " + zoom + "/" + x + "/" + y);
        } catch (IOException failure) {
            throw new MissingTerrainException("Elevation tile " + zoom + "/" + x + "/" + y + " failed: " + failure);
        }
    }

    /**
     * On a first visit a tile waits on a chain of downloads: its own pixels, then the neighbours its seams read, the
     * coarser tile its repair compares against, and the layers' sources. None of them depends on another's values,
     * so they all start at once here and the chain later joins the loads in flight or finds them cached.
     */
    private void early(int zoom, int x, int y) {
        if (zoom > REFERENCE_ZOOM + 1) {
            int shift = zoom - REFERENCE_ZOOM;
            boolean known;
            synchronized (memory) { known = memory.containsKey(key(REFERENCE_ZOOM, x >> shift, y >> shift)); }
            if (!known) Thread.ofVirtual().start(() -> {
                try { tile(REFERENCE_ZOOM, x >> shift, y >> shift); } catch (RuntimeException missing) { /* repair() does without */ }
            });
        }
        for (ElevationLayer layer : layers) Thread.ofVirtual().start(() -> {
            try { layer.prefetch(zoom, x, y); } catch (RuntimeException missing) { /* refine() reports it */ }
        });
        // Neighbours are worth reading ahead only when this tile must come over the network too: from the disk, seams
        // reads them in moments, and reading them twice would cost more than it saves.
        if (cacheRoot == null || !Files.isRegularFile(cacheRoot.resolve(zoom + "/" + x + "/" + y + ".png"), LinkOption.NOFOLLOW_LINKS))
            TileSeams.seamNeighbours(zoom, x, y, (nz, nx, ny) -> {
                if (ny < 0 || ny >= 1 << nz) return;
                int wrapped = Math.floorMod(nx, 1 << nz);
                if (cacheRoot != null && Files.isRegularFile(cacheRoot.resolve(nz + "/" + wrapped + "/" + ny + ".png"), LinkOption.NOFOLLOW_LINKS)) return;
                Thread.ofVirtual().start(() -> neighbour(nz, wrapped, ny));
            });
    }

    /** A tile's values as the composite holds them, from the disk cache or else the network; the caller's own copy. */
    private float[] raw(int zoom, int x, int y) throws IOException, InterruptedException {
        long key = key(zoom, x, y);
        CompletableFuture<float[]> mine = new CompletableFuture<>(), shared = rawInFlight.putIfAbsent(key, mine);
        if (shared == null) {
            try {
                float[] values = load(zoom, x, y);
                mine.complete(values);
                return values.clone();
            } catch (Throwable failure) {
                mine.completeExceptionally(failure);
                throw failure;
            } finally {
                rawInFlight.remove(key, mine);
            }
        }
        try {
            return DataWaits.get(shared).clone();
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof IOException io) throw io;
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new MissingTerrainException("Elevation tile " + zoom + "/" + x + "/" + y + " failed: " + failed.getCause());
        }
    }

    private float[] load(int zoom, int x, int y) throws IOException, InterruptedException {
        Path cached = cacheRoot == null ? null : cacheRoot.resolve(zoom + "/" + x + "/" + y + ".png");
        if (cached != null && Files.isRegularFile(cached, LinkOption.NOFOLLOW_LINKS)) {
            try {
                float[] values = TerrariumPng.decode(DiskCache.read(cached));
                diskHits.incrementAndGet();
                return values;
            } catch (IOException corrupt) {
                // Cache bytes are derived; discard and refetch from the source rather than keep serving a broken tile.
                Files.deleteIfExists(cached);
            }
        }
        if (!allowNetwork) throw new MissingTerrainException("Elevation tile " + zoom + "/" + x + "/" + y + " not cached and network access is disabled");
        byte[] bytes = download(zoom, x, y);
        float[] values = TerrariumPng.decode(bytes);
        if (cached != null) {
            DiskCache.write(cached, bytes, "tile");
        }
        return values;
    }

    /** A neighbouring tile's unmended values for {@link TileSeams#seams}, or null where there is none or it can't be had. */
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
        DataWaits.before();
        try { return transfer(zoom, x, y); }
        finally { DataWaits.after(); }
    }

    private byte[] transfer(int zoom, int x, int y) throws IOException, InterruptedException {
        URI uri = base.resolve(zoom + "/" + x + "/" + y + ".png");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).header("User-Agent", "earth-platform/terrarium-v1").GET().build();
        IOException last = null;
        // Three tries for ordinary failures; a host asking us to slow down is waited out a few more times.
        for (int attempt = 0, slowed = 0; attempt < 3; attempt++) {
            if (attempt > 0) Thread.sleep(250L << attempt);
            HostBackoff.await(uri);
            HostBackoff.reachable(uri, "Elevation tiles");
            transfers.acquire();
            try {
                // Asked again: a host can go down while this transfer waited its turn.
                HostBackoff.reachable(uri, "Elevation tiles");
                long start = System.nanoTime();
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status >= 500 && !HostBackoff.slowDown(status)) HostBackoff.failed(uri, "Elevation tiles", "HTTP " + status);
                else HostBackoff.answered(uri);
                if (status == 200) {
                    downloads.incrementAndGet();
                    Downloads.count("Elevation tiles", response.body().length, System.nanoTime() - start);
                    if (response.body().length > MAX_TILE_BYTES) throw new MissingTerrainException("Oversized elevation tile " + uri);
                    return response.body();
                }
                if (status == 403 || status == 404) throw new MissingTerrainException("Elevation tile not available (" + status + "): " + uri);
                last = new IOException("HTTP " + status + " for " + uri);
                if (HostBackoff.slowDown(status) && ++slowed < HostBackoff.ATTEMPTS) {
                    HostBackoff.pause(uri, response, slowed, "Elevation tiles");
                    attempt--;
                }
            } catch (InterruptedIOException interrupted) {
                throw interrupted;
            } catch (IOException failure) {
                last = failure;
                HostBackoff.failed(uri, "Elevation tiles", failure);
            } finally {
                transfers.release();
            }
        }
        throw new MissingTerrainException("Elevation tile download failed after retries: " + uri + " (" + last + ")");
    }

    /** Unmended tiles beside one being mended, for {@link TileSeams#seams}: null where there is none. */
    interface Neighbours { float[] tile(int zoom, int x, int y); }

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

    /**
     * Lays one layer over a tile. A layer that can't have its data fails the tile, as the composite's own
     * missing tiles do, so it is tried again later; one that breaks any other way is a bug in it, and the
     * tile goes on as the layers before it left it rather than leaving a hole in the world.
     */
    static void refine(ElevationLayer layer, int zoom, int x, int y, float[] values) {
        float[] before = values.clone();
        try {
            layer.refine(zoom, x, y, values);
        } catch (MissingTerrainException missing) {
            throw missing;
        } catch (RuntimeException broken) {
            System.arraycopy(before, 0, values, 0, values.length);
            warnOnce(layer.id(), "Elevation layer " + layer.id() + " failed on tile " + zoom + "/" + x + "/" + y + "; using the tile without it", broken);
        }
    }

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /** Logs a broken source once per game, not once per tile. */
    static void warnOnce(String source, String message, Throwable failure) {
        if (WARNED.add(source)) System.getLogger("earthplatform").log(System.Logger.Level.WARNING, message, failure);
    }

    /** The layers laid over the composite, in order. */
    public java.util.List<ElevationLayer> layers() { return layers; }

    public Stats stats() {
        int resident;
        synchronized (memory) { resident = memory.size(); }
        return new Stats(downloads.get(), diskHits.get(), memoryHits.get(), failures.get(), resident, inFlight.size());
    }

    public record Stats(long downloads, long diskHits, long memoryHits, long failures, int residentTiles, int inFlight) {}

    @Override public void close() { executor.shutdownNow(); }
}
