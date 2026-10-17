package io.earthplatform.geography.globe;

import com.sun.net.httpserver.HttpServer;
import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.MissingTerrainException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dependency-free harness for the whole-planet surface layer, run by {@link GlobeSuiteTest} in the Gradle tests.
 * Synthetic tiles are served from a local HTTP server; the optional live section (EARTH_LIVE_TILES=1) samples real
 * AWS Terrain Tiles at known places. Run on its own, it needs {@code geography/src/main/resources} on the class path
 * too (the REMA layer reads its geoid there).
 */
public final class GlobeTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        projection();
        codec();
        mend();
        seams();
        repair();
        despike();
        strayZeros();
        bareEarth();
        clearings();
        islandBesideTown();
        kernel();
        streaming();
        outage();
        worldCover();
        copernicus();
        copernicusFill();
        climate();
        imagery();
        palette();
        rema();
        usgs();
        nationalLayers();
        if ("1".equals(System.getenv("EARTH_LIVE_TILES"))) { live(); liveCover(); liveClimate(); liveImagery(); liveRema(); liveUsgs(); }
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
        float[] back = TerrariumPng.decode(TerrariumTileSource.encode(metres));
        for (int i = 0; i < metres.length; i++) near(metres[i], back[i], 1 / 256.0, "Terrarium codec");
        fail(() -> TerrariumPng.decode(new byte[]{1, 2, 3}), java.io.IOException.class, "non-image rejected");
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
        float[] mended = TileMending.mend(metres, zoom, tileY);
        Set<Integer> bad = Set.of(128 * 256 + 128, 60 * 256 + 60, 61 * 256 + 60, 200, 10 * 256 + 21);
        for (int i : bad) near(clean[i], mended[i], 60, "bad pixel " + (i % 256) + "," + (i / 256) + " mended to within a pixel's slope");
        int changed = 0;
        for (int i = 0; i < clean.length; i++) if (!bad.contains(i) && mended[i] != clean[i]) changed++;
        equal(0, changed, "good pixels never change");
        check(TileMending.mend(clean, zoom, tileY) == clean, "clean tile returned as it is");
        // A sheer face 550 m high over three pixels, with a lip at its brink and ledges, wandering along its length.
        float[] face = new float[256 * 256];
        for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) {
            double at = x - 100 - 2 * Math.sin(y / 3.0);
            face[y * 256 + x] = (float) (1550 + 550 / (1 + Math.exp(at * 1.5)) + 70 * Math.exp(-(at + 1) * (at + 1)) + 25 * Math.sin(y * 1.7) * Math.exp(-at * at));
        }
        check(TileMending.mend(face, zoom, tileY) == face, "a sheer face keeps its lip and ledges");
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

    private static final int ISLE_X0 = 3100 * 256 + 128, ISLE_Y0 = 1900 * 256 + 128;

    /** Pixel offsets from (ISLE_X0, ISLE_Y0): a rooftop town west of a channel, a wooded island in it, and the sea. */
    private static int isle(double gx, double gy) {
        double x = Math.floor(gx) - ISLE_X0, y = Math.floor(gy) - ISLE_Y0;
        if (x < 0) return WorldCoverSource.BUILT_UP;
        if (x >= 12 && x < 28 && Math.abs(y) < 10) return WorldCoverSource.TREE_COVER;
        return WorldCoverSource.WATER;
    }

    private static float isleSurface(double gx, double gy) {
        double x = Math.floor(gx) - ISLE_X0, y = Math.floor(gy) - ISLE_Y0;
        long noise = Math.floorMod(((long) x * 92821 ^ (long) y * 68917) * 0x9E3779B97F4A7C15L >>> 40, 10);
        return switch (isle(gx, gy)) {
            case WorldCoverSource.BUILT_UP -> (float) (1.5 + (noise < 6 ? 20 : 1));
            case WorldCoverSource.TREE_COVER -> (float) (2 + 6 * Math.exp(-((x - 20) * (x - 20) + y * y) / 30.0) + noise * 0.4);
            default -> 0f;
        };
    }

    /** An island across water from a town keeps its own ground, and low coastal land stays above the sea. */
    private static void islandBesideTown() {
        long size = 256L << 12;
        GlobalElevation.Tiles source = (zoom, x, y) -> {
            float[] tile = new float[256 * 256];
            double scale = Math.scalb(1.0, 12 - zoom);
            for (int py = 0; py < 256; py++) for (int px = 0; px < 256; px++)
                tile[py * 256 + px] = isleSurface(((double) x * 256 + px + 0.5) * scale - 0.5, ((double) y * 256 + py + 0.5) * scale - 0.5);
            return tile;
        };
        BareEarth.Cover cover = (latitude, longitude) -> {
            double gx = (longitude + 180) / 360 * size, r = Math.toRadians(latitude);
            double gy = (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * size;
            return isle(gx - 0.5, gy - 0.5);
        };
        float[] raw = source.tile(12, 3100, 1900), tile = new BareEarth(source, cover, 8, 8).tile(12, 3100, 1900);
        java.util.function.IntBinaryOperator at = (dx, dy) -> (128 + dy) * 256 + 128 + dx;
        double worst = 0, lowest = Double.MAX_VALUE, town = 0;
        for (int dy = -9; dy <= 9; dy++) for (int dx = 12; dx < 28; dx++)
            worst = Math.max(worst, Math.abs(tile[at.applyAsInt(dx, dy)] - raw[at.applyAsInt(dx, dy)]));
        for (int dy = -60; dy <= 60; dy++) for (int dx = -60; dx < 0; dx++) {
            lowest = Math.min(lowest, tile[at.applyAsInt(dx, dy)]);
            town += tile[at.applyAsInt(dx, dy)] / (121.0 * 60);
        }
        check(worst < 0.5, "an island across water from a town keeps its ground: " + worst + " m changed");
        check(town < 6, "the town's own ground still comes down: " + town + " m");
        check(lowest >= 1 - 1e-4, "coastal town ground stays a metre over the sea: " + lowest);
    }

    private static final int CLEAR_X0 = 3000 * 256 + 128, CLEAR_Y0 = 2000 * 256 + 128;

    /** Gently sloping land with a round hill, a forest block standing 15 m on it, and a 10 m bank over a river. */
    private static float clearingSurface(double gx, double gy, double tilt) {
        double x = Math.floor(gx) - CLEAR_X0, y = Math.floor(gy) - CLEAR_Y0;
        double hillX = gx - CLEAR_X0 - 60, hillY = gy - CLEAR_Y0 + 60;
        double ground = 100 + tilt * (gx - CLEAR_X0) + 25 * Math.exp(-(hillX * hillX + hillY * hillY) / (2 * 12 * 12));
        if (y >= 80) ground -= 10;
        return (float) (ground + (clearingBlock(x, y) ? 15 : 0));
    }

    private static boolean clearingBlock(double x, double y) { return x >= -70 && x < -20 && y >= -40 && y < 40; }

    private static void clearings() {
        long size = 256L << 12;
        java.util.function.DoubleFunction<GlobalElevation.Tiles> source = tilt -> (zoom, x, y) -> {
            float[] tile = new float[256 * 256];
            double scale = Math.scalb(1.0, 12 - zoom);
            for (int py = 0; py < 256; py++) for (int px = 0; px < 256; px++)
                tile[py * 256 + px] = clearingSurface(((double) x * 256 + px + 0.5) * scale - 0.5, ((double) y * 256 + py + 0.5) * scale - 0.5, tilt);
            return tile;
        };
        BareEarth.Cover cover = (latitude, longitude) -> {
            double gx = (longitude + 180) / 360 * size, r = Math.toRadians(latitude);
            double gy = (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * size;
            double x = Math.floor(gx) - CLEAR_X0, y = Math.floor(gy) - CLEAR_Y0;
            if (y >= 90) return WorldCoverSource.WATER;
            return clearingBlock(x, y) ? WorldCoverSource.TREE_COVER : WorldCoverSource.GRASSLAND;
        };
        GlobalElevation.Tiles flat = source.apply(0.1);
        BareEarth bare = new BareEarth(flat, cover, 8, 8);
        float[] raw = flat.tile(12, 3000, 2000), tile = bare.tile(12, 3000, 2000);
        java.util.function.IntBinaryOperator at = (dx, dy) -> (128 + dy) * 256 + 128 + dx;
        for (int edge : new int[] {-70, -20}) {
            double steepest = 0;
            for (int dy = -30; dy <= 30; dy += 10)
                for (int dx = edge - 10; dx < edge + 10; dx++) steepest = Math.max(steepest, Math.abs(tile[at.applyAsInt(dx + 1, dy)] - tile[at.applyAsInt(dx, dy)]));
            check(steepest < 2, "a forest block's 15 m edge eases into a slope: " + steepest + " m a pixel at x " + edge);
        }
        double topEdge = 0;
        for (int dx = -60; dx <= -30; dx += 10)
            for (int dy = -50; dy < -30; dy++) topEdge = Math.max(topEdge, Math.abs(tile[at.applyAsInt(dx, dy + 1)] - tile[at.applyAsInt(dx, dy)]));
        check(topEdge < 2, "its edge across the slope eases too: " + topEdge);
        near(raw[at.applyAsInt(60, -60)], tile[at.applyAsInt(60, -60)], 1.5, "a round hill keeps its height");
        check(tile[at.applyAsInt(40, 81)] < tile[at.applyAsInt(40, 78)] - 9, "a river bank keeps its step: "
                + tile[at.applyAsInt(40, 78)] + " over " + tile[at.applyAsInt(40, 81)]);
        near(raw[at.applyAsInt(127, -127)], tile[at.applyAsInt(127, -127)], 0.2, "land well away from the block is untouched");
        // Finer zooms read the same correction, with no ledge left where the edge was.
        int fineX = (CLEAR_X0 - 70) * 8, fineY = CLEAR_Y0 * 8;
        float[] fine = bare.tile(15, fineX >> 8, fineY >> 8);
        double ledge = 0;
        int row = (fineY & 255) * 256;
        for (int i = 0; i < 255; i++) ledge = Math.max(ledge, Math.abs(fine[row + i + 1] - fine[row + i]));
        check(ledge < 0.6, "zoom 15 has no ledge at the old edge: " + ledge);
        // On a hillside the same block is left alone: a step there may well be the land's own.
        GlobalElevation.Tiles steep = source.apply(6);
        float[] hillside = steep.tile(12, 3000, 2000);
        BareEarth steepBare = new BareEarth((zoom, x, y) -> zoom == 12 && x == 3000 && y == 2000 ? hillside : steep.tile(zoom, x, y), cover, 8, 8);
        check(steepBare.tile(12, 3000, 2000) == hillside, "steps on steep land stay");
        // Far north, where pixels are small, the steps' square is cut to the window (it once ran past it at
        // 53 degrees and up, failing every tile there): the same block, 1024 tiles north at 68 degrees.
        int north = 1024;
        GlobalElevation.Tiles northern = (zoom, x, y) -> flat.tile(zoom, x, y + (zoom >= 12 ? north << (zoom - 12) : north >> (12 - zoom)));
        BareEarth.Cover northernCover = (latitude, longitude) -> {
            double r = Math.toRadians(latitude), gy = (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * size + north * 256L;
            double back = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * gy / size))));
            return cover.code(back, longitude);
        };
        BareEarth far = new BareEarth(northern, northernCover, 8, 8);
        for (int zoom = 11; zoom <= 15; zoom++) {
            int y = (2000 - north) << (zoom - 11) >> 1, x = 3000 << (zoom - 11) >> 1;
            float[] northTile = far.tile(zoom, x, y);
            equal(65536, northTile.length, "a tile at 68 degrees north comes back at zoom " + zoom);
        }
        float[] northTile = far.tile(12, 3000, 2000 - north);
        double northEdge = 0;
        for (int dy = -30; dy <= 30; dy += 10)
            for (int dx = -80; dx < -60; dx++) northEdge = Math.max(northEdge, Math.abs(northTile[at.applyAsInt(dx + 1, dy)] - northTile[at.applyAsInt(dx, dy)]));
        check(northEdge < 2, "far north, a forest block's edge still eases: " + northEdge);
        // A bug in working out the ground leaves the tile as it is rather than failing it.
        BareEarth.Cover breaks = (latitude, longitude) -> { throw new IllegalStateException("broken cover"); };
        check(Arrays.equals(new BareEarth(flat, breaks, 8, 8).tile(12, 3000, 2000), raw), "a broken bare-earth pass gives the tile as it is");
        // Fields kept on disk read back as they were worked out, and are worked out again over changed data.
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("bare-earth");
            float[] first = new BareEarth(flat, cover, 8, 8, dir).tile(12, 3000, 2000);
            check(java.nio.file.Files.isRegularFile(dir.resolve("12/3000/2000.field")), "a field is kept on disk");
            float[] again = new BareEarth(flat, cover, 8, 8, dir).tile(12, 3000, 2000);
            check(java.util.Arrays.equals(first, tile) && java.util.Arrays.equals(again, tile), "a kept field reads back as worked out");
            float[] changed = new BareEarth(steep, cover, 8, 8, dir).tile(12, 3000, 2000);
            check(java.util.Arrays.equals(changed, steepBare.tile(12, 3000, 2000)), "a kept field isn't read over other tiles");
            try (var files = java.nio.file.Files.walk(dir)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            }
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
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
        AtomicInteger flaky = new AtomicInteger(2), limited = new AtomicInteger(3);
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
            else if (path.equals("2/2/3.png") && limited.getAndDecrement() > 0) { status = 429; body = new byte[0]; exchange.getResponseHeaders().add("Retry-After", "1"); }
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
                // A host asking us to slow down is waited out (Retry-After) beyond the ordinary three tries.
                long slowStart = System.nanoTime();
                source.tile(zoom, 2, 3);
                equal(4, hits.get("2/2/3.png").get(), "three slow-down replies then success");
                check(System.nanoTime() - slowStart >= 2_500_000_000L, "Retry-After pauses were honoured");
                equal(5_000L, HostBackoff.pauseMillis("5", 1), "Retry-After seconds");
                equal(HostBackoff.MAX_PAUSE_MS, HostBackoff.pauseMillis("3600", 1), "Retry-After capped");
                equal(4_000L, HostBackoff.pauseMillis("Wed, 21 Oct 2015 07:28:00 GMT", 3), "date form doubles instead");

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
            // Layers run in order on each cleaned tile, once, and the later one wins.
            List<String> seen = Collections.synchronizedList(new ArrayList<>());
            ElevationLayer plus = new ElevationLayer() {
                public String id() { return "plus"; }
                public void refine(int z, int x, int y, float[] values) { seen.add("plus " + z + "/" + x + "/" + y); values[0] += 100; }
            };
            ElevationLayer pin = new ElevationLayer() {
                public String id() { return "pin"; }
                public void refine(int z, int x, int y, float[] values) { seen.add("pin"); values[1] = 42; }
            };
            try (var layered = new TerrariumTileSource(base, cache, false, 4, 1, Duration.ofSeconds(5), List.of(plus, pin))) {
                float[] tile = layered.tile(zoom, 1, 2);
                near(planeAt(zoom, 256, 512) + 100, tile[0], 1 / 256.0, "first layer applied");
                equal(42f, tile[1], "second layer applied after it");
                near(planeAt(zoom, 256 + 2, 512), tile[2], 1 / 256.0, "untouched pixel kept");
                check(layered.tile(zoom, 1, 2) == tile, "layered tile kept in memory");
                equal(List.of("plus 2/1/2", "pin"), seen, "each layer runs once, in order");
                equal(List.of(plus, pin), layered.layers(), "layers listed in order");
            }
            // A layer that breaks leaves the tile as the layers before it left it, half-written pixels and all
            // put back; one whose data can't be had fails the tile, to be tried again.
            ElevationLayer broken = new ElevationLayer() {
                public String id() { return "broken"; }
                public void refine(int z, int x, int y, float[] values) { values[2] = -999; values[values.length] = 1; }
            };
            ElevationLayer away = new ElevationLayer() {
                public String id() { return "away"; }
                public void refine(int z, int x, int y, float[] values) { throw new MissingTerrainException("layer data not cached"); }
            };
            try (var layered = new TerrariumTileSource(base, cache, false, 4, 1, Duration.ofSeconds(5), List.of(plus, broken, pin))) {
                float[] tile = layered.tile(zoom, 1, 2);
                near(planeAt(zoom, 256, 512) + 100, tile[0], 1 / 256.0, "layer before a broken one kept");
                near(planeAt(zoom, 256 + 2, 512), tile[2], 1 / 256.0, "a broken layer's writes are undone");
                equal(42f, tile[1], "layers after a broken one still run");
            }
            try (var layered = new TerrariumTileSource(base, cache, false, 4, 1, Duration.ofSeconds(5), List.of(away))) {
                fail(() -> layered.tile(zoom, 1, 2), MissingTerrainException.class, "a layer's missing data fails the tile");
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
     * A host that can't be reached is marked down after a few failed tries, so transfers to it fail at
     * once instead of each waiting out its own timeouts; one transfer checks it again a few seconds
     * later, and when it answers everything goes back to normal.
     */
    private static void outage() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean down = new java.util.concurrent.atomic.AtomicBoolean(true);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/tiles/", exchange -> {
            hits.incrementAndGet();
            // Down: the connection is dropped without an answer, as a host that is gone does.
            if (down.get()) { exchange.close(); return; }
            byte[] body = TerrariumTileSource.encode(new float[256 * 256]);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/tiles/");
        Path cache = Files.createTempDirectory("terrarium-outage");
        // A blip that breaks many transfers at once is not an outage: their retries get through.
        try (var source = new TerrariumTileSource(base, Files.createTempDirectory("terrarium-blip"), true, 32, 16, Duration.ofSeconds(5))) {
            var pool = Executors.newFixedThreadPool(16);
            List<Future<float[]>> tiles = new ArrayList<>();
            for (int i = 0; i < 16; i++) { int x = i % 8, y = i / 8; tiles.add(pool.submit(() -> source.tile(3, x, y))); }
            Thread.sleep(150);
            down.set(false);
            for (var tile : tiles) check(tile.get().length == 256 * 256, "transfer broken off in a blip retried");
            pool.shutdown();
            check(!HostBackoff.anyDown(), "a blip does not mark the host down");
            down.set(true);
        }
        try (var source = new TerrariumTileSource(base, cache, true, 4, 2, Duration.ofSeconds(5))) {
            check(!HostBackoff.anyDown(), "no host down at first");
            fail(() -> source.tile(2, 0, 0), MissingTerrainException.class, "a host that never answers fails the tile");
            check(HostBackoff.anyDown(), "no answer on every try: host marked down");
            int tries = hits.get();
            long start = System.nanoTime();
            for (int i = 0; i < 20; i++) {
                int x = i % 4;
                fail(() -> source.tile(2, x, 1), MissingTerrainException.class, "down host fails at once");
            }
            check(System.nanoTime() - start < 500_000_000L, "twenty tiles from a down host fail without waiting");
            equal(tries, hits.get(), "nothing sent while down");
            down.set(false);
            fail(() -> source.tile(2, 1, 1), MissingTerrainException.class, "still down until it is checked again");
            Thread.sleep(HostBackoff.FIRST_DOWN_MS + 200);
            // Due a check: it runs off to the side, so even this caller does not wait on the host.
            fail(() -> source.tile(2, 1, 1), MissingTerrainException.class, "the caller that starts a check still fails at once");
            for (int i = 0; i < 100 && HostBackoff.anyDown(); i++) Thread.sleep(20);
            check(!HostBackoff.anyDown(), "host back once a check got an answer");
            equal(tries + 1, hits.get(), "one request checked the host");
            source.tile(2, 1, 1);
            source.tile(2, 2, 1);
            equal(tries + 3, hits.get(), "transfers flow again");
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
        check(TileSeams.seams(clean, zoom, tileX, tileY, none) == clean, "clean tile returned as it is");
        float[] dip = {12, 61, 85, 37};                                   // as along 5°W in the Highlands
        for (int y = 0; y < 256; y++) for (int k = 0; k < 4; k++) metres[y * 256 + line - 1 + k] -= dip[k];
        for (int y = 0; y < 256; y++) for (int k = 0; k < 4; k++) metres[y * 256 + 100 + k] -= dip[k];   // a gully off the line
        metres[50 * 256 + line + 4] += 30; metres[51 * 256 + line + 4] += 30;                          // a knoll on it
        float[] mended = TileSeams.seams(metres, zoom, tileX, tileY, none);
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
        float[] westSide = TileSeams.seams(tiles[0], zoom, left, ty, both), eastSide = TileSeams.seams(tiles[1], zoom, right, ty, both);
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
        TileRepair.repair(tile, reference, 2, tx, ty);
        near(expected[5 * 256 + 114], tile[5 * 256 + 114], 9, "broken pit column takes the reference and its neighbours' detail");
        near(expected[200 * 256 + 115], tile[200 * 256 + 115], 9, "broken low column keeps the neighbours' 40 m rise");
        near(expected[40 * 256 + 40], tile[40 * 256 + 40], 9, "broken spike takes the reference");
        near(expected[41 * 256 + 41], tile[41 * 256 + 41], 9, "a 400 m pit on a gentle slope takes the reference");
        near(0, tile[60 * 256 + 60], 0, "open-sea zeros are left for the bathymetry fill");
        near(expected[9 * 256 + 7], tile[9 * 256 + 7], 1e-3, "relief the reference smooths away is kept");
        near(expected[9 * 256 + 8], tile[9 * 256 + 8], 1e-3, "ordinary pixels are untouched");
    }

    private static void despike() {
        // Flat coastal land 1 to 5 m high, sea (exact zeros) west of x 20.
        float[] tile = new float[256 * 256];
        for (int j = 0; j < 256; j++) for (int i = 20; i < 256; i++) tile[j * 256 + i] = 1 + ((i * 7 + j * 13) % 5);
        float[] ground = tile.clone();
        // A row of towers along a shore, 36 to 86 m, as at West Palm Beach at zoom 12, and a lone tower.
        for (int j = 100; j < 103; j++) tile[j * 256 + 60] = 36 + (j - 100) * 25;
        tile[120 * 256 + 90] = 140;
        // A lake at 76 m with a 50 m hill on its shore, and an esker 60 m high and one pixel wide crossing it.
        for (int j = 160; j < 200; j++) for (int i = 30; i < 80; i++) tile[j * 256 + i] = i < 55 ? 76 : 126;
        for (int j = 160; j < 200; j++) tile[j * 256 + 40] = 136;
        // A broad hill 30 m high, eight pixels across; a bluff 25 m high along the sea; a steady slope.
        for (int j = 0; j < 256; j++) for (int i = 0; i < 256; i++) {
            double d = Math.hypot(i - 150, j - 150);
            if (d < 8) tile[j * 256 + i] += (float) (30 * (1 - d / 8));
            if (i >= 20 && i < 24 && j > 200) tile[j * 256 + i] = 25;
            if (i >= 200 && j < 60) tile[j * 256 + i] = (i - 200) * 6f;
        }
        float[] before = tile.clone();
        TileDespike.despike(tile);
        for (int j = 100; j < 103; j++) near(ground[j * 256 + 60], tile[j * 256 + 60], 3, "tower at 60," + j + " lowered to the ground");
        near(ground[120 * 256 + 90], tile[120 * 256 + 90], 3, "a lone tower lowered to the ground");
        near(126, tile[180 * 256 + 55], 0, "a hill on a lake shore is kept");
        near(136, tile[180 * 256 + 40], 0, "an esker across a lake is kept");
        near(before[150 * 256 + 150], tile[150 * 256 + 150], 0, "a broad hill's top is kept");
        near(25, tile[230 * 256 + 20], 0, "a bluff along the sea is kept");
        near(before[30 * 256 + 250], tile[30 * 256 + 250], 0, "a steady slope is kept");
        near(0, tile[50 * 256 + 5], 0, "open sea stays zero");
        near(before[10 * 256 + 100], tile[10 * 256 + 100], 0, "ordinary ground is untouched");
    }

    /** Zeros that are no open sea, as lines across a shallow bank and squares of an island's ground, take the ground's height. */
    private static void strayZeros() {
        // Open sea (exact zeros) west of x 40; east of it a bank a few centimetres either side of 0 m and an island.
        float[] tile = new float[256 * 256];
        for (int j = 0; j < 256; j++) for (int i = 40; i < 256; i++) tile[j * 256 + i] = (i + j) % 2 == 0 ? 0.04f : -0.03f;
        for (int j = 150; j < 220; j++) for (int i = 150; i < 220; i++) tile[j * 256 + i] = 3 + (i + j) % 4;
        // A line two pixels wide across the bank, joining the open sea; an eight-pixel square of 0 m on the island;
        // and a wide bay of open sea reaching into the bank.
        for (int i = 40; i < 140; i++) { tile[60 * 256 + i] = 0; tile[61 * 256 + i] = 0; }
        for (int j = 180; j < 188; j++) for (int i = 180; i < 188; i++) tile[j * 256 + i] = 0;
        for (int j = 100; j < 130; j++) for (int i = 40; i < 100; i++) tile[j * 256 + i] = 0;
        // Thin zeros in a tile at zoom 10 are left: the bathymetry has no coarser zoom to fill from.
        check(StrayZeros.strayZeros(tile, 10, 0, 0, null) == tile, "zoom 10 left as it is");
        float[] out = StrayZeros.strayZeros(tile, 15, 0, 0, null);
        check(out != tile && tile[60 * 256 + 90] == 0, "the tile itself is not changed");
        for (int i = 42; i < 140; i += 7) check(out[60 * 256 + i] != 0 && Math.abs(out[60 * 256 + i]) < 0.05, "line across the bank at " + i + " takes the bank's level");
        for (int j = 180; j < 188; j += 3) for (int i = 180; i < 188; i += 3) near(4.5, out[j * 256 + i], 1.6, "island square at " + i + "," + j + " takes the island's height");
        near(0, out[115 * 256 + 70], 0, "a wide bay of open sea stays sea");
        near(0, out[50 * 256 + 10], 0, "open sea stays zero");
        near(0, out[60 * 256 + 20], 0, "open sea where the line leaves it stays zero");
        near(tile[30 * 256 + 100], out[30 * 256 + 100], 0, "the bank is untouched");
        // A small square cut by the tile's edge is measured on in the tile beside it: zeros there make it sea.
        float[] edge = new float[256 * 256];
        Arrays.fill(edge, 2f);
        for (int j = 50; j < 58; j++) for (int i = 252; i < 256; i++) edge[j * 256 + i] = 0;
        float[] beyondLand = new float[256 * 256], beyondSea = new float[256 * 256];
        Arrays.fill(beyondLand, 2f);
        for (int j = 50; j < 58; j++) for (int i = 0; i < 4; i++) beyondLand[j * 256 + i] = 0;
        near(2, StrayZeros.strayZeros(edge, 15, 5, 5, (z, x, y) -> beyondLand)[54 * 256 + 254], 0.01, "a square of 0 m across a tile edge is ground");
        near(0, StrayZeros.strayZeros(edge, 15, 5, 5, (z, x, y) -> beyondSea)[54 * 256 + 254], 0, "a bay of the sea beyond the edge stays sea");
        near(0, StrayZeros.strayZeros(edge, 15, 5, 5, (z, x, y) -> null)[54 * 256 + 254], 0, "with no tile beside it, it stays sea");
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
    /** A plane of heights in a synthetic REMA file: metres at a full-resolution pixel centre. */
    private static double remaPlane(double column, double row) { return 1000 + 0.5 * column - 0.25 * row; }

    /**
     * REMA over a synthetic 2 m file served over local HTTP: LZW and the floating-point predictor,
     * BigTIFF directories, an overview, a gap, missing files, the disk cache, and the layer's
     * projection, geoid and edge blend.
     */
    private static void rema() throws Exception {
        // LZW round trips, including runs long enough to fill the table and clear it.
        Random random = new Random(7);
        byte[] noise = new byte[200_000];
        for (int i = 0; i < noise.length; i++) noise[i] = (byte) (i % 977 < 400 ? random.nextInt(4) : random.nextInt(256));
        check(Arrays.equals(noise, CogBlocks.lzw(lzw(noise), noise.length)), "LZW round trip");
        byte[] zeros = new byte[300_000];
        check(Arrays.equals(zeros, CogBlocks.lzw(lzw(zeros), zeros.length)), "LZW round trip of one long run");
        fail(() -> CogBlocks.lzw(Arrays.copyOf(lzw(noise), 1000), noise.length), java.io.IOException.class, "truncated LZW rejected");

        // A file with its directories at the end, uncompressed, its overview tiled smaller, as some 3DEP files are.
        {
            int full = 48, tile = 32, overview = 24, small = 16;
            ByteBuffer file = ByteBuffer.allocate(70_000).order(ByteOrder.LITTLE_ENDIAN);
            file.put(new byte[]{'I', 'I', 42, 0});
            int ifd = 66_000, ifd2 = ifd + 2 + 12 * 12 + 4, arrays = ifd2 + 2 + 11 * 12 + 4;
            file.putInt(4, ifd);
            float[] first = new float[tile * tile];
            for (int i = 0; i < first.length; i++) first[i] = i * 0.5f;
            file.position(16);
            for (float v : first) file.putFloat(v);
            int blockBytes = tile * tile * 4, smallBytes = small * small * 4;
            // Level 0: 2 x 2 tiles of 32; level 1: 2 x 2 tiles of 16. All tiles but the first point at it.
            int[] tiles0 = {16, 16, 16, 16}, tiles1 = {16, 16, 16, 16};
            int at = arrays;
            int offsets0 = at, counts0 = at + 16, offsets1 = at + 32, counts1 = at + 48, scale = at + 64, tie = at + 88;
            for (int i = 0; i < 4; i++) {
                file.putInt(offsets0 + 4 * i, tiles0[i]);
                file.putInt(counts0 + 4 * i, blockBytes);
                file.putInt(offsets1 + 4 * i, tiles1[i]);
                file.putInt(counts1 + 4 * i, smallBytes);
            }
            file.putDouble(scale, 10).putDouble(scale + 8, 10).putDouble(scale + 16, 0);
            double[] ties = {0, 0, 0, 1000, 2000, 0};
            for (int i = 0; i < 6; i++) file.putDouble(tie + 8 * i, ties[i]);
            int[][] entries0 = {{256, 3, 1, full}, {257, 3, 1, full}, {258, 3, 1, 32}, {259, 3, 1, 1}, {277, 3, 1, 1}, {322, 3, 1, tile},
                    {323, 3, 1, tile}, {324, 4, 4, offsets0}, {325, 4, 4, counts0}, {339, 3, 1, 3}, {33550, 12, 3, scale}, {33922, 12, 6, tie}};
            int[][] entries1 = {{254, 4, 1, 1}, {256, 3, 1, overview}, {257, 3, 1, overview}, {258, 3, 1, 32}, {259, 3, 1, 1}, {277, 3, 1, 1},
                    {322, 3, 1, small}, {323, 3, 1, small}, {324, 4, 4, offsets1}, {325, 4, 4, counts1}, {339, 3, 1, 3}};
            int[] where = {ifd, ifd2};
            int[][][] all = {entries0, entries1};
            for (int d = 0; d < 2; d++) {
                file.putShort(where[d], (short) all[d].length);
                for (int e = 0; e < all[d].length; e++) {
                    int[] entry = all[d][e];
                    int base = where[d] + 2 + 12 * e;
                    file.putShort(base, (short) entry[0]).putShort(base + 2, (short) entry[1]).putInt(base + 4, entry[2]);
                    if (entry[1] == 3 && entry[2] == 1) file.putShort(base + 8, (short) entry[3]);
                    else file.putInt(base + 8, entry[3]);
                }
                file.putInt(where[d] + 2 + 12 * all[d].length, d == 0 ? ifd2 : 0);
            }
            byte[] bytes = file.array();
            CogHeader.Pieces pieces = new CogHeader.Pieces();
            pieces.add(0, Arrays.copyOf(bytes, 65_536));
            try {
                CogHeader.parse(pieces);
                check(false, "directories past the bytes read are asked for");
            } catch (FloatCogs.HeaderTooShort need) {
                check(need.offset == ifd, "the bytes asked for are where the directories lie");
                pieces.add(need.offset, Arrays.copyOfRange(bytes, (int) need.offset, bytes.length));
            }
            FloatCogs.Header header = CogHeader.parse(CogHeader.Pieces.of(pieces.bytes()));
            check(header.overviews() == 2 && !header.compressed() && header.predictor() == 1 && header.x0() == 1000 && header.y0() == 2000,
                    "a file with its directories at the end reads, through the disk cache's form too");
            check(Arrays.equals(first, CogBlocks.decode(Arrays.copyOfRange(bytes, 16, 16 + blockBytes), tile, false, 1, false)), "uncompressed block");
            fail(() -> CogBlocks.decode(new byte[100], tile, false, 1, false), java.io.IOException.class, "short uncompressed block rejected");
            byte[] raw = Arrays.copyOf(bytes, 16);
            check(Arrays.equals(raw, CogHeader.Pieces.of(raw).bytes()), "a header read from the start alone is cached as it is");
        }

        // Projection and geoid against PROJ (EPSG:3031) and NGA's grid.
        double[][] places = {{-78.5254, -85.6171, -1247100.934, 95584.793}, {-77.53, 167.153, 302405.631, -1326007.625},
                {-60, 0, 0, 3333134.028}, {-71, 90, 2082760.109, 0}};
        for (double[] place : places) {
            double[] xy = RemaElevation.project(place[0], place[1]);
            near(place[2], xy[0], 1e-3, "EPSG:3031 x at " + place[0] + ", " + place[1]);
            near(place[3], xy[1], 1e-3, "EPSG:3031 y at " + place[0] + ", " + place[1]);
        }
        near(15.13, RemaElevation.geoid(-60, 0), 0.05, "EGM96 on a grid node");
        near(-53.73, RemaElevation.geoid(-77.53, 167.153), 0.5, "EGM96 at Erebus");
        near(-15.4, RemaElevation.geoid(-78.5254, -85.6171), 0.6, "EGM96 at Vinson");
        near(RemaElevation.geoid(-70, -180), RemaElevation.geoid(-70, 180), 1e-9, "EGM96 wraps the antimeridian");

        // One 2 km file of 1024 x 1024 pixels around a place on the ice, inside its 50 km square.
        double[] centre = RemaElevation.project(-78, -80);
        double x0 = Math.floor(centre[0] / 2) * 2 - 600, y0 = Math.floor(centre[1] / 2) * 2 + 600;
        int column = (int) Math.floor((centre[0] + 3_100_000) / 100_000), row = (int) Math.floor((centre[1] + 3_100_000) / 100_000);
        int east = centre[0] + 3_100_000 - column * 100_000 >= 50_000 ? 2 : 1, north = centre[1] + 3_100_000 - row * 100_000 >= 50_000 ? 2 : 1;
        double squareWest = column * 100_000 - 3_100_000 + (east - 1) * 50_000, squareSouth = row * 100_000 - 3_100_000 + (north - 1) * 50_000;
        check(x0 > squareWest && x0 + 2048 < squareWest + 50_000 && y0 - 2048 > squareSouth && y0 < squareSouth + 50_000, "fixture file inside its square");
        String square = String.format(Locale.ROOT, "%02d_%02d", row, column);
        String path = "/rema/2m/" + square + "/" + square + "_" + north + "_" + east + "_2m_v2.0_dem.tif";
        float[] full = new float[1024 * 1024];
        for (int j = 0; j < 1024; j++) for (int i = 0; i < 1024; i++) full[j * 1024 + i] = (float) remaPlane(i, j);
        for (int j = 600; j < 606; j++) for (int i = 600; i < 606; i++) full[j * 1024 + i] = -9999f;
        float[] half = new float[512 * 512];
        for (int j = 0; j < 512; j++) for (int i = 0; i < 512; i++) half[j * 512 + i] = (float) remaPlane(2 * i + 0.5, 2 * j + 0.5);
        byte[] file = remaTiff(x0, y0, full, 1024, half, 512);

        Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", exchange -> {
            String asked = exchange.getRequestURI().getPath();
            hits.computeIfAbsent(asked, k -> new AtomicInteger()).incrementAndGet();
            if (!asked.equals(path)) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
            String range = exchange.getRequestHeaders().getFirst("Range");
            String[] bounds = range.substring("bytes=".length()).split("-");
            int from = Integer.parseInt(bounds[0]), to = Math.min(file.length - 1, Integer.parseInt(bounds[1]));
            byte[] body = Arrays.copyOfRange(file, from, to + 1);
            exchange.sendResponseHeaders(206, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        Path cache = Files.createTempDirectory("rema-test");
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/rema/");
        try {
            try (var rema = new RemaSource(base, cache, true, 8, 2, Duration.ofSeconds(5))) {
                // Pixel centres and points between them read the plane.
                for (int[] p : new int[][]{{0, 0}, {5, 900}, {700, 3}, {1022, 1021}, {511, 512}}) {
                    double x = x0 + 2 * (p[0] + 0.5), y = y0 - 2 * (p[1] + 0.5);
                    near(remaPlane(p[0], p[1]), rema.metres(RemaSource.FINE, 0, x, y), 1e-3, "REMA pixel " + p[0] + "," + p[1]);
                    near(remaPlane(p[0] + 0.25, p[1] + 0.5), rema.metres(RemaSource.FINE, 0, x + 0.5, y - 1), 1e-3, "REMA bilinear near " + p[0] + "," + p[1]);
                    near(1, rema.coverage(RemaSource.FINE, 0, x, y), 1e-9, "REMA coverage inside at " + p[0] + "," + p[1]);
                }
                near(remaPlane(300.5, 200.5), rema.metres(RemaSource.FINE, 1, x0 + 2 * 301, y0 - 2 * 201), 1e-3, "REMA overview pixel");
                // Overviews deeper than the file has fall back to its coarsest.
                near(remaPlane(300.5, 200.5), rema.metres(RemaSource.FINE, 6, x0 + 2 * 301, y0 - 2 * 201), 1e-3, "REMA missing overview falls back");
                double hx = x0 + 2 * 603, hy = y0 - 2 * 603;
                check(Double.isNaN(rema.metres(RemaSource.FINE, 0, hx, hy)), "REMA gap reads no data");
                near(0, rema.coverage(RemaSource.FINE, 0, hx, hy), 1e-9, "REMA gap has no coverage");
                near(remaPlane(1205.0 / 2, 1205.0 / 2), rema.metres(RemaSource.FINE, 1, hx, hy), 1e-3, "REMA overview covers the gap");
                // Next to the gap, only valid neighbours count.
                near(remaPlane(599, 602.5), rema.metres(RemaSource.FINE, 0, x0 + 2 * 599.5 + 0.5, hy), 1e-3, "REMA bilinear skips gap pixels");
                check(Double.isNaN(rema.metres(RemaSource.FINE, 0, x0 + 2048 + 10, y0 - 100)), "REMA outside the file reads no data");
                check(Double.isNaN(rema.metres(RemaSource.FINE, 0, x0 - 60_000, y0)), "REMA missing file reads no data");
                check(Double.isNaN(rema.metres(RemaSource.FINE, 0, 0, 3_400_000)), "REMA beyond its grid reads no data");
                check(Files.exists(cache.resolve("2m/" + square + "/" + square + "_" + north + "_" + (3 - east) + "_2m_v2.0_dem.none"))
                        || Files.exists(cache.resolve("2m/" + square + "/" + square + "_" + north + "_" + east + "_2m_v2.0_dem.header")), "REMA headers cached");
                fail(() -> rema.metres(RemaSource.FINE, 7, x0, y0), IllegalArgumentException.class, "REMA overview out of range rejected");

                // The layer on a zoom-15 tile across the file's east edge.
                var layer = new RemaElevation(rema);
                int zoom = 15;
                double world = 256.0 * (1 << zoom);
                double edgeLat = remaLatitude(x0 + 2048, y0 - 1024), edgeLon = Math.toDegrees(Math.atan2(x0 + 2048, y0 - 1024));
                int tx = (int) ((edgeLon + 180) / 360 * (1 << zoom));
                int ty = (int) Math.floor((1 - Math.log(Math.tan(Math.PI / 4 + Math.toRadians(edgeLat) / 2)) / Math.PI) / 2 * (1 << zoom));
                float[] values = new float[256 * 256];
                Arrays.fill(values, 500f);
                values[3] = 0f;
                layer.refine(zoom, tx, ty, values);
                int taken = 0, kept = 0, between = 0;
                for (int j = 0; j < 256; j++) for (int i = 0; i < 256; i++) {
                    double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * (ty * 256.0 + j + 0.5) / world))));
                    double lon = (tx * 256.0 + i + 0.5) / world * 360 - 180;
                    double[] xy = RemaElevation.project(lat, lon);
                    double fx = (xy[0] - x0) / 2 - 0.5, fy = (y0 - xy[1]) / 2 - 0.5;
                    float v = values[j * 256 + i];
                    if (j * 256 + i == 3) { equal(0f, v, "REMA layer leaves open sea"); continue; }
                    if (fx < 1000 && fx > 0 && fy > 0 && fy < 1000) {
                        near(remaPlane(fx, fy) - RemaElevation.geoid(lat, lon), v, 0.01, "REMA layer inside the data at " + i + "," + j);
                        taken++;
                    } else if (fx > 1040) {
                        equal(500f, v, "REMA layer keeps the tile beyond the data at " + i + "," + j);
                        kept++;
                    } else between++;
                }
                check(taken > 1000 && kept > 1000 && between > 0, "REMA fixture tile crosses the data's edge: " + taken + " / " + between + " / " + kept);
                // North of REMA nothing changes.
                float[] north50 = new float[256 * 256];
                Arrays.fill(north50, 123f);
                layer.refine(8, 10, 100, north50);
                check(allEqual(north50, 123f), "REMA layer leaves tiles north of 50 S");
                layer.refine(RemaElevation.MIN_ZOOM - 1, 0, 127, north50);
                check(allEqual(north50, 123f), "REMA layer leaves coarse tiles");
            }
            int transfers = hits.get(path).get();
            try (var offline = new RemaSource(base, cache, false, 8, 1, Duration.ofSeconds(5))) {
                near(remaPlane(5, 900), offline.metres(RemaSource.FINE, 0, x0 + 11, y0 - 1801), 1e-3, "REMA disk cache offline");
                equal(transfers, hits.get(path).get(), "REMA cached blocks need no transfer");
            }
            try (var empty = new RemaSource(base, Files.createTempDirectory("rema-empty"), false, 8, 1, Duration.ofSeconds(5))) {
                fail(() -> empty.metres(RemaSource.FINE, 0, x0 + 11, y0 - 1801), MissingTerrainException.class, "REMA uncached file offline fails");
            }
        } finally {
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    private static double usgsPlane(double column, double row) { return 160 + 0.05 * column - 0.03 * row; }

    /** The 3DEP layer against a fixture 1° cell served over HTTP: cells, edges, sea and bathymetry. */
    private static void usgs() throws Exception {
        equal("n44w080", UsgsElevation.cellName(UsgsElevation.cell(43.07, -79.07)), "3DEP cell at Niagara");
        equal("n44w080", UsgsElevation.cellName(UsgsElevation.cell(44.0, -80.0)), "3DEP cell holds its north-west corner");
        equal("s14w171", UsgsElevation.cellName(UsgsElevation.cell(-14.3, -170.7)), "3DEP cell in American Samoa");
        equal("n53e177", UsgsElevation.cellName(UsgsElevation.cell(52.5, 177.5)), "3DEP cell past the antimeridian");
        equal("n44w080/USGS_13_n44w080_20230227.tif", UsgsElevation.name(UsgsElevation.cell(43.07, -79.07)), "3DEP cell pinned to its dated release");
        fail(() -> UsgsElevation.name(UsgsElevation.cell(48.85, 2.35)), IllegalArgumentException.class, "3DEP has no cell in Paris");

        // A coarse stand-in for n44w080: 1024 pixels a degree, a 6-pixel margin, 256-pixel blocks.
        double p = 1.0 / 1024, x0 = -80 - 6 * p, y0 = 44 + 6 * p;
        int n = 1036;
        float[] full = new float[n * n];
        for (int j = 0; j < n; j++) for (int i = 0; i < n; i++) {
            // No data in the north (past the border); sea level and below in the south-east.
            full[j * n + i] = j < 300 ? -999999f : i >= 900 && j >= 900 ? -2f : (float) usgsPlane(i, j);
        }
        int h = n / 2;
        float[] half = new float[h * h];
        for (int j = 0; j < h; j++) for (int i = 0; i < h; i++) {
            double fi = 2 * i + 0.5, fj = 2 * j + 0.5;
            half[j * h + i] = fj < 300 ? -999999f : fi >= 900 && fj >= 900 ? -2f : (float) usgsPlane(fi, fj);
        }
        byte[] file = floatTiff(x0, y0, p, 256, full, n, half, h);
        String path = "/3dep/n44w080/USGS_13_n44w080_20230227.tif";
        Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", exchange -> {
            String asked = exchange.getRequestURI().getPath();
            hits.computeIfAbsent(asked, k -> new AtomicInteger()).incrementAndGet();
            if (!asked.equals(path)) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
            String[] bounds = exchange.getRequestHeaders().getFirst("Range").substring("bytes=".length()).split("-");
            int from = Integer.parseInt(bounds[0]), to = Math.min(file.length - 1, Integer.parseInt(bounds[1]));
            byte[] body = Arrays.copyOfRange(file, from, to + 1);
            exchange.sendResponseHeaders(206, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        Path cache = Files.createTempDirectory("3dep-test");
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/3dep/");
        try {
            // Zoom-10 pixels read the fixture's overview (its pixels are coarse for a cell).
            int zoom = 10;
            double world = 256.0 * (1 << zoom);
            float[] online;
            java.util.function.DoubleUnaryOperator column = lon -> (lon + 180) / 360 * (1 << zoom);
            java.util.function.DoubleUnaryOperator row = lat -> (1 - Math.log(Math.tan(Math.PI / 4 + Math.toRadians(lat) / 2)) / Math.PI) / 2 * (1 << zoom);
            try (var layer = new UsgsElevation(base, cache, true, 16, 2, Duration.ofSeconds(5))) {
                // A tile across the data's north edge (row 300), with a pixel of open sea.
                int tx = (int) column.applyAsDouble(x0 + 500 * p), ty = (int) row.applyAsDouble(y0 - 300 * p);
                float[] values = new float[256 * 256];
                Arrays.fill(values, 75f);
                values[5] = 0f;
                layer.refine(zoom, tx, ty, values);
                int taken = 0, kept = 0, between = 0;
                for (int j = 0; j < 256; j++) for (int i = 0; i < 256; i++) {
                    double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * (ty * 256.0 + j + 0.5) / world))));
                    double lon = (tx * 256.0 + i + 0.5) / world * 360 - 180;
                    double fx = (lon - x0) / p - 0.5, fy = (y0 - lat) / p - 0.5;
                    float v = values[j * 256 + i];
                    if (j * 256 + i == 5) { equal(0f, v, "3DEP layer leaves open sea"); continue; }
                    if (fy > 306) { near(usgsPlane(fx, fy), v, 0.01, "3DEP layer inside the data at " + i + "," + j); taken++; }
                    else if (fy < 290) { equal(75f, v, "3DEP layer keeps the tile past the data at " + i + "," + j); kept++; }
                    else between++;
                }
                check(taken > 1000 && kept > 1000 && between > 0, "3DEP fixture tile crosses the data's edge: " + taken + " / " + between + " / " + kept);
                online = values;

                // Where 3DEP reads sea level or below, land takes it but bathymetry stays.
                int sx = (int) column.applyAsDouble(x0 + 960 * p), sy = (int) row.applyAsDouble(y0 - 960 * p);
                float[] sea = new float[256 * 256];
                for (int k = 0; k < sea.length; k++) sea[k] = k % 2 == 0 ? -40f : 3f;
                layer.refine(zoom, sx, sy, sea);
                int low = 0;
                for (int j = 0; j < 256; j++) for (int i = 0; i < 256; i++) {
                    double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2 * (sy * 256.0 + j + 0.5) / world))));
                    double lon = (sx * 256.0 + i + 0.5) / world * 360 - 180;
                    double fx = (lon - x0) / p - 0.5, fy = (y0 - lat) / p - 0.5;
                    if (fx < 906 || fy < 906 || fx > 1029 || fy > 1029) continue;
                    int k = j * 256 + i;
                    if (k % 2 == 0) equal(-40f, sea[k], "3DEP keeps bathymetry at " + i + "," + j);
                    else near(-2, sea[k], 1e-3, "3DEP takes land down to sea level at " + i + "," + j);
                    low++;
                }
                check(low > 1000, "3DEP fixture tile reaches the low corner: " + low);

                // Outside the listed cells nothing is asked for; coarse tiles are left alone.
                int before = hits.values().stream().mapToInt(AtomicInteger::get).sum();
                float[] paris = new float[256 * 256];
                Arrays.fill(paris, 35f);
                layer.refine(zoom, (int) column.applyAsDouble(2.35), (int) row.applyAsDouble(48.85), paris);
                check(allEqual(paris, 35f), "3DEP leaves Paris");
                float[] coarse = new float[256 * 256];
                Arrays.fill(coarse, 35f);
                layer.refine(UsgsElevation.MIN_ZOOM - 1, 35, 46, coarse);
                check(allEqual(coarse, 35f), "3DEP leaves coarse tiles");
                equal(before, hits.values().stream().mapToInt(AtomicInteger::get).sum(), "3DEP asks for nothing outside its cells");
            }
            int transfers = hits.get(path).get();
            try (var offline = new UsgsElevation(base, cache, false, 16, 1, Duration.ofSeconds(5))) {
                int tx = (int) column.applyAsDouble(x0 + 500 * p), ty = (int) row.applyAsDouble(y0 - 300 * p);
                float[] values = new float[256 * 256];
                Arrays.fill(values, 75f);
                values[5] = 0f;
                offline.refine(zoom, tx, ty, values);
                check(Arrays.equals(online, values), "3DEP disk cache offline");
                equal(transfers, hits.get(path).get(), "3DEP cached blocks need no transfer");
            }
            try (var light = new UsgsElevation(base, cache, false, 16, 1, Duration.ofSeconds(5), UsgsElevation.Reading.GRADED)) {
                equal(UsgsElevation.GRADED_ID, light.id(), "3DEP read lighter pins its own id");
                equal(UsgsElevation.Reading.COARSE, UsgsElevation.Reading.of(UsgsElevation.COARSE_ID), "3DEP reading found by its pin");
                equal(UsgsElevation.DATASET_ID, new UsgsElevation(base, cache, false, 16, 1, Duration.ofSeconds(5)).id(), "3DEP keeps its first id");
            }
        } finally {
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    private static boolean allEqual(float[] values, float expected) {
        for (float v : values) if (v != expected) return false;
        return true;
    }

    /** Latitude of EPSG:3031 metres, by bisection on the layer's own projection. */
    private static double remaLatitude(double x, double y) {
        double rho = Math.hypot(x, y), lo = -90, hi = -50;
        for (int k = 0; k < 80; k++) {
            double mid = (lo + hi) / 2, r = Math.hypot(RemaElevation.project(mid, 0)[0], RemaElevation.project(mid, 0)[1]);
            if (r > rho) hi = mid; else lo = mid;
        }
        return (lo + hi) / 2;
    }

    /** TIFF LZW as libtiff writes it: a clear code first, widths changing one code early, a clear before the table fills. */
    private static byte[] lzw(byte[] data) {
        var out = new java.io.ByteArrayOutputStream();
        long[] bits = {0, 0};
        java.util.function.BiConsumer<Integer, Integer> emit = (code, width) -> {
            bits[0] = bits[0] << width | code;
            bits[1] += width;
            while (bits[1] >= 8) { out.write((int) (bits[0] >>> (bits[1] - 8)) & 0xFF); bits[1] -= 8; }
            bits[0] &= (1L << bits[1]) - 1;
        };
        java.util.function.IntUnaryOperator width = next -> { int seen = Math.max(258, next - 1); return seen < 511 ? 9 : seen < 1023 ? 10 : seen < 2047 ? 11 : 12; };
        Map<Long, Integer> table = new HashMap<>();
        int next = 258;
        emit.accept(256, 9);
        int prefix = data[0] & 0xFF;
        for (int i = 1; i < data.length; i++) {
            int k = data[i] & 0xFF;
            Integer known = table.get((long) prefix << 8 | k);
            if (known != null) { prefix = known; continue; }
            emit.accept(prefix, width.applyAsInt(next));
            table.put((long) prefix << 8 | k, next++);
            if (next >= 4094) {
                emit.accept(256, width.applyAsInt(next));
                table.clear();
                next = 258;
            }
            prefix = k;
        }
        emit.accept(prefix, width.applyAsInt(next));
        emit.accept(257, width.applyAsInt(next + 1));
        if (bits[1] > 0) out.write((int) (bits[0] << (8 - bits[1])) & 0xFF);
        return out.toByteArray();
    }

    /** A block of floats as REMA and 3DEP store it: byte planes from the most significant, differenced along each row, then LZW. */
    private static byte[] remaBlock(float[] image, int width, int height, int bx, int by, int block) {
        byte[] raw = new byte[block * block * 4];
        for (int j = 0; j < block; j++) {
            int start = j * block * 4;
            for (int i = 0; i < block; i++) {
                int x = bx * block + i, y = by * block + j;
                int bitsOf = Float.floatToIntBits(x < width && y < height ? image[y * width + x] : 0f);
                for (int b = 0; b < 4; b++) raw[start + b * block + i] = (byte) (bitsOf >>> (24 - 8 * b));
            }
            for (int i = start + block * 4 - 1; i > start; i--) raw[i] -= raw[i - 1];
        }
        return lzw(raw);
    }

    /** A little-endian BigTIFF like REMA's: a 2 m image at (x0, y0) and one overview, 512-pixel LZW blocks. */
    private static byte[] remaTiff(double x0, double y0, float[] full, int fullSize, float[] overview, int overviewSize) {
        return floatTiff(x0, y0, 2, 512, full, fullSize, overview, overviewSize);
    }

    /** A little-endian BigTIFF of square pixels at (x0, y0) and one overview, in LZW blocks with the floating-point predictor. */
    private static byte[] floatTiff(double x0, double y0, double pixel, int block, float[] full, int fullSize, float[] overview, int overviewSize) {
        float[][] images = {full, overview};
        int[] sizes = {fullSize, overviewSize};
        List<List<byte[]>> blocks = new ArrayList<>();
        for (int level = 0; level < 2; level++) {
            List<byte[]> list = new ArrayList<>();
            int across = Math.ceilDiv(sizes[level], block);
            for (int by = 0; by < across; by++) for (int bx = 0; bx < across; bx++) list.add(remaBlock(images[level], sizes[level], sizes[level], bx, by, block));
            blocks.add(list);
        }
        // Layout: header, then per level its directory and arrays, then the blocks.
        int[] tagsPerLevel = {14, 13};
        long at = 16;
        long[] directory = new long[2], offsetsAt = new long[2], countsAt = new long[2], scaleAt = new long[2], tieAt = new long[2];
        for (int level = 0; level < 2; level++) {
            directory[level] = at;
            at += 8 + tagsPerLevel[level] * 20L + 8;
            offsetsAt[level] = at; at += blocks.get(level).size() * 8L;
            countsAt[level] = at; at += blocks.get(level).size() * 8L;
            if (level == 0) { scaleAt[0] = at; at += 24; tieAt[0] = at; at += 48; }
        }
        long dataStart = at;
        ByteBuffer out = ByteBuffer.allocate((int) (dataStart + blocks.stream().flatMap(List::stream).mapToLong(b -> b.length).sum())).order(ByteOrder.LITTLE_ENDIAN);
        out.put((byte) 'I').put((byte) 'I').putShort((short) 43).putShort((short) 8).putShort((short) 0).putLong(directory[0]);
        long data = dataStart;
        for (int level = 0; level < 2; level++) {
            int size = sizes[level];
            List<long[]> tags = new ArrayList<>();
            if (level == 1) tags.add(new long[]{254, 4, 1, 1});
            tags.add(new long[]{256, 4, 1, size});
            tags.add(new long[]{257, 4, 1, size});
            tags.add(new long[]{258, 3, 1, 32});
            tags.add(new long[]{259, 3, 1, 5});
            tags.add(new long[]{262, 3, 1, 1});
            tags.add(new long[]{277, 3, 1, 1});
            tags.add(new long[]{317, 3, 1, 3});
            tags.add(new long[]{322, 3, 1, block});
            tags.add(new long[]{323, 3, 1, block});
            tags.add(new long[]{324, 16, blocks.get(level).size(), offsetsAt[level]});
            tags.add(new long[]{325, 16, blocks.get(level).size(), countsAt[level]});
            tags.add(new long[]{339, 3, 1, 3});
            if (level == 0) { tags.add(new long[]{33550, 12, 3, scaleAt[0]}); tags.add(new long[]{33922, 12, 6, tieAt[0]}); }
            out.position((int) directory[level]);
            out.putLong(tags.size());
            // One block's offset and size fit in their entries, as BigTIFF keeps eight bytes inline.
            int offsetsInline = -1, countsInline = -1;
            for (long[] tag : tags) {
                out.putShort((short) tag[0]).putShort((short) tag[1]).putLong(tag[2]);
                int valueAt = out.position();
                if (tag[1] == 3) out.putShort((short) tag[3]); else if (tag[1] == 4) out.putInt((int) tag[3]); else out.putLong(tag[3]);
                if (tag[2] == 1 && tag[0] == 324) offsetsInline = valueAt;
                if (tag[2] == 1 && tag[0] == 325) countsInline = valueAt;
                out.position(valueAt + 8);
            }
            out.putLong(level == 0 ? directory[1] : 0);
            List<byte[]> list = blocks.get(level);
            for (int b = 0; b < list.size(); b++) {
                out.putLong(offsetsInline >= 0 ? offsetsInline : (int) (offsetsAt[level] + b * 8L), data);
                out.putLong(countsInline >= 0 ? countsInline : (int) (countsAt[level] + b * 8L), list.get(b).length);
                out.position((int) data);
                out.put(list.get(b));
                data += list.get(b).length;
            }
            if (level == 0) {
                out.position((int) scaleAt[0]);
                out.putDouble(pixel).putDouble(pixel).putDouble(0);
                out.putDouble(0).putDouble(0).putDouble(0).putDouble(x0).putDouble(y0).putDouble(0);
            }
        }
        return out.array();
    }

    /** REMA's real heights at Antarctic summits. */
    private static void liveRema() throws Exception {
        try (var rema = new RemaSource(RemaSource.AWS_REMA, null, true, 32, 4, Duration.ofSeconds(30));
             var tiles = new TerrariumTileSource(TerrariumTileSource.AWS_TERRAIN_TILES, null, true, 64, 4, Duration.ofSeconds(30), List.of(new RemaElevation(rema)))) {
            // Mount Vinson (4,892 m) in its zoom-13 tile; the composite alone reads under 2,900 m. Erebus
            // (3,794 m surveyed) is an active volcano, so REMA's surface there is only checked roughly.
            double[][] summits = {{-78.5254, -85.6171, 4892, 15}, {-77.53, 167.153, 3794, 80}};
            for (double[] summit : summits) {
                int zoom = 13, n = 1 << zoom;
                int tx = (int) ((summit[1] + 180) / 360 * n);
                int ty = (int) Math.floor((1 - Math.log(Math.tan(Math.PI / 4 + Math.toRadians(summit[0]) / 2)) / Math.PI) / 2 * n);
                float highest = -1e9f;
                for (float v : tiles.tile(zoom, tx, ty)) highest = Math.max(highest, v);
                near(summit[2], highest, summit[3], "REMA summit at " + summit[0] + ", " + summit[1]);
            }
        }
    }

    /** 3DEP above Niagara Falls, where the composite drops the river to Lake Ontario's level. */
    private static void liveUsgs() throws Exception {
        try (var usgs = new UsgsElevation(UsgsElevation.AWS_3DEP, null, true, 32, 4, Duration.ofSeconds(30));
             var plain = new TerrariumTileSource(TerrariumTileSource.AWS_TERRAIN_TILES, null, true, 64, 4, Duration.ofSeconds(30));
             var tiles = new TerrariumTileSource(TerrariumTileSource.AWS_TERRAIN_TILES, null, true, 64, 4, Duration.ofSeconds(30), List.of(usgs))) {
            int zoom = 15, n = 1 << zoom;
            double lat = 43.0725, lon = -79.0650;
            int tx = (int) ((lon + 180) / 360 * n);
            double fy = (1 - Math.log(Math.tan(Math.PI / 4 + Math.toRadians(lat) / 2)) / Math.PI) / 2 * n;
            int ty = (int) fy, i = (int) (((lon + 180) / 360 * n - tx) * 256), j = (int) ((fy - ty) * 256);
            float before = plain.tile(zoom, tx, ty)[j * 256 + i], after = tiles.tile(zoom, tx, ty)[j * 256 + i];
            check(before < 90, "Composite has the upper Niagara at Lake Ontario's level: " + before);
            near(168, after, 6, "3DEP upper Niagara river");
        }
    }

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

    /** Japan, France and Canada: coverage lists, the sources' encodings, the blend over the composite and the tile reads. */
    private static void nationalLayers() throws Exception {
        // Coverage lists, read from the shipped resources.
        TileCoverage gsi = TileCoverage.load("gsi-dem-v1.txt"), ign = TileCoverage.load("ign-rgealti-v1.txt");
        int[] fuji = tileOf(35.3606, 138.7274, 15), paris = tileOf(48.8566, 2.3522, 14);
        check(gsi.covers(15, fuji[0], fuji[1]), "GSI covers Mount Fuji");
        check(gsi.covers(10, fuji[0] >> 5, fuji[1] >> 5), "GSI covers Fuji's zoom-10 tile");
        check(gsi.covers(5, fuji[0] >> 10, fuji[1] >> 10), "GSI covers Fuji's zoom-5 tile");
        int[] pacific = tileOf(30, 160, 15), seoul = tileOf(37.5665, 126.978, 13);
        check(!gsi.covers(15, pacific[0], pacific[1]), "GSI leaves the open Pacific");
        check(!gsi.covers(13, seoul[0], seoul[1]), "GSI leaves Seoul");
        check(ign.covers(14, paris[0], paris[1]), "IGN covers Paris");
        int[] london = tileOf(51.5074, -0.1278, 14), reunion = tileOf(-21.115, 55.536, 13);
        check(!ign.covers(14, london[0], london[1]), "IGN leaves London");
        check(ign.covers(13, reunion[0], reunion[1]), "IGN covers Reunion");

        // GSI's PNG code: hundredths of a metre in 24 bits, two's complement, 2^23 no data.
        near(0, GsiElevation.metres(0), 0, "GSI zero");
        near(3776.24, GsiElevation.metres(377624), 1e-3, "GSI height");
        near(-0.01, GsiElevation.metres((1 << 24) - 1), 1e-6, "GSI below sea level");
        check(Float.isNaN(GsiElevation.metres(1 << 23)), "GSI no data");
        // IGN's BIL: the service's own latitude and longitude grid, sampled at our pixel centres. A plane
        // comes back exactly; pixels with no data around them are no data.
        IgnElevation.Block block = IgnElevation.block(14, 8480, 5972);
        equal(21, block.level(), "IGN reads pixels as wide as the tile's");
        equal(IgnElevation.FINEST, IgnElevation.block(16, 0, 0).level(), "IGN reads no finer than its finest grid");
        equal(8, IgnElevation.block(1, 1, 0).level(), "IGN reads a coarse grid for a coarse tile");
        check(block.columns() > 256 && block.columns() < 262 && block.rows() > 183 && block.rows() < 192, "IGN block " + block);
        double r = block.degrees();
        ByteBuffer bil = ByteBuffer.allocate(block.columns() * block.rows() * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int j = 0; j < block.rows(); j++) for (int i = 0; i < block.columns(); i++) {
            double lat = (block.south() + block.rows() - j - 0.5) * r, lon = (block.west() + i + 0.5) * r;
            bil.putFloat(i < 20 ? -99999f : (float) (1000 * (lat - 43.7) + 500 * (lon - 6.3) + 1000));
        }
        float[] decoded = IgnElevation.decode(block, 14, 8480, 5972, bil.array());
        double world = 256.0 * (1 << 14);
        double lat = MercatorTileLayer.latitude(world, 5972 * 256 + 100.5), lon = (8480 * 256 + 200.5) / world * 360 - 180;
        near(1000 * (lat - 43.7) + 500 * (lon - 6.3) + 1000, decoded[100 * 256 + 200], 1e-3, "IGN samples the grid bilinearly");
        check(Float.isNaN(decoded[0]), "IGN no data");
        fail(() -> IgnElevation.decode(block, 14, 8480, 5972, "<ServiceException/>".getBytes()), java.io.IOException.class, "IGN error answer is not a tile");

        // The blend: the source's west half has data. Its interior takes the source, its edge eases in,
        // past it the tile stays; open sea stays sea and bathymetry stays.
        float[] values = new float[256 * 256], source = new float[256 * 256];
        Arrays.fill(values, 100f);
        for (int j = 0; j < 256; j++) for (int i = 0; i < 256; i++) source[j * 256 + i] = i < 128 ? 140f : Float.NaN;
        values[40 * 256 + 40] = 0f; source[40 * 256 + 40] = 3f;        // sea where the source reads a few metres
        values[41 * 256 + 41] = -30f; source[41 * 256 + 41] = -1f;     // bathymetry under a source at sea level
        values[42 * 256 + 42] = 0f; source[42 * 256 + 42] = 900f;      // a void of 0 high in the source: filled
        MercatorTileLayer.blend(values, source);
        near(140, values[10 * 256 + 60], 1e-4, "source interior taken");
        near(100, values[10 * 256 + 200], 0, "tile kept past the source");
        check(values[10 * 256 + 126] > 100 && values[10 * 256 + 126] < 140, "source eases in at its edge: " + values[10 * 256 + 126]);
        equal(0f, values[40 * 256 + 40], "open sea stays sea");
        equal(-30f, values[41 * 256 + 41], "bathymetry stays");
        near(900, values[42 * 256 + 42], 1e-3, "inland void filled");

        // GSI read from a local server: zoom 15 takes the lidar, its gaps the 10 m model; a missing lidar tile is
        // remembered, so a second read asks the server nothing.
        int[] at = fuji;
        Map<String, byte[]> tiles = new HashMap<>();
        float[] lidar = new float[256 * 256], model = new float[256 * 256];
        for (int k = 0; k < lidar.length; k++) { lidar[k] = k / 256 < 100 ? 3000f : Float.NaN; model[k] = 2900f; }
        tiles.put("/gsi/dem5a_png/15/" + at[0] + "/" + at[1] + ".png", gsiPng(lidar));
        tiles.put("/gsi/dem_png/14/" + (at[0] >> 1) + "/" + (at[1] >> 1) + ".png", gsiPng(model));
        AtomicInteger asked = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", exchange -> {
            asked.incrementAndGet();
            byte[] body = tiles.get(exchange.getRequestURI().getPath());
            if (body == null) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        Path cache = Files.createTempDirectory("gsi-test");
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/gsi/");
        try {
            try (var layer = new GsiElevation(base, cache, true, 8, 2, Duration.ofSeconds(5))) {
                float[] tile = new float[256 * 256];
                Arrays.fill(tile, 2500f);
                layer.refine(15, at[0], at[1], tile);
                near(3000, tile[20 * 256 + 20], 1e-2, "GSI lidar taken");
                // The fixture's z14 tile is a quarter of it: the model fills the lidar's gap.
                near(2900, tile[200 * 256 + 20], 1e-2, "GSI model fills the lidar's gap");
                // A neighbouring tile with no lidar on the server reads the model alone, once.
                float[] next = new float[256 * 256];
                Arrays.fill(next, 2500f);
                int nx = at[0] ^ 1;
                layer.refine(15, nx, at[1], next);
                near(2900, next[128 * 256 + 128], 1e-2, "GSI tile without lidar takes the model");
                check(Files.exists(cache.resolve("dem5a_png/15/" + nx + "/" + at[1] + ".none")), "GSI missing lidar remembered on disk");
                // Coarse tiles keep the composite.
                float[] far = new float[256 * 256];
                Arrays.fill(far, 2500f);
                layer.refine(11, at[0] >> 4, at[1] >> 4, far);
                equal(2500f, far[0], "GSI leaves coarse tiles to the composite");
            }
            int before = asked.get();
            try (var offline = new GsiElevation(base, cache, false, 8, 2, Duration.ofSeconds(5))) {
                float[] next = new float[256 * 256];
                Arrays.fill(next, 2500f);
                offline.refine(15, at[0] ^ 1, at[1], next);
                near(2900, next[128 * 256 + 128], 1e-2, "GSI reads its disk cache offline");
            }
            equal(before, asked.get(), "GSI cache answers without the server");
        } finally {
            server.stop(0);
        }

        // Canada Atlas Lambert: the origin, and the edge of an HRDEM square as NRCan's catalog gives it in degrees.
        double[] xy = new double[2];
        HrdemElevation.project(49, -95, xy);
        near(0, xy[0], 1e-6, "EPSG:3979 origin x");
        near(0, xy[1], 1e-6, "EPSG:3979 origin y");
        HrdemElevation.project(42.665636, -76.986506, xy);
        near(1_500_000, xy[0], 5, "HRDEM square 8_2 south-east corner x");
        near(-500_000, xy[1], 5, "HRDEM square 8_2 south-east corner y");
    }

    private static int[] tileOf(double lat, double lon, int zoom) {
        int n = 1 << zoom;
        return new int[]{(int) ((lon + 180) / 360 * n), (int) ((1 - Math.log(Math.tan(Math.toRadians(lat)) + 1 / Math.cos(Math.toRadians(lat))) / Math.PI) / 2 * n)};
    }

    /** A GSI elevation PNG of metres, NaN as no data. */
    private static byte[] gsiPng(float[] metres) throws java.io.IOException {
        var image = new java.awt.image.BufferedImage(256, 256, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int k = 0; k < metres.length; k++) {
            int code = Float.isNaN(metres[k]) ? 1 << 23 : (int) Math.round(metres[k] * 100) & 0xFFFFFF;
            image.setRGB(k % 256, k / 256, code);
        }
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
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
