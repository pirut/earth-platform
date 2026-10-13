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
        kernel();
        streaming();
        worldCover();
        copernicus();
        copernicusFill();
        climate();
        imagery();
        palette();
        webp();
        national();
        if ("1".equals(System.getenv("EARTH_LIVE_TILES"))) { live(); liveCover(); liveClimate(); liveImagery(); liveNational(); }
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

    /** Synthetic Copernicus ground: 100 m plus a gentle swell, with sea (0 m) in a band of latitudes. */
    private static float copernicusGround(double latitude, double longitude) {
        double inSquare = latitude - Math.floor(latitude);
        if (inSquare > 0.4 && inSquare < 0.6) return 0;
        return (float) (100 + 20 * Math.sin(longitude * 7) + 10 * Math.cos(latitude * 5));
    }

    /**
     * A Copernicus-style cloud-optimised GeoTIFF of one degree square at {@code size} pixels: 32-bit
     * floats, deflated, floating-point predictor, {@code tile}-pixel blocks, three halving overviews.
     */
    private static byte[] syntheticCopernicus(int south, int west, int size, int tile) throws Exception {
        int levels = 4;
        List<byte[]> blocks = new ArrayList<>();
        int[] firstBlock = new int[levels], width = new int[levels];
        for (int level = 0; level < levels; level++) {
            int w = size >> level;
            width[level] = w;
            firstBlock[level] = blocks.size();
            int across = Math.ceilDiv(w, tile);
            for (int br = 0; br < across; br++) for (int bc = 0; bc < across; bc++) {
                byte[] raw = new byte[tile * tile * 4];
                for (int y = 0; y < tile; y++) {
                    int at = y * tile * 4;
                    for (int x = 0; x < tile; x++) {
                        int row = br * tile + y, column = bc * tile + x;
                        float v = row < w && column < w ? copernicusGround(south + 1 - (row + 0.5) / w, west + (column + 0.5) / w) : 0;
                        int bits = Float.floatToIntBits(v);
                        for (int plane = 0; plane < 4; plane++) raw[at + plane * tile + x] = (byte) (bits >>> (24 - 8 * plane));
                    }
                    for (int i = tile * 4 - 1; i > 0; i--) raw[at + i] -= raw[at + i - 1];
                }
                var deflater = new java.util.zip.Deflater();
                deflater.setInput(raw);
                deflater.finish();
                var out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[65536];
                while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer));
                deflater.end();
                blocks.add(out.toByteArray());
            }
        }
        // Header: IFDs of 11 entries each, their offset and count arrays, then the blocks.
        int entries = 11, ifdBytes = 2 + entries * 12 + 4;
        int headerEnd = 8;
        int[] ifdAt = new int[levels], arraysAt = new int[levels];
        for (int level = 0; level < levels; level++) {
            int n = Math.ceilDiv(width[level], tile) * Math.ceilDiv(width[level], tile);
            ifdAt[level] = headerEnd;
            arraysAt[level] = headerEnd + ifdBytes;
            headerEnd += ifdBytes + n * 8;
        }
        int total = headerEnd;
        for (byte[] b : blocks) total += b.length;
        var file = java.nio.ByteBuffer.allocate(total).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        file.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(ifdAt[0]);
        int data = headerEnd;
        int[] blockAt = new int[blocks.size()];
        for (int i = 0; i < blocks.size(); i++) { blockAt[i] = data; data += blocks.get(i).length; }
        for (int level = 0; level < levels; level++) {
            int n = Math.ceilDiv(width[level], tile) * Math.ceilDiv(width[level], tile);
            file.position(ifdAt[level]);
            file.putShort((short) entries);
            int[][] tags = {{256, 4, 1, width[level]}, {257, 4, 1, width[level]}, {258, 3, 1, 32}, {259, 3, 1, 8}, {277, 3, 1, 1},
                    {317, 3, 1, 3}, {322, 3, 1, tile}, {323, 3, 1, tile}, {324, 4, n, n == 1 ? blockAt[firstBlock[level]] : arraysAt[level]},
                    {325, 4, n, n == 1 ? blocks.get(firstBlock[level]).length : arraysAt[level] + 4 * n}, {339, 3, 1, 3}};
            for (int[] t : tags) {
                file.putShort((short) t[0]).putShort((short) t[1]).putInt(t[2]);
                if (t[1] == 3) file.putShort((short) t[3]).putShort((short) 0); else file.putInt(t[3]);
            }
            file.putInt(level + 1 < levels ? ifdAt[level + 1] : 0);
            file.position(arraysAt[level]);
            for (int i = 0; i < n; i++) file.putInt(blockAt[firstBlock[level] + i]);
            for (int i = 0; i < n; i++) file.putInt(blocks.get(firstBlock[level] + i).length);
        }
        file.position(headerEnd);
        for (byte[] b : blocks) file.put(b);
        return file.array();
    }

    /** Serves synthetic Copernicus files by name under /cop/, answering range requests and counting reads. */
    private static HttpServer copernicusServer(Map<String, byte[]> files, AtomicInteger reads) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/cop/", exchange -> {
            reads.incrementAndGet();
            String path = exchange.getRequestURI().getPath().substring("/cop/".length());
            byte[] file = files.get(path.substring(path.indexOf('/') + 1));
            String range = exchange.getRequestHeaders().getFirst("Range");
            if (file == null || range == null) { exchange.sendResponseHeaders(403, -1); exchange.close(); return; }
            String[] span = range.substring("bytes=".length()).split("-");
            int from = Integer.parseInt(span[0]), to = Math.min(file.length - 1, Integer.parseInt(span[1]));
            byte[] body = Arrays.copyOfRange(file, from, to + 1);
            exchange.sendResponseHeaders(206, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static void copernicus() throws Exception {
        equal("Copernicus_DSM_COG_10_N66_00_W024_00_DEM", CopernicusSource.name(66, -24), "Copernicus file name");
        equal("Copernicus_DSM_COG_10_S45_00_E167_00_DEM", CopernicusSource.name(-45, 167), "southern and eastern file name");
        Map<String, byte[]> files = new HashMap<>();
        files.put(CopernicusSource.name(10, 20) + ".tif", syntheticCopernicus(10, 20, 512, 128));
        AtomicInteger reads = new AtomicInteger();
        HttpServer server = copernicusServer(files, reads);
        Path cache = Files.createTempDirectory("copernicus-test");
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/cop/");
        try {
            try (var dem = new CopernicusSource(base, cache, true, 4, 2, Duration.ofSeconds(5))) {
                for (int overview = 0; overview <= 3; overview++) {
                    double lat = 10.7 + 0.05 * overview, lon = 20.3;
                    near(copernicusGround(lat, lon), dem.metres(lat, lon, overview), 0.5 * (1 << overview), "bilinear ground at overview " + overview);
                }
                // A pixel centre reads its own value exactly.
                double centreLat = 11 - 100.5 / 512, centreLon = 20 + 300.5 / 512;
                near(copernicusGround(centreLat, centreLon), dem.metres(centreLat, centreLon, 0), 1e-4, "pixel centre");
                equal(0.0, dem.metres(10.5, 20.5, 0), "sea is 0 m");
                check(Double.isNaN(dem.metres(30.5, 20.5, 0)), "no file is NaN");
                check(!dem.covers(30.5, 20.5) && dem.covers(10.5, 20.5), "files cover their squares only");
                // At the square's edge the pixel past it lies in a square with no file: open sea.
                check(dem.metres(11 - 0.25 / 512, 20.3, 0) < copernicusGround(11 - 0.25 / 512, 20.3), "edge eases toward the sea beyond");
                fail(() -> dem.metres(10.5, 20.5, 4), IllegalArgumentException.class, "overview beyond the file rejected");
            }
            try (var offline = new CopernicusSource(base, cache, false, 4, 1, Duration.ofSeconds(5))) {
                int before = reads.get();
                near(copernicusGround(10.7, 20.3), offline.metres(10.7, 20.3, 0), 0.5, "disk cache offline");
                check(Double.isNaN(offline.metres(30.5, 20.5, 0)), "missing file remembered offline");
                equal(before, reads.get(), "no transfer when cached");
                fail(() -> offline.metres(-5.5, 20.5, 0), io.earthplatform.geography.MissingTerrainException.class, "uncached square offline fails");
            }
        } finally {
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    /**
     * The fill against synthetic Copernicus ground: Terrarium tiles that agree stay exactly as they
     * are; tiles 65 m too high everywhere, and tiles with the sea band filled in at 240 m, take
     * Copernicus, with its sea as open sea.
     */
    private static void copernicusFill() throws Exception {
        Map<String, byte[]> files = new HashMap<>();
        for (int s = 9; s <= 11; s++) for (int w = 19; w <= 21; w++) files.put(CopernicusSource.name(s, w) + ".tif", syntheticCopernicus(s, w, 512, 128));
        AtomicInteger reads = new AtomicInteger();
        HttpServer server = copernicusServer(files, reads);
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/cop/");
        int zoom = 13;
        long size = 256L << zoom;
        // The zoom 13 tile at 10.7 N 20.3 E, and one in the sea band.
        int tx = (int) ((20.3 + 180) / 360 * (1 << zoom));
        java.util.function.DoubleUnaryOperator row = lat -> (1 - Math.log(Math.tan(Math.toRadians(lat)) + 1 / Math.cos(Math.toRadians(lat))) / Math.PI) / 2 * (1 << zoom);
        int landY = (int) row.applyAsDouble(10.7), seaY = (int) row.applyAsDouble(10.5);
        try (var dem = new CopernicusSource(base, null, true, 32, 4, Duration.ofSeconds(5))) {
            for (String kind : List.of("agree", "offset", "filled")) {
                // Terrarium as the check zoom sees it, made from the same ground with this kind of fault.
                CopernicusFill.Tiles tiles = (z, x, y) -> {
                    float[] t = new float[256 * 256];
                    long s = 256L << z;
                    for (int py = 0; py < 256; py++) {
                        double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * (y * 256L + py + 0.5) / s))));
                        for (int px = 0; px < 256; px++) t[py * 256 + px] = terrarium(kind, lat, (x * 256L + px + 0.5) * 360.0 / s - 180);
                    }
                    return t;
                };
                var fill = new CopernicusFill(dem, tiles, 16);
                for (int ty : new int[]{landY, seaY}) {
                    float[] before = tiles.tile(zoom, tx, ty), after = before.clone();
                    fill.apply(after, zoom, tx, ty);
                    double worst = 0;
                    int sea = 0;
                    for (int py = 0; py < 256; py++) {
                        double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * (ty * 256L + py + 0.5) / size))));
                        for (int px = 0; px < 256; px++) {
                            double lon = (tx * 256L + px + 0.5) * 360.0 / size - 180;
                            worst = Math.max(worst, Math.abs(after[py * 256 + px] - copernicusGround(lat, lon)));
                            if (after[py * 256 + px] == 0f) sea++;
                        }
                    }
                    String where = ty == landY ? "land" : "sea band";
                    if (kind.equals("agree")) check(Arrays.equals(before, after), "agreeing tiles left exactly as they were (" + where + ")");
                    else {
                        check(worst < 1, kind + " tile takes Copernicus (" + where + "), worst " + worst + " m off");
                        if (ty == seaY) check(sea > 256 * 128, kind + " sea band becomes open sea (" + sea + " pixels)");
                    }
                }
                check(kind.equals("agree") ? fill.weight(10.7, 20.3) == 0 : fill.weight(10.7, 20.3) == 1, kind + " weight");
            }
        } finally {
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    /** Synthetic Terrarium for {@link #copernicusFill}: the ground as it is, 65 m high, or with the sea filled in at 240 m. */
    private static float terrarium(String kind, double lat, double lon) {
        float ground = copernicusGround(lat, lon);
        return switch (kind) {
            case "offset" -> ground == 0 ? 0 : ground + 65;
            case "filled" -> ground == 0 ? 240 : ground;
            default -> ground == 0 ? 0 : ground;
        };
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
    /** Lossless WebP images made by libwebp (cwebp -lossless -m 6), checked against its own decode. */
    private static void webp() throws Exception {
        String gradient = "UklGRpwAAABXRUJQVlA4TI8AAAAvJIAEALkyRPQ/BgratmHMH3e7w6AQYhqif0slnKb/E4BHMVlYWpxGkq1aj5kkYwCvXv7pwb+gvvzyW6Yxitu2jbMQ9Mw5QiYoaiBt21C2/TEBF0NaGrwJLc8fnmA8qlzAnNiXF4JenCjrxouT5by5IiglRimEZeTogGjWpLwQXj8pgsV6AWL5eJ0WB5aHLQA=";
        String palette3 = "UklGRugAAABXRUJQVlA4TNwAAAAvJIAEEFUgattG2PY9KMcfTCMLBNIQUBTbLBZM/Bmm0puXQCDxt8hgtg3iq3u8XNGkacm2bbBZslrNsK0tFbI1a9GwrGZjW22rZptt20bbahqlpi1mrBatxrZmJTZbUyZNszbWYjZoaliGbCxVVlvMKi3NYDM2qrURpFGbaoVl1rYVa1vJAgKMqlWSVavUGputla0MtalaaqZgbVvT1mAlU2tbbazM1qbG1rZS2RpTRUsLiU0tljUZNjVUllnKlhmytWLS2rZmaxZLNoUaphZVm6ER1arWNJGsprUA";
        String palette40 = "UklGRtICAABXRUJQVlA4TMUCAAAvJIAEED/BNADAJGu4O1kf4ACn8jbVaUR3i+MNNwIAEFF2jfUCj3xHL+4NTW22zaMTCQCQBnd3kjsPoNL5MEza0m4fcHf3OJxGAIBECWteyYzgktxhAfbg2MIS0e39EzN8syV+Arv4eD7dlhzF3f/fPecfuttM+/F+w/J2d0ZYZATGu//ZESF9dj+LChS/nYdBiL4+89xD4jMsm1+LtOw13zr/stWV9k3eFpMuJkz9vr18fm5t0Fx7pP+KueNB0S+q/FwnzOomi4jX0B5met/QdT2ghbGHAUcxg2n2M6IKELIFBYYBADKVlpKV3SANJET0P8BMCANYPc7I6by+8HK/VUfOqPcjXHgLGEGMGHj4y8sdrpJO7AHHsy/QwbuA5E0+oM8R5TEDZYhdGMGIx9WqT0yB1ue5lhWEKG1Fk5sfcANhxTeP3xmPsYpQL/+qpcHdtSfiMKND2FwiHr78s3W09hAwledMNtawu8oLzinh+wImoP1DSWvopcBrYN0nezQOR0iM2Nz/mvknPsxw14n3d3/lzGhVmryTvNtmIY64xBxYzP86vWp6HcrB1fb4LA2plHnyjyn1ce9QNrePRd2AQ/2SnupoQUgot0K/9wpJhhOufsghhcpVP02VlSZDRkkz/csMNVDf0mwBES80lrL0cLlmBmqzjXktw6l2v/LvW7fhD7M6wLfTA77805id85aGsF4siQb2IfDk+pJQZ94+GoSbT7elovUXGDEv0O6IJd3sv9zXTG5ar49divQwh46QpW92x+n4J0TB7m79CXk4Xz/+EekTojCP6ilJosJoOj4OBCLqfCTUsWprEhxed2M19FZQvWJU/7OfQ7+FcNOdDr3nmDOv2BsN9LJIR34RQk0dZhO3GMD22u9JS2l3FQQI78brHQQLwdcMhGnRX7KVadizp3jCz2ttDz+bLCuCHjiv53AkAA==";
        Object[][] images = {{gradient, -1717325314}, {palette3, 1234437887}, {palette40, -172448698}};
        for (Object[] image : images) {
            WebpLossless.Image decoded = WebpLossless.decode(Base64.getDecoder().decode((String) image[0]));
            equal(37, decoded.width(), "WebP width");
            equal(19, decoded.height(), "WebP height");
            equal(image[1], Arrays.hashCode(decoded.argb()), "WebP pixels match libwebp's");
        }
        float[] flat = NationalElevationTestTile.metres();
        check(flat.length == 512 * 512 && flat[0] == 1000f && flat[flat.length - 1] == 1000f, "Terrarium WebP tile decodes to 1000 m");
        byte[] truncated = Arrays.copyOf(Base64.getDecoder().decode(palette40), 300);
        fail(() -> WebpLossless.decode(truncated), java.io.IOException.class, "truncated WebP rejected");
        fail(() -> WebpLossless.decode(TerrariumTileSource.encode(new float[256 * 256])), java.io.IOException.class, "PNG is not WebP");
    }

    /** A 512-pixel lossless WebP Terrarium tile reading 1000 m everywhere. */
    private static final class NationalElevationTestTile {
        static final String WEBP = "UklGRi4AAABXRUJQVlA4TCEAAAAv/8F/AAdQ9A5G/wMCgWR/7xmK6H/Gf/7zn//85z//+T8A";
        static byte[] bytes() { return Base64.getDecoder().decode(WEBP); }
        static float[] metres() throws Exception {
            int[] argb = WebpLossless.decode(bytes()).argb();
            float[] out = new float[argb.length];
            for (int i = 0; i < argb.length; i++) out[i] = (((argb[i] >>> 16) & 0xFF) * 256 + ((argb[i] >>> 8) & 0xFF) + (argb[i] & 0xFF) / 256f) - 32768;
            return out;
        }
    }

    /**
     * National elevation over a synthetic region: one coverage cell holds a national model reading
     * 1000 m, everything else is uncovered. Tiles deep in the cell take it; tiles at its edge fade in.
     */
    private static void national() throws Exception {
        int rx = 33, ry = 22, cell = 13;
        int cx = rx * 128 + 5, cy = ry * 128 + 5;
        long cellId = NationalElevation.PmtilesIndex.base(cell) + hilbertIndex(cell, cx, cy);
        // Hilbert order round trip on every cell of a small zoom.
        for (int d = 0; d < 64; d++) {
            long[] xy = NationalElevation.PmtilesIndex.hilbert(3, d);
            equal((long) d, hilbertIndex(3, xy[0], xy[1]), "Hilbert curve round trip");
        }
        // A finer tile (zoom 14) inside the archive must not count as coverage.
        long fineId = NationalElevation.PmtilesIndex.base(14) + hilbertIndex(14, (rx * 128 + 9) * 2L, (ry * 128 + 9) * 2L);
        byte[] archive = pmtiles(new long[]{cellId, fineId}), empty = pmtiles(new long[0]);
        String catalogue = "{\"version\":\"x\",\"items\":[{\"name\":\"planet.pmtiles\",\"min_zoom\":0,\"max_zoom\":12},"
                + "{\"name\":\"6-33-22.pmtiles\",\"url\":\"x\",\"min_zoom\":13,\"max_zoom\":14},"
                + "{\"name\":\"6-40-20.pmtiles\",\"min_zoom\":13,\"max_zoom\":13}]}";
        Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            hits.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
            byte[] file = switch (path) {
                case "/downloads/download_urls.json" -> catalogue.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                case "/downloads/6-33-22.pmtiles" -> archive;
                case "/downloads/6-40-20.pmtiles" -> empty;
                default -> path.startsWith("/tiles/14/") || path.startsWith("/tiles/11/") ? NationalElevationTestTile.bytes() : null;
            };
            if (file == null) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
            String range = exchange.getRequestHeaders().getFirst("Range");
            int from = 0, to = file.length - 1;
            if (range != null) {
                String[] span = range.substring("bytes=".length()).split("-");
                from = Integer.parseInt(span[0]);
                to = Math.min(file.length - 1, Integer.parseInt(span[1]));
            }
            byte[] body = Arrays.copyOfRange(file, from, to + 1);
            exchange.sendResponseHeaders(range == null ? 200 : 206, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        Path cache = Files.createTempDirectory("national-test");
        String root = "http://127.0.0.1:" + server.getAddress().getPort();
        URI tiles = URI.create(root + "/tiles/"), downloads = URI.create(root + "/downloads/");
        try {
            try (var national = new NationalElevation(tiles, downloads, cache, true, 4, 2, Duration.ofSeconds(5))) {
                check(national.covers(cx, cy), "covered cell");
                check(!national.covers(cx + 1, cy) && !national.covers(rx * 128 + 9, ry * 128 + 9), "neighbours and finer tiles are not coverage");
                check(!national.covers(40 * 128, 20 * 128 + 3), "empty archive region uncovered");
                check(!national.covers(0, 0), "region outside the catalogue uncovered");
                // Zoom 15 tile (1, 1) of the cell's 4x4 lies a whole tile inside every edge.
                float[] inner = filled(-50f);
                inner[5] = 0f;
                national.refine(15, cx * 4 + 1, cy * 4 + 1, inner);
                near(1000, inner[128 * 256 + 128], 1e-3, "inside coverage the national value wins");
                near(1000, inner[255 * 256 + 255], 1e-3, "all of an inner tile is national");
                equal(0f, inner[5], "open sea stays");
                // Tile (0, 1) touches the uncovered cell to the west: its west edge keeps the composite.
                float[] edge = filled(-50f);
                national.refine(15, cx * 4, cy * 4 + 1, edge);
                near(-50, edge[128 * 256], 25, "uncovered edge keeps the composite");
                near(1000, edge[128 * 256 + 255], 1e-3, "past the feather the national value wins");
                check(edge[128 * 256 + 20] > edge[128 * 256 + 10] && edge[128 * 256 + 10] > edge[128 * 256], "feather rises inward");
                // A tile in no covered cell is untouched, and a zoom too coarse for regions too.
                float[] outside = filled(-50f), coarse = filled(-50f);
                national.refine(15, (cx + 3) * 4, cy * 4, outside);
                national.refine(6, rx, ry, coarse);
                check(outside[1000] == -50f && coarse[1000] == -50f, "uncovered and coarse tiles untouched");
                // Zoom 12 reads the coarse planet tile where the cell is covered.
                float[] wide = filled(-50f);
                national.refine(12, cx >> 1, cy >> 1, wide);
                int px = (cx & 1) * 128 + 64, py = (cy & 1) * 128 + 64;
                near(1000, wide[py * 256 + px], 1e-3, "coarse zoom refined inside coverage");
                equal(-50f, wide[(py ^ 128) * 256 + px], "coarse zoom outside coverage untouched");
            }
            int before = hits.values().stream().mapToInt(AtomicInteger::get).sum();
            try (var offline = new NationalElevation(tiles, downloads, cache, false, 4, 1, Duration.ofSeconds(5))) {
                float[] again = filled(-50f);
                offline.refine(15, cx * 4 + 1, cy * 4 + 1, again);
                near(1000, again[128 * 256 + 128], 1e-3, "disk cache offline");
                float[] missing = filled(-50f);
                fail(() -> offline.refine(15, cx * 4 + 2, cy * 4 + 2, missing), MissingTerrainException.class, "uncached national tile offline fails");
            }
            equal(before, hits.values().stream().mapToInt(AtomicInteger::get).sum(), "no transfer when cached");
            // Through the tile source: layers run after cleaning and the layered tile is what callers see.
            try (var national = new NationalElevation(tiles, downloads, cache, false, 4, 1, Duration.ofSeconds(5))) {
                check(national.id().equals(NationalElevation.DATASET_ID), "layer id");
            }
        } finally {
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    private static float[] filled(float metres) {
        float[] values = new float[256 * 256];
        Arrays.fill(values, metres);
        return values;
    }

    /** Position of tile (x, y) along a zoom's Hilbert curve, the inverse of the PMTiles order. */
    private static long hilbertIndex(int zoom, long x, long y) {
        long d = 0;
        for (long s = (1L << zoom) / 2; s > 0; s /= 2) {
            long rx = (x & s) > 0 ? 1 : 0, ry = (y & s) > 0 ? 1 : 0;
            d += s * s * ((3 * rx) ^ ry);
            if (ry == 0) {
                if (rx == 1) { x = s - 1 - x; y = s - 1 - y; }
                long swap = x; x = y; y = swap;
            }
        }
        return d;
    }

    /** A PMTiles v3 archive whose root directory lists the given tile ids, each a tiny tile. */
    private static byte[] pmtiles(long[] ids) throws Exception {
        long[] sorted = ids.clone();
        Arrays.sort(sorted);
        var dir = new java.io.ByteArrayOutputStream();
        varint(dir, sorted.length);
        long last = 0;
        for (long id : sorted) { varint(dir, id - last); last = id; }
        for (long ignored : sorted) varint(dir, 1);
        for (long ignored : sorted) varint(dir, 4);
        for (int i = 0; i < sorted.length; i++) varint(dir, i == 0 ? 1 : 0);
        var zipped = new java.io.ByteArrayOutputStream();
        try (var gzip = new java.util.zip.GZIPOutputStream(zipped)) { gzip.write(dir.toByteArray()); }
        byte[] root = zipped.toByteArray();
        var header = java.nio.ByteBuffer.allocate(127).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        header.put("PMTiles".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).put((byte) 3);
        header.putLong(127).putLong(root.length).putLong(0).putLong(0).putLong(127 + root.length).putLong(0)
                .putLong(127 + root.length).putLong(4L * sorted.length).putLong(sorted.length).putLong(sorted.length).putLong(sorted.length);
        header.put((byte) 1).put((byte) 2).put((byte) 1).put((byte) 4).put((byte) 13).put((byte) 14);
        var out = new java.io.ByteArrayOutputStream();
        out.write(header.array());
        out.write(root);
        out.write(new byte[4 * sorted.length]);
        return out.toByteArray();
    }

    private static void varint(java.io.ByteArrayOutputStream out, long value) {
        while (value >= 0x80) { out.write((int) (value & 0x7F) | 0x80); value >>>= 7; }
        out.write((int) value);
    }

    /** The real national models at peaks the composite rounds off, and one with none. */
    private static void liveNational() throws Exception {
        Path cache = Files.createTempDirectory("national-live");
        try (var national = new NationalElevation(NationalElevation.MAPTERHORN_TILES, NationalElevation.MAPTERHORN_DOWNLOADS, cache.resolve("n"), true, 16, 4, Duration.ofSeconds(30));
             var plain = new TerrariumTileSource(TerrariumTileSource.AWS_TERRAIN_TILES, cache.resolve("t"), true, 64, 4, Duration.ofSeconds(30));
             var layered = new TerrariumTileSource(TerrariumTileSource.AWS_TERRAIN_TILES, cache.resolve("t"), true, 64, 4, Duration.ofSeconds(30), List.of(national))) {
            var grid = new MercatorGrid(1.0);
            var before = new GlobalElevation(grid, plain, 15);
            var after = new GlobalElevation(grid, layered, 15);
            GeoPoint matterhorn = new GeoPoint(45.9763, 7.6586), dru = new GeoPoint(45.9325, 6.9558), fitzRoy = new GeoPoint(-49.2714, -73.0431);
            check(maxNear(before, matterhorn, 150) < 4400, "composite rounds off the Matterhorn");
            near(4478, maxNear(after, matterhorn, 150), 15, "national Matterhorn summit");
            near(3754, maxNear(after, dru, 150), 20, "national Aiguille du Dru summit");
            near(maxNear(before, fitzRoy, 300), maxNear(after, fitzRoy, 300), 1e-6, "no national model in Patagonia: unchanged");
        }
    }

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
