package io.earthplatform.geography.globe;

import com.sun.net.httpserver.HttpServer;
import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.MissingTerrainException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dependency-free harness for the whole-planet surface layer. Synthetic tiles are served from a local HTTP server; the
 * optional live section (EARTH_LIVE_TILES=1) samples real AWS Terrain Tiles at known places.
 */
public final class GlobeTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        projection();
        codec();
        mend();
        seams();
        repair();
        bareEarth();
        kernel();
        streaming();
        worldCover();
        climate();
        imagery();
        palette();
        if ("1".equals(System.getenv("EARTH_LIVE_TILES"))) { live(); liveCover(); liveClimate(); liveImagery(); }
        else System.out.println("GlobeTest: live AWS tile checks skipped (set EARTH_LIVE_TILES=1)");
        System.out.println("GlobeTest: " + checks + " checks passed");
    }

    private static void projection() {
        var grid = new MercatorGrid(1.0);
        near(40_075_016.686, grid.worldWidthBlocks(), 1e-3, "circumference in blocks at 1 m");
        for (GeoPoint g : List.of(new GeoPoint(0, 0), new GeoPoint(27.9881, 86.925), new GeoPoint(-84.9, -179.9), new GeoPoint(84.9, 179.9), new GeoPoint(-33.86, 151.21))) {
            GeoPoint back = grid.geographic(grid.blockX(g), grid.blockZ(g));
            near(g.latitude(), back.latitude(), 1e-9, "latitude roundtrip");
            near(0, GeoPoint.wrapLongitude(g.longitude() - back.longitude()), 1e-9, "longitude roundtrip");
        }
        check(grid.blockZ(new GeoPoint(50, 0)) < 0, "north is negative Z");
        check(grid.blockX(new GeoPoint(0, 10)) > 0, "east is positive X");
        near(grid.geographic(0, 0).longitude(), grid.geographic(grid.worldWidthBlocks(), 0).longitude(), 1e-9, "X wraps around the globe");
        near(2, grid.scaleFactor(grid.blockZ(new GeoPoint(60, 0))), 1e-9, "scale factor sec(60°) = 2");
        near(0.5, grid.groundMetresPerBlock(grid.blockZ(new GeoPoint(-60, 0))), 1e-9, "ground metres per block at 60°S");
        // Conformal: a small step east and the same step north cover the same ground distance.
        double z = grid.blockZ(new GeoPoint(47, 8));
        GeoPoint a = grid.geographic(0, z), east = grid.geographic(1, z), north = grid.geographic(0, z - 1);
        near(haversine(a, east), haversine(a, north), 1e-5, "equal east and north ground scale");
        // Pixel space is linear in blocks, so a tile pixel spans a constant number of blocks.
        near(1, grid.pixelX(1000 + grid.blocksPerPixel(14), 14) - grid.pixelX(1000, 14), 1e-6, "pixel linear in blocks");
        near(256.0 * (1 << 13), grid.pixelY(0, 14), 1e-9, "equator at mid pixel row");
        fail(() -> grid.blockZ(new GeoPoint(86, 0)), MissingTerrainException.class, "beyond Mercator coverage fails");
        fail(() -> grid.geographic(0, grid.worldWidthBlocks()), MissingTerrainException.class, "beyond Mercator Z fails");
        fail(() -> new MercatorGrid(0), IllegalArgumentException.class, "zero scale rejected");
    }

    private static void codec() throws Exception {
        float[] metres = new float[256 * 256];
        for (int i = 0; i < metres.length; i++) metres[i] = (float) ((i % 997) * 9.25 - 4000 + (i / 256) * 0.5);
        metres[0] = -10_994; metres[1] = 8849.5f;
        float[] back = TerrariumTileSource.decode(TerrariumTileSource.encode(metres));
        for (int i = 0; i < metres.length; i++) near(metres[i], back[i], 1 / 256.0, "Terrarium codec");
        fail(() -> TerrariumTileSource.decode(new byte[]{1, 2, 3}), java.io.IOException.class, "non-image rejected");
    }

    /** Lone bad pixels are mended; steep real ground and open-sea zeros are left alone. */
    private static void mend() {
        int zoom = 15, tileY = 1 << 14;
        float[] metres = new float[256 * 256];
        // A cliff falling 100 m a pixel along a slanted line (steeper than Half Dome's face), sea on the left.
        for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) metres[y * 256 + x] = x < 20 ? 0 : (float) (500 + 100 * x + 50 * y);
        float[] clean = metres.clone();
        metres[128 * 256 + 128] = -450;           // a pit, as the Rio Negro's
        metres[60 * 256 + 60] -= 640; metres[61 * 256 + 60] -= 260;  // a pair
        metres[0 * 256 + 200] += 1100;            // a spire on the tile's edge
        metres[10 * 256 + 21] = 9000;             // a spire beside the sea
        float[] mended = TerrariumTileSource.mend(metres, zoom, tileY);
        Set<Integer> bad = Set.of(128 * 256 + 128, 60 * 256 + 60, 61 * 256 + 60, 200, 10 * 256 + 21);
        for (int i : bad) near(clean[i], mended[i], 60, "bad pixel " + (i % 256) + "," + (i / 256) + " mended to within a pixel's slope");
        int changed = 0;
        for (int i = 0; i < clean.length; i++) if (!bad.contains(i) && mended[i] != clean[i]) changed++;
        equal(0, changed, "good pixels never change");
        check(TerrariumTileSource.mend(clean, zoom, tileY) == clean, "clean tile returned as it is");
        // A sheer face 550 m high over three pixels, with a lip at its brink and ledges, wandering along its length.
        float[] face = new float[256 * 256];
        for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) {
            double at = x - 100 - 2 * Math.sin(y / 3.0);
            face[y * 256 + x] = (float) (1550 + 550 / (1 + Math.exp(at * 1.5)) + 70 * Math.exp(-(at + 1) * (at + 1)) + 25 * Math.sin(y * 1.7) * Math.exp(-at * at));
        }
        check(TerrariumTileSource.mend(face, zoom, tileY) == face, "a sheer face keeps its lip and ledges");
    }

    /** A synthetic town west of zoom 12 pixel (X0 + 20, Y0): buildings on 3 m ground, a hill, a river and shadow pits; fields east. */
    private static final int BARE_X0 = 3638 * 256 + 128, BARE_Y0 = 1612 * 256 + 128;

    private static boolean bareTown(double gx) { return gx < BARE_X0 + 20; }
    private static boolean bareRiver(double gy) { return Math.abs(gy - (BARE_Y0 + 40)) < 4; }
    private static boolean barePit(double gx, double gy) { return Math.floorMod((long) Math.floor(gx), 23) == 0 && Math.floorMod((long) Math.floor(gy), 29) == 0 && !bareRiver(gy); }

    /** Bare ground of the synthetic town at a zoom 12 pixel position. */
    private static double bareGround(double gx, double gy) {
        double d2 = (gx - BARE_X0 + 20) * (gx - BARE_X0 + 20) + (gy - BARE_Y0 + 70) * (gy - BARE_Y0 + 70);
        return 3 + 40 * Math.exp(-d2 / (2 * 30.0 * 30.0));
    }

    private static float bareSurface(double gx, double gy) {
        if (bareRiver(gy)) return 0.5f;
        double ground = bareGround(gx, gy);
        if (!bareTown(gx)) return (float) ground;
        if (barePit(gx, gy)) return (float) (ground - 11);
        long block = Math.floorDiv((long) Math.floor(gx), 3) * 92821 ^ Math.floorDiv((long) Math.floor(gy), 3) * 68917;
        return (float) (ground + (Math.floorMod(block * 0x9E3779B97F4A7C15L >>> 40, 10) < 7 ? 25 : 2));
    }

    private static void bareEarth() {
        long size = 256L << 12;
        GlobalElevation.Tiles source = (zoom, x, y) -> {
            float[] tile = new float[256 * 256];
            double scale = Math.scalb(1.0, 12 - zoom);
            for (int py = 0; py < 256; py++) for (int px = 0; px < 256; px++)
                tile[py * 256 + px] = bareSurface(((double) x * 256 + px + 0.5) * scale - 0.5, ((double) y * 256 + py + 0.5) * scale - 0.5);
            return tile;
        };
        BareEarth.Cover cover = (latitude, longitude) -> {
            double gx = (longitude + 180) / 360 * size, r = Math.toRadians(latitude);
            double gy = (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * size;
            gx -= 0.5; gy -= 0.5;
            if (bareRiver(gy) || bareTown(gx) && barePit(gx, gy)) return WorldCoverSource.WATER;
            return bareTown(gx) ? WorldCoverSource.BUILT_UP : WorldCoverSource.GRASSLAND;
        };
        BareEarth bare = new BareEarth(source, cover, 8, 8);
        float[] raw = source.tile(12, 3638, 1612), tile = bare.tile(12, 3638, 1612);
        // Tile pixel (i, j) is zoom 12 pixel (X0 - 128 + i, Y0 - 128 + j).
        java.util.function.IntBinaryOperator at = (dx, dy) -> (128 + dy) * 256 + 128 + dx;
        double built = 0, rooftops = 0;
        int n = 0;
        for (int dy = 60; dy <= 100; dy++) for (int dx = -110; dx <= -60; dx++) { built += tile[at.applyAsInt(dx, dy)]; rooftops += raw[at.applyAsInt(dx, dy)]; n++; }
        near(3, built / n, 3, "ground under buildings comes down to the land (" + rooftops / n + " m on the roofs)");
        double spread = 0;
        for (int dy = 60; dy <= 100; dy++) for (int dx = -110; dx <= -60; dx++) spread = Math.max(spread, Math.abs(tile[at.applyAsInt(dx, dy)] - built / n));
        check(spread < 7, "no 25 m building lumps or street grooves left: " + spread);
        near(43, tile[at.applyAsInt(-20, -70)], 7, "a hill under the town keeps its height");
        near(0.5, tile[at.applyAsInt(-60, 40)], 0.75, "a river keeps its level");
        int pitX = (int) (Math.ceil((BARE_X0 - 100) / 23.0) * 23) - BARE_X0, pitY = (int) (Math.ceil((BARE_Y0 - 100) / 29.0) * 29) - BARE_Y0;
        check(barePit(BARE_X0 + pitX, BARE_Y0 + pitY), "the test finds a shadow pit");
        near(bareGround(BARE_X0 + pitX, BARE_Y0 + pitY), tile[at.applyAsInt(pitX, pitY)], 3.5, "a shadow pit lies at the ground's level");
        near(raw[at.applyAsInt(127, 0)], tile[at.applyAsInt(127, 0)], 0, "fields well past the town are untouched");
        // Every zoom reads the same ground: zoom 15 over the town agrees with zoom 12.
        float[] fine = bare.tile(15, 3638 * 8 + 3, 1612 * 8 + 3);
        double fineMean = 0;
        for (int i = 0; i < fine.length; i++) fineMean += fine[i];
        fineMean /= fine.length;
        double coarseMean = 0;
        for (int dy = -32; dy < 0; dy++) for (int dx = -32; dx < 0; dx++) coarseMean += tile[at.applyAsInt(dx, dy)];
        coarseMean /= 1024;
        near(coarseMean, fineMean, 1, "zoom 15 ground matches zoom 12 ground");
        // A tile with no town near it is the source's own tile.
        float[] plain = new float[256 * 256];
        BareEarth plainCountry = new BareEarth((zoom, x, y) -> plain, (latitude, longitude) -> WorldCoverSource.TREE_COVER, 8, 8);
        check(plainCountry.tile(14, 100, 100) == plain, "tiles away from towns come back unchanged");
        check(Arrays.equals(source.tile(10, 909, 403), bare.tile(10, 909, 403)), "coarse zooms pass through");
    }

    private static void kernel() {
        double sum = 0;
        for (int i = -1; i <= 2; i++) sum += GlobalElevation.weight(i - 0.37);
        near(1, sum, 1e-12, "Catmull-Rom weights partition unity");
        near(1, GlobalElevation.weight(0), 0, "interpolating at centre");
        near(0, GlobalElevation.weight(1), 0, "zero at neighbour centre");
    }

    /** Synthetic world at zoom 2: elevation is an exact linear function of global pixel coordinates. */
    private static float planeAt(int zoom, long gx, long gy) { return (float) ((gx * 0.75 - gy * 0.5) / (1 << (zoom - 2)) + 10); }

    private static void streaming() throws Exception {
        int zoom = 2;
        Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
        Set<String> missing = ConcurrentHashMap.newKeySet();
        AtomicInteger flaky = new AtomicInteger(2);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/tiles/", exchange -> {
            String path = exchange.getRequestURI().getPath().substring("/tiles/".length());
            hits.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
            String[] parts = path.replace(".png", "").split("/");
            int z = Integer.parseInt(parts[0]), tx = Integer.parseInt(parts[1]), ty = Integer.parseInt(parts[2]);
            byte[] body;
            int status = 200;
            if (missing.contains(path)) { status = 404; body = new byte[0]; }
            else if (path.equals("2/3/3.png") && flaky.getAndDecrement() > 0) { status = 503; body = new byte[0]; }
            else {
                float[] metres = new float[256 * 256];
                for (int j = 0; j < 256; j++) for (int i = 0; i < 256; i++) metres[j * 256 + i] = z == 11 ? 0 : planeAt(z, tx * 256L + i, ty * 256L + j);
                try { Thread.sleep(30); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                body = TerrariumTileSource.encode(metres);
            }
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        Path cache = Files.createTempDirectory("terrarium-test");
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/tiles/");
        try {
            var grid = new MercatorGrid(1_000_000.0 / 256);
            try (var source = new TerrariumTileSource(base, cache, true, 6, 2, Duration.ofSeconds(5))) {
                // Single flight: many concurrent callers of one tile cause one transfer.
                var pool = Executors.newFixedThreadPool(8);
                List<Future<float[]>> futures = new ArrayList<>();
                for (int i = 0; i < 16; i++) futures.add(pool.submit(() -> source.tile(zoom, 1, 2)));
                float[] first = futures.get(0).get();
                for (var f : futures) check(f.get() == first, "shared decoded tile");
                pool.shutdown();
                equal(1, hits.get("2/1/2.png").get(), "single transfer for concurrent requests");
                near(planeAt(zoom, 256 + 5, 512 + 7), first[7 * 256 + 5], 1 / 256.0, "decoded pixel");

                // Bicubic reproduces a linear field exactly, including across tile seams and the antimeridian.
                var field = new GlobalElevation(grid, source, zoom);
                double width = grid.worldWidthBlocks();
                for (double fx : new double[]{0.24, 0.2501, 0.26, 0.4999, 0.5, 0.51}) {
                    double x = (fx - 0.5) * width, z = (0.6 - 0.5) * width;
                    double px = grid.pixelX(x, zoom) - 0.5, py = grid.pixelY(z, zoom) - 0.5;
                    near(px * 0.75 - py * 0.5 + 10, field.metres(x, z), 1e-2, "seamless linear reproduction at " + fx);
                }
                check(Math.abs(field.metres(-width / 2 + 0.01, 0) - field.metres(width / 2 + 0.01, 0)) < 1e-9, "antimeridian wraps");

                // Above zoom 10, open sea reads exactly 0 m; the coarser bathymetry is substituted.
                var detailed = new GlobalElevation(grid, source, 11);
                double bx = -0.4 * width, bz = 0.4 * width;
                double lowPx = grid.pixelX(bx, 10) - 0.5, lowPy = grid.pixelY(bz, 10) - 0.5;
                near(Math.min(0, (lowPx * 0.75 - lowPy * 0.5) / 256 + 10), detailed.metres(bx, bz), 0.05, "zero-sea pixels take bathymetry");
                check(detailed.metres(bx, bz) < -100, "bathymetry fixture is below sea level");

                // sample() agrees with metres(), its slope with finite differences, and it flags open sea.
                double[] out = new double[3];
                for (double fx : new double[]{0.24, 0.2501, 0.4999, 0.51}) {
                    double x = (fx - 0.5) * width, z = (0.6 - 0.5) * width;
                    near(field.metres(x, z), field.sample(x, z, out), 1e-9, "sample matches metres at " + fx);
                    near((field.metres(x + 0.5, z) - field.metres(x - 0.5, z)), out[0], 1e-3, "X slope at " + fx);
                    near((field.metres(x, z + 0.5) - field.metres(x, z - 0.5)), out[1], 1e-3, "Z slope at " + fx);
                    near(0, out[2], 1e-12, "land has no sea weight at " + fx);
                }
                near(detailed.metres(bx, bz), detailed.sample(bx, bz, out), 1e-9, "sample matches metres at sea");
                near(1, out[2], 1e-9, "open sea has full sea weight");

                // Transient server errors are retried.
                source.tile(zoom, 3, 3);
                equal(3, hits.get("2/3/3.png").get(), "two retries then success");

                missing.add("2/0/0.png");
                fail(() -> source.tile(zoom, 0, 0), MissingTerrainException.class, "404 is explicit missing terrain");
                fail(() -> source.tile(zoom, 4, 0), IllegalArgumentException.class, "tile outside zoom rejected");
                check(source.stats().residentTiles() <= 6, "memory LRU bounded");
            }
            int transfers = hits.get("2/1/2.png").get();
            // A fresh offline source must serve the same bytes from disk without the network.
            try (var offline = new TerrariumTileSource(base, cache, false, 4, 1, Duration.ofSeconds(5))) {
                near(planeAt(zoom, 256 + 5, 512 + 7), offline.tile(zoom, 1, 2)[7 * 256 + 5], 1 / 256.0, "disk cache offline");
                equal(transfers, hits.get("2/1/2.png").get(), "no transfer when cached");
                equal(1L, offline.stats().diskHits(), "disk hit recorded");
                fail(() -> offline.tile(zoom, 1, 0), MissingTerrainException.class, "offline uncached tile fails");
                // Corrupt cache bytes are discarded instead of served.
                Files.write(cache.resolve("2/3/3.png"), new byte[]{9, 9, 9});
                fail(() -> offline.tile(zoom, 3, 3), MissingTerrainException.class, "corrupt cache offline fails");
                check(!Files.exists(cache.resolve("2/3/3.png")), "corrupt cache entry removed");
            }
            try (var online = new TerrariumTileSource(base, cache, true, 4, 1, Duration.ofSeconds(5))) {
                online.tile(zoom, 3, 3);
                check(Files.exists(cache.resolve("2/3/3.png")), "corrupt cache entry refetched");
            }
            try (var stream = Files.list(cache.resolve("2/1"))) {
                check(stream.noneMatch(p -> p.toString().endsWith(".part")), "no partial cache files left");
            }
        } finally {
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    /** Lines of bad pixels along whole degrees are mended, across tile edges too; real ground along them and lines off whole degrees keep their shape. */
    private static void seams() {
        int zoom = 15;
        long size = 256L << zoom;
        // Rolling hills; 5°W crosses the tile holding 5°W, 57.05°N at a column found the same way the source does.
        int tileX = (int) (Math.floor(175 / 360.0 * size) / 256), tileY = 10030;
        int line = (int) ((long) Math.floor(175 / 360.0 * size) - tileX * 256L);
        float[] metres = new float[256 * 256];
        for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) metres[y * 256 + x] = (float) (200 + 30 * Math.sin(x / 17.0) + 20 * Math.cos(y / 23.0) + 0.5 * x);
        float[] clean = metres.clone();
        TerrariumTileSource.Neighbours none = (z, x, y) -> null;
        check(TerrariumTileSource.seams(clean, zoom, tileX, tileY, none) == clean, "clean tile returned as it is");
        float[] dip = {12, 61, 85, 37};                                   // as along 5°W in the Highlands
        for (int y = 0; y < 256; y++) for (int k = 0; k < 4; k++) metres[y * 256 + line - 1 + k] -= dip[k];
        for (int y = 0; y < 256; y++) for (int k = 0; k < 4; k++) metres[y * 256 + 100 + k] -= dip[k];   // a gully off the line
        metres[50 * 256 + line + 4] += 30; metres[51 * 256 + line + 4] += 30;                          // a knoll on it
        float[] mended = TerrariumTileSource.seams(metres, zoom, tileX, tileY, none);
        for (int y = 0; y < 256; y++) for (int k = 0; k < 4; k++) near(clean[y * 256 + line - 1 + k], mended[y * 256 + line - 1 + k], 3, "seam pixel mended");
        int changed = 0;
        for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) if ((x < line - 1 || x > line + 2) && mended[y * 256 + x] != metres[y * 256 + x]) changed++;
        equal(0, changed, "gully off the line, knoll on it and the ground about untouched");
        // A trench 4 km deep a pixel wide along Greenwich, which runs along a tile edge: each tile mends its own side.
        int left = (int) (size / 2 / 256) - 1, right = left + 1, ty = 12000;
        float[][] tiles = new float[2][256 * 256], cleanTiles = new float[2][];
        for (int t = 0; t < 2; t++) {
            for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) tiles[t][y * 256 + x] = (float) (5000 + 3 * (t * 256 + x) + 40 * Math.sin(y / 9.0));
            cleanTiles[t] = tiles[t].clone();
        }
        for (int y = 0; y < 256; y++) { tiles[0][y * 256 + 255] = 1871; tiles[1][y * 256] = 1872; }
        TerrariumTileSource.Neighbours both = (z, x, y) -> x == left ? tiles[0] : x == right ? tiles[1] : null;
        float[] westSide = TerrariumTileSource.seams(tiles[0], zoom, left, ty, both), eastSide = TerrariumTileSource.seams(tiles[1], zoom, right, ty, both);
        near(cleanTiles[0][77 * 256 + 255], westSide[77 * 256 + 255], 3, "trench mended west of a tile edge");
        near(cleanTiles[1][77 * 256], eastSide[77 * 256], 3, "trench mended east of a tile edge");
    }

    private static void repair() {
        // A 4x4 block of zoom-15 tiles under one zoom-13 reference tile, on a slope rising 2 m per fine pixel east.
        float[] reference = new float[256 * 256];
        for (int i = 0; i < reference.length; i++) reference[i] = 3000 + (i % 256) * 8f;
        int tx = 4 * 9 + 2, ty = 4 * 5 + 1;
        float[] tile = new float[256 * 256];
        for (int j = 0; j < 256; j++) for (int i = 0; i < 256; i++) tile[j * 256 + i] = 3000 + ((2 * 256 + i + 0.5f) / 4 - 0.5f) * 8f + (i % 7 == 0 && j < 100 ? 60 : 0) + (j > 128 ? 40 : 0);
        float[] expected = tile.clone();
        for (int j = 0; j < 256; j++) { tile[j * 256 + 114] = -8161; tile[j * 256 + 115] = 1519; }
        tile[40 * 256 + 40] = 9000;
        tile[41 * 256 + 41] -= 400;
        tile[60 * 256 + 60] = 0;
        TerrariumTileSource.repair(tile, reference, 2, tx, ty);
        near(expected[5 * 256 + 114], tile[5 * 256 + 114], 9, "broken pit column takes the reference and its neighbours' detail");
        near(expected[200 * 256 + 115], tile[200 * 256 + 115], 9, "broken low column keeps the neighbours' 40 m rise");
        near(expected[40 * 256 + 40], tile[40 * 256 + 40], 9, "broken spike takes the reference");
        near(expected[41 * 256 + 41], tile[41 * 256 + 41], 9, "a 400 m pit on a gentle slope takes the reference");
        near(0, tile[60 * 256 + 60], 0, "open-sea zeros are left for the bathymetry fill");
        near(expected[9 * 256 + 7], tile[9 * 256 + 7], 1e-3, "relief the reference smooths away is kept");
        near(expected[9 * 256 + 8], tile[9 * 256 + 8], 1e-3, "ordinary pixels are untouched");
    }

    /**
     * A synthetic WorldCover file laid out as the real ones are: 36000-pixel image with six
     * overviews, 1024-pixel deflated blocks, served with range requests. Only full-resolution
     * block (row 2, column 3) and the coarsest overview's block hold data; the rest are empty.
     */
    static byte[] syntheticCover() throws Exception {
        int levels = 7, blocks = 0;
        int[] widths = new int[levels];
        for (int k = 0; k < levels; k++) widths[k] = 36000 >> k;
        byte[] detailed = new byte[1024 * 1024], coarse = new byte[1024 * 1024];
        for (int y = 0; y < 1024; y++) for (int x = 0; x < 1024; x++) {
            detailed[y * 1024 + x] = (byte) (x < 512 ? WorldCoverSource.TREE_COVER : WorldCoverSource.WATER);
            coarse[y * 1024 + x] = (byte) WorldCoverSource.GRASSLAND;
        }
        byte[] detailedZ = deflate(detailed), coarseZ = deflate(coarse);
        var out = new java.io.ByteArrayOutputStream();
        int[] counts = new int[levels];
        for (int k = 0; k < levels; k++) { int across = Math.ceilDiv(widths[k], 1024); counts[k] = across * across; blocks += counts[k]; }
        // Header: magic, then each IFD (11 entries) followed by its two arrays; data after all of them.
        int ifdSize = 2 + 11 * 12 + 4;
        int[] ifdAt = new int[levels];
        int at = 8;
        for (int k = 0; k < levels; k++) { ifdAt[k] = at; at += ifdSize + counts[k] * 8; }
        int detailedAt = at, coarseAt = at + detailedZ.length;
        var header = java.nio.ByteBuffer.allocate(at).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        header.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(ifdAt[0]);
        for (int k = 0; k < levels; k++) {
            int arrays = ifdAt[k] + ifdSize, n = counts[k];
            header.position(ifdAt[k]);
            header.putShort((short) 11);
            int[][] entries = {{256, 4, 1, widths[k]}, {257, 4, 1, widths[k]}, {258, 3, 1, 8}, {259, 3, 1, 8}, {262, 3, 1, 3},
                    {277, 3, 1, 1}, {284, 3, 1, 1}, {322, 3, 1, 1024}, {323, 3, 1, 1024}, {324, 4, n, n == 1 ? -1 : arrays}, {325, 4, n, n == 1 ? -2 : arrays + n * 4}};
            for (int[] e : entries) {
                header.putShort((short) e[0]).putShort((short) e[1]).putInt(e[2]);
                if (e[1] == 3) header.putShort((short) e[3]).putShort((short) 0);
                else if (e[3] == -1) header.putInt(k == 6 ? coarseAt : 0);
                else if (e[3] == -2) header.putInt(k == 6 ? coarseZ.length : 0);
                else header.putInt(e[3]);
            }
            header.putInt(k + 1 < levels ? ifdAt[k + 1] : 0);
            if (n > 1) {
                for (int i = 0; i < n; i++) header.putInt(k == 0 && i == 2 * 36 + 3 ? detailedAt : 0);
                for (int i = 0; i < n; i++) header.putInt(k == 0 && i == 2 * 36 + 3 ? detailedZ.length : 0);
            }
        }
        out.write(header.array());
        out.write(detailedZ);
        out.write(coarseZ);
        check(blocks > 1296, "synthetic cover has every level");
        return out.toByteArray();
    }

    private static byte[] deflate(byte[] data) {
        var deflater = new java.util.zip.Deflater();
        deflater.setInput(data);
        deflater.finish();
        var out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[65536];
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer));
        deflater.end();
        return out.toByteArray();
    }

    private static void worldCover() throws Exception {
        byte[] file = syntheticCover();
        String name = WorldCoverSource.name(36, -120);
        equal("ESA_WorldCover_10m_2021_v200_N36W120_Map.tif", name, "WorldCover file name");
        equal("ESA_WorldCover_10m_2021_v200_S03E006_Map.tif", WorldCoverSource.name(-3, 6), "southern and eastern file name");
        Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/cover/", exchange -> {
            String path = exchange.getRequestURI().getPath().substring("/cover/".length());
            String range = exchange.getRequestHeaders().getFirst("Range");
            hits.computeIfAbsent(path + " " + range, k -> new AtomicInteger()).incrementAndGet();
            if (!path.equals(name) || range == null) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
            String[] span = range.substring("bytes=".length()).split("-");
            int from = Integer.parseInt(span[0]), to = Math.min(file.length - 1, Integer.parseInt(span[1]));
            byte[] body = Arrays.copyOfRange(file, from, to + 1);
            exchange.sendResponseHeaders(206, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        Path cache = Files.createTempDirectory("worldcover-test");
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/cover/");
        // Block (row 2, column 3) spans these degrees: west half tree cover, east half water.
        double north = 39 - 2 * 1024 / 12000.0, west = -120 + 3 * 1024 / 12000.0;
        double treeLat = north - 100.5 / 12000, treeLon = west + 10.5 / 12000, waterLon = west + 900.5 / 12000;
        try {
            try (var cover = new WorldCoverSource(base, cache, true, 4, 2, Duration.ofSeconds(5))) {
                var pool = Executors.newFixedThreadPool(8);
                List<Future<Integer>> futures = new ArrayList<>();
                for (int i = 0; i < 16; i++) futures.add(pool.submit(() -> cover.code(treeLat, treeLon, 0)));
                for (var f : futures) equal(WorldCoverSource.TREE_COVER, f.get(), "tree cover pixel");
                pool.shutdown();
                equal(WorldCoverSource.WATER, cover.code(treeLat, waterLon, 0), "water pixel");
                equal(WorldCoverSource.NO_DATA, cover.code(37.5, -119.5, 0), "empty block is no data");
                equal(WorldCoverSource.GRASSLAND, cover.code(37.5, -119.5, 6), "coarsest overview");
                equal(WorldCoverSource.GRASSLAND, cover.code(36.00001, -117.00001, 6), "last overview pixel folds in");
                equal(WorldCoverSource.NO_DATA, cover.code(10.5, -40.5, 0), "missing file (open ocean) is no data");
                equal(WorldCoverSource.NO_DATA, cover.code(37.5, -119.5 + 360, 0), "longitude wraps");
                long headerReads = hits.entrySet().stream().filter(e -> e.getKey().startsWith(name + " bytes=0-")).mapToLong(e -> e.getValue().get()).sum();
                equal(1L, headerReads, "header read once");
                long blockReads = hits.entrySet().stream().filter(e -> e.getKey().startsWith(name) && !e.getKey().contains("bytes=0-")).mapToLong(e -> e.getValue().get()).sum();
                equal(2L, blockReads, "one read per block with data");
                fail(() -> cover.code(37.5, -119.5, 7), IllegalArgumentException.class, "overview beyond the file rejected");
            }
            try (var offline = new WorldCoverSource(base, cache, false, 4, 1, Duration.ofSeconds(5))) {
                int before = hits.values().stream().mapToInt(AtomicInteger::get).sum();
                equal(WorldCoverSource.WATER, offline.code(treeLat, waterLon, 0), "disk cache offline");
                equal(WorldCoverSource.NO_DATA, offline.code(10.5, -40.5, 0), "missing file remembered offline");
                equal(before, hits.values().stream().mapToInt(AtomicInteger::get).sum(), "no transfer when cached");
            }
        } finally {
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    private static void imagery() throws Exception {
        var image = new java.awt.image.BufferedImage(256, 256, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) image.setRGB(x, y, x < 128 ? 0xD0B080 : 0x305020);
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "jpg", out);
        byte[] jpeg = out.toByteArray();
        // The server's blank placeholder for tiles with no imagery is a small PNG.
        byte[] blank = TerrariumTileSource.encode(new float[256 * 256]);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/s2/", exchange -> {
            requests.incrementAndGet();
            String path = exchange.getRequestURI().getPath().substring("/s2/".length());
            byte[] body = path.equals("14/6000/3000.jpg") ? jpeg : path.equals("14/6000/3001.jpg") ? blank : null;
            if (body == null) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        Path cache = Files.createTempDirectory("imagery-test");
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/s2/");
        try {
            try (var imagery = new ImagerySource(base, cache, true, 4, 2, Duration.ofSeconds(5))) {
                var pool = Executors.newFixedThreadPool(8);
                List<Future<Integer>> futures = new ArrayList<>();
                for (int i = 0; i < 16; i++) futures.add(pool.submit(() -> imagery.pixel(3000L * 256 + 10, 6000L * 256 + 10, 14)));
                for (var f : futures) near(0xD0, f.get() >> 16 & 255, 6, "sand pixel red");
                pool.shutdown();
                equal(1, requests.get(), "one request for a tile wanted at once");
                near(0x50, imagery.pixel(3000L * 256 + 200, 6000L * 256 + 10, 14) >> 8 & 255, 6, "forest pixel green");
                equal(ImagerySource.NO_DATA, imagery.pixel(3001L * 256 + 5, 6000L * 256 + 5, 14), "placeholder tile is no imagery");
                equal(ImagerySource.NO_DATA, imagery.pixel(5, 5, 14), "missing tile is no imagery");
                double[] mean = new double[3];
                check(imagery.mean(3000 * 256 + 20.5, 6000 * 256 + 20.5, 14, 3, mean), "mean over imagery");
                near(ImagerySource.LINEAR[0xD0], mean[0], 0.03, "mean red in linear light");
                check(!imagery.mean(3001 * 256 + 20.5, 6000 * 256 + 20.5, 14, 3, mean), "mean over no imagery fails");
                // Halfway between the last sand and first forest pixel centres, the colour is their blend.
                check(imagery.bilinear(3000 * 256 + 128.0, 6000 * 256 + 50.5, 14, mean), "bilinear over imagery");
                near((ImagerySource.LINEAR[0xD0] + ImagerySource.LINEAR[0x30]) / 2, mean[0], 0.04, "bilinear blends across a pixel edge");
                check(imagery.bilinear(3000 * 256 + 20.5, 6000 * 256 + 50.5, 14, mean) && Math.abs(mean[0] - ImagerySource.LINEAR[0xD0]) < 0.03, "bilinear at a pixel centre is that pixel");
                fail(() -> imagery.tile(15, 0, 0), IllegalArgumentException.class, "zoom beyond the mosaic rejected");
            }
            try (var offline = new ImagerySource(base, cache, false, 4, 1, Duration.ofSeconds(5))) {
                int before = requests.get();
                near(0xD0, offline.pixel(3000L * 256 + 10, 6000L * 256 + 10, 14) >> 16 & 255, 6, "disk cache offline");
                equal(ImagerySource.NO_DATA, offline.pixel(3001L * 256 + 5, 6000L * 256 + 5, 14), "placeholder remembered offline");
                equal(before, requests.get(), "no transfer when cached");
                fail(() -> offline.pixel(9, 9, 13), MissingTerrainException.class, "uncached tile offline");
            }
        } finally {
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    private static void palette() {
        // Colours sampled from the Sentinel-2 cloudless mosaic at known places.
        GroundPalette.Reading dune = reading(254, 184, 96), hamada = reading(171, 134, 98), granite = reading(191, 166, 128);
        GroundPalette.Reading forest = reading(46, 66, 30), dryGrass = reading(180, 160, 118), river = reading(16, 25, 40), snow = reading(240, 244, 248);
        check(EnumSet.of(GroundPalette.Ground.RED_SAND, GroundPalette.Ground.SAND).contains(GroundPalette.match(dune, GroundPalette.DESERT_LOOSE, 0)), "Erg Chebbi dunes are sand");
        int red = 0;
        for (int i = 0; i < 100; i++) if (GroundPalette.match(dune, GroundPalette.DESERT_LOOSE, i / 100.0) == GroundPalette.Ground.RED_SAND) red++;
        check(red > 30 && red < 100, "orange dunes mix red and pale sand: " + red);
        check(EnumSet.of(GroundPalette.Ground.GRAVEL, GroundPalette.Ground.COARSE_DIRT).contains(GroundPalette.match(hamada, GroundPalette.DESERT_LOOSE, 0)), "stony desert plain is gravel or coarse dirt");
        check(EnumSet.of(GroundPalette.Ground.STONE, GroundPalette.Ground.ANDESITE, GroundPalette.Ground.DIORITE, GroundPalette.Ground.CALCITE)
                .contains(GroundPalette.match(granite, GroundPalette.ROCK, 0)), "Half Dome granite is pale grey rock: " + GroundPalette.match(granite, GroundPalette.ROCK, 0));
        equal(GroundPalette.Ground.STONE, GroundPalette.match(granite, new GroundPalette.Ground[]{GroundPalette.Ground.STONE}, 0.99), "single candidate");
        check(forest.green() && forest.vegetation() > 0.5, "forest is green");
        check(!dryGrass.green() && dryGrass.vegetation() < 0.25, "dry grass is not");
        check(river.shadow(), "dark water says nothing");
        check(snow.snow() && !granite.snow(), "snow is snow, pale granite is not");
        GroundPalette.Reading asphalt = reading(118, 120, 124), concrete = reading(192, 191, 188), sagebrush = reading(163, 122, 91);
        check(asphalt.grey() && concrete.grey(), "roads and roofs are grey");
        check(!dryGrass.grey() && !hamada.grey() && !sagebrush.grey() && !dune.grey(), "soils, scrub and sand are not grey");
        check(!java.util.Arrays.asList(GroundPalette.SOIL).contains(GroundPalette.Ground.GRAVEL), "no gravel in soil patches");
    }

    private static GroundPalette.Reading reading(int r, int g, int b) {
        return GroundPalette.read(new double[]{ImagerySource.LINEAR[r], ImagerySource.LINEAR[g], ImagerySource.LINEAR[b]});
    }

    /** Known places in the real Sentinel-2 cloudless mosaic. */
    private static void liveImagery() throws Exception {
        try (var imagery = new ImagerySource(ImagerySource.EOX_S2_CLOUDLESS_2016, null, true, 16, 4, Duration.ofSeconds(30))) {
            var grid = new MercatorGrid(1);
            double[] mean = new double[3];
            GeoPoint erg = new GeoPoint(31.15, -3.97);
            check(imagery.mean(grid.pixelX(grid.blockX(erg), 14), grid.pixelY(grid.blockZ(erg), 14), 14, 3, mean), "Erg Chebbi has imagery");
            check(GroundPalette.match(GroundPalette.read(mean), GroundPalette.DESERT_LOOSE, 0) == GroundPalette.Ground.RED_SAND, "Erg Chebbi reads as red sand");
            GeoPoint sea = new GeoPoint(30, -40);
            boolean any = imagery.mean(grid.pixelX(grid.blockX(sea), 14), grid.pixelY(grid.blockZ(sea), 14), 14, 1, mean);
            check(!any || GroundPalette.read(mean).shadow(), "the open Atlantic says nothing about ground");
        }
    }

    /** Known places in the real WorldCover map. */
    private static void liveCover() throws Exception {
        try (var cover = new WorldCoverSource(WorldCoverSource.AWS_WORLDCOVER, null, true, 16, 4, Duration.ofSeconds(30))) {
            equal(WorldCoverSource.WATER, cover.code(39.09, -120.04, 0), "Lake Tahoe is water");
            equal(WorldCoverSource.BUILT_UP, cover.code(37.7897, -122.4000, 0), "downtown San Francisco is built up");
            equal(WorldCoverSource.SNOW_AND_ICE, cover.code(72.58, -38.46, 0), "Greenland summit is ice");
            equal(WorldCoverSource.NO_DATA, cover.code(30.0, -40.0, 0), "mid-Atlantic has no file");
            equal(WorldCoverSource.WATER, cover.code(39.09, -120.04, 5), "Lake Tahoe in an overview");
            System.out.printf("GlobeTest live cover: %s%n", cover.stats());
        }
    }

    /**
     * A synthetic CHELSA GeoTIFF laid out as the real ones are: 43200 x 20880 16-bit pixels, one
     * deflated strip per row with horizontal differencing. Every row holds {@code fill} except the
     * rows given, and rows with the same values share one strip.
     */
    static byte[] syntheticClimate(int fill, Map<Integer, short[]> rows) throws Exception {
        int width = ClimateSource.WIDTH, height = ClimateSource.HEIGHT;
        short[] plain = new short[width];
        Arrays.fill(plain, (short) fill);
        int entries = 13, ifd = 8, arrays = ifd + 2 + entries * 12 + 4;
        int offsetsAt = arrays, countsAt = offsetsAt + height * 4, scaleAt = countsAt + height * 4, tieAt = scaleAt + 24, dataAt = tieAt + 48;
        var data = new java.io.ByteArrayOutputStream();
        Map<Integer, int[]> strips = new HashMap<>();
        int[] offsets = new int[height], counts = new int[height];
        for (int row = 0; row < height; row++) {
            short[] values = rows.getOrDefault(row, plain);
            int[] strip = strips.get(System.identityHashCode(values));
            if (strip == null) {
                var bytes = java.nio.ByteBuffer.allocate(width * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                short previous = 0;
                for (short v : values) { bytes.putShort((short) (v - previous)); previous = v; }
                byte[] z = deflate(bytes.array());
                strip = new int[]{dataAt + data.size(), z.length};
                data.write(z);
                strips.put(System.identityHashCode(values), strip);
            }
            offsets[row] = strip[0];
            counts[row] = strip[1];
        }
        var header = java.nio.ByteBuffer.allocate(dataAt).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        header.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(ifd);
        header.putShort((short) entries);
        int[][] tags = {{256, 4, 1, width}, {257, 4, 1, height}, {258, 3, 1, 16}, {259, 3, 1, 8}, {262, 3, 1, 1}, {273, 4, height, offsetsAt},
                {277, 3, 1, 1}, {278, 3, 1, 1}, {279, 4, height, countsAt}, {284, 3, 1, 1}, {317, 3, 1, 2}, {33550, 12, 3, scaleAt}, {33922, 12, 6, tieAt}};
        for (int[] t : tags) {
            header.putShort((short) t[0]).putShort((short) t[1]).putInt(t[2]);
            if (t[1] == 3) header.putShort((short) t[3]).putShort((short) 0);
            else header.putInt(t[3]);
        }
        header.putInt(0);
        for (int o : offsets) header.putInt(o);
        for (int c : counts) header.putInt(c);
        header.putDouble(1 / 120.0).putDouble(1 / 120.0).putDouble(0);
        header.putDouble(0).putDouble(0).putDouble(0).putDouble(-180.00013888885).putDouble(83.99986041515).putDouble(0);
        var out = new java.io.ByteArrayOutputStream();
        out.write(header.array());
        data.writeTo(out);
        return out.toByteArray();
    }

    private static void climate() throws Exception {
        // Row 6000 is latitude 34 N; columns 7000 and 7001 sit at about 121.66 W.
        int row = 6000, column = 7000;
        short[] mean = new short[ClimateSource.WIDTH], gap = new short[ClimateSource.WIDTH], rain = new short[ClimateSource.WIDTH], zones = new short[ClimateSource.WIDTH];
        Arrays.fill(mean, (short) 2882);
        mean[column] = (short) 2932;
        mean[column + 1] = (short) 2832;
        Arrays.fill(gap, (short) 2882);
        gap[column] = 0;
        Arrays.fill(rain, (short) 5000);
        Arrays.fill(zones, (short) ClimateSource.CFB);
        zones[column] = (short) ClimateSource.CSA;
        zones[column + 1] = (short) ClimateSource.CSB;
        Map<String, byte[]> served = new HashMap<>();
        served.put(ClimateSource.Layer.MEAN.file, syntheticClimate(2882, Map.of(row, mean, row + 10, gap)));
        served.put(ClimateSource.Layer.WARM.file, syntheticClimate(2982, Map.of()));
        served.put(ClimateSource.Layer.RAIN.file, syntheticClimate(5000, Map.of(row, rain)));
        served.put(ClimateSource.Layer.KOPPEN.file, syntheticClimate(ClimateSource.CFB, Map.of(row, zones)));
        Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/chelsa/", exchange -> {
            String path = exchange.getRequestURI().getPath().substring("/chelsa/".length());
            String range = exchange.getRequestHeaders().getFirst("Range");
            hits.computeIfAbsent(path + " " + range, k -> new AtomicInteger()).incrementAndGet();
            byte[] file = served.get(path);
            if (range == null || file == null && !path.equals(ClimateSource.DEM_FILE)) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
            String[] span = range.substring("bytes=".length()).split("-");
            long from = Long.parseLong(span[0]), to = Long.parseLong(span[1]);
            byte[] body;
            if (file != null) body = Arrays.copyOfRange(file, (int) from, (int) Math.min(file.length - 1, to) + 1);
            else {
                // The elevation grid: 1000 m everywhere, as geopotential.
                var floats = java.nio.ByteBuffer.allocate((int) (to - from + 1)).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                while (floats.hasRemaining()) floats.putFloat(9806.65f);
                body = floats.array();
            }
            exchange.sendResponseHeaders(206, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        Path cache = Files.createTempDirectory("climate-test");
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chelsa/");
        double lat = 84 - (row + 0.5) / 120, lon = -180 + (column + 0.5) / 120, quarter = 0.25 / 120;
        try {
            try (var climate = new ClimateSource(base, cache, true, 64, 2, Duration.ofSeconds(5))) {
                var pool = Executors.newFixedThreadPool(8);
                List<Future<ClimateSource.Climate>> futures = new ArrayList<>();
                for (int i = 0; i < 16; i++) futures.add(pool.submit(() -> climate.at(lat, lon + quarter, 1000)));
                for (var f : futures) near(17.55, f.get().meanTemperature(), 1e-6, "blend a quarter of the way between pixels");
                pool.shutdown();
                var here = climate.at(lat, lon + quarter, 1000);
                equal(ClimateSource.CSA, here.koppen(), "nearest pixel's zone");
                near(25.05, here.warmestQuarter(), 1e-6, "warmest quarter at the grid's own height");
                near(500, here.precipitation(), 1e-6, "yearly precipitation");
                near(17.55 + 6.5, climate.at(lat, lon + quarter, 0).meanTemperature(), 1e-6, "warmer 1000 m lower");
                near(17.55 - 13, climate.at(lat, lon + quarter, 3000).meanTemperature(), 1e-6, "colder 2000 m higher");
                equal(ClimateSource.CSB, climate.at(lat, lon + 3 * quarter, 1000).koppen(), "zone changes past the middle");
                double gapLat = 84 - (row + 10.5) / 120;
                near(15.05, climate.at(gapLat, lon + quarter, 1000).meanTemperature(), 1e-6, "a gap is skipped, not averaged in");
                near(climate.at(lat, lon + quarter, 1000).meanTemperature(), climate.at(lat, lon + quarter + 360, 1000).meanTemperature(), 0, "longitude wraps");
                check(climate.at(89.9, 10, 0) != null, "north of the grid reads its top row");
                for (var layer : ClimateSource.Layer.values()) {
                    long headerReads = hits.entrySet().stream().filter(e -> e.getKey().startsWith(layer.file + " bytes=0-")).mapToLong(e -> e.getValue().get()).sum();
                    equal(1L, headerReads, layer + " header read once");
                }
                // Strips are written in row order, so row 6000's own strip comes right after the shared plain one.
                var meanStrips = hits.entrySet().stream().filter(e -> e.getKey().startsWith(ClimateSource.Layer.MEAN.file) && !e.getKey().contains("bytes=0-"))
                        .sorted(Comparator.comparingLong(e -> Long.parseLong(e.getKey().substring(e.getKey().indexOf('=') + 1, e.getKey().indexOf('-', e.getKey().indexOf('='))))))
                        .toList();
                equal(1, meanStrips.get(1).getValue().get(), "a row wanted by many threads is read once");
                climate.prefetch(33.9, -121.8, 34.1, -121.5);
            }
            try (var offline = new ClimateSource(base, cache, false, 64, 1, Duration.ofSeconds(5))) {
                int before = hits.values().stream().mapToInt(AtomicInteger::get).sum();
                near(17.55, offline.at(lat, lon + quarter, 1000).meanTemperature(), 1e-6, "disk cache offline");
                equal(ClimateSource.CFB, offline.at(34.05, -121.6, 0).koppen(), "prefetched rows offline");
                equal(before, hits.values().stream().mapToInt(AtomicInteger::get).sum(), "no transfer when cached");
                fail(() -> offline.at(-40, 100, 0), MissingTerrainException.class, "uncached row offline");
            }
        } finally {
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    /** Known places in the real CHELSA climatologies. */
    private static void liveClimate() throws Exception {
        try (var climate = new ClimateSource(ClimateSource.CHELSA, null, true, 64, 4, Duration.ofSeconds(60))) {
            var manaus = climate.at(-3.1, -60.0, 40);
            equal(ClimateSource.AF, manaus.koppen(), "Manaus is tropical rainforest");
            near(27, manaus.meanTemperature(), 1.5, "Manaus is hot");
            check(manaus.precipitation() > 2000, "Manaus is wet");
            equal(ClimateSource.BWH, climate.at(23.0, 10.0, 600).koppen(), "the Sahara is hot desert");
            equal(ClimateSource.CFB, climate.at(51.5, -0.1, 20).koppen(), "London is oceanic");
            equal(ClimateSource.DFD, climate.at(62.0, 129.7, 100).koppen(), "Yakutsk is extremely continental");
            equal(ClimateSource.EF, climate.at(72.5, -40.0, 3000).koppen(), "inland Greenland is ice cap");
            var valley = climate.at(37.7240, -119.6365, 1200);
            var rim = climate.at(37.7307, -119.5741, 2200);
            check(valley.meanTemperature() - rim.meanTemperature() > 5, "Yosemite Valley is warmer than Glacier Point: " + valley + " " + rim);
            near(11.5, valley.meanTemperature(), 2, "Yosemite Valley mean temperature");
            System.out.printf("GlobeTest live climate: %s%n", climate.stats());
        }
    }

    /** Known places on the real dataset; tolerances reflect source resolution and bicubic smoothing. */
    private static void live() throws Exception {
        var grid = new MercatorGrid(1.0);
        try (var tiles = new TerrariumTileSource(TerrariumTileSource.AWS_TERRAIN_TILES, null, true, 64, 4, Duration.ofSeconds(30))) {
            var elevation = new GlobalElevation(grid, tiles, 12);
            double everest = maxNear(elevation, new GeoPoint(27.9881, 86.925), 600);
            check(everest > 8500 && everest < 8900, "Everest summit area elevation " + everest);
            double trench = minNear(elevation, new GeoPoint(11.3493, 142.1996), 4000);
            check(trench < -10_000 && trench > -11_100, "Challenger Deep elevation " + trench);
            double dead = elevation.metres(grid.blockX(new GeoPoint(31.5, 35.5)), grid.blockZ(new GeoPoint(31.5, 35.5)));
            check(dead < -350, "Dead Sea below sea level " + dead);
            double denver = elevation.metres(grid.blockX(new GeoPoint(39.7392, -104.9903)), grid.blockZ(new GeoPoint(39.7392, -104.9903)));
            check(denver > 1550 && denver < 1700, "Denver elevation " + denver);
            System.out.printf("GlobeTest live: Everest %.0f m, Challenger Deep %.0f m, Dead Sea %.0f m, Denver %.0f m; %s%n", everest, trench, dead, denver, tiles.stats());
        }
    }

    private static double maxNear(GlobalElevation e, GeoPoint p, double radius) {
        double cx = e.grid().blockX(p), cz = e.grid().blockZ(p), best = Double.NEGATIVE_INFINITY;
        for (double dz = -radius; dz <= radius; dz += radius / 20) for (double dx = -radius; dx <= radius; dx += radius / 20) best = Math.max(best, e.metres(cx + dx, cz + dz));
        return best;
    }

    private static double minNear(GlobalElevation e, GeoPoint p, double radius) {
        double cx = e.grid().blockX(p), cz = e.grid().blockZ(p), best = Double.POSITIVE_INFINITY;
        for (double dz = -radius; dz <= radius; dz += radius / 20) for (double dx = -radius; dx <= radius; dx += radius / 20) best = Math.min(best, e.metres(cx + dx, cz + dz));
        return best;
    }

    private static double haversine(GeoPoint a, GeoPoint b) {
        double p1 = Math.toRadians(a.latitude()), p2 = Math.toRadians(b.latitude());
        double dp = p2 - p1, dl = Math.toRadians(b.longitude() - a.longitude());
        double h = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * 6_371_008.8 * Math.asin(Math.sqrt(h));
    }

    private interface Action { void run() throws Exception; }

    private static void fail(Action action, Class<? extends Throwable> expected, String label) {
        try { action.run(); } catch (Throwable t) {
            if (expected.isInstance(t)) { checks++; return; }
            throw new AssertionError(label + ": expected " + expected.getSimpleName() + " but got " + t, t);
        }
        throw new AssertionError(label + ": expected " + expected.getSimpleName());
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }

    private static void equal(Object expected, Object actual, String label) { check(Objects.equals(expected, actual), label + ": expected " + expected + " got " + actual); }

    private static void near(double expected, double actual, double tolerance, String label) {
        check(Math.abs(expected - actual) <= tolerance, label + ": expected " + expected + " got " + actual);
    }
}
