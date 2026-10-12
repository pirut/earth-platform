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
        kernel();
        streaming();
        worldCover();
        if ("1".equals(System.getenv("EARTH_LIVE_TILES"))) { live(); liveCover(); }
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
