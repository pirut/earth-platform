package io.earthplatform.geography.globe;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** Sampling uses varied traced bytes, so dry synthetic source tiles cannot hide a changed kernel. */
class RiverMapSamplingTest {
    private static final int TILE = 256;

    @Test
    void keepsEveryBitOfInteriorInterpolation() throws Exception {
        try (Fixture fixture = new Fixture(17)) {
            Random random = new Random(0x518E83L);
            for (int i = 0; i < 20_000; i++) {
                double x = 800_000 + random.nextDouble() * 512;
                double y = 120_000 + random.nextDouble() * 512;
                fixture.check(point(x, y));
            }
        }
    }

    @Test
    void keepsEveryBitAtTileSeamsDataEdgesAndTheAntimeridian() throws Exception {
        double[] columns = {0, TILE, 2 * TILE, SurfaceWaterSource.ACROSS - TILE, SurfaceWaterSource.ACROSS};
        double[] rows = {0, TILE, 2 * TILE, SurfaceWaterSource.DOWN - 128, SurfaceWaterSource.DOWN};
        double[] offsets = {-2, -1, -0.5, -0.001, 0, 0.001, 0.5, 1, 2};
        try (Fixture fixture = new Fixture(31)) {
            for (double x : columns) for (double y : rows)
                for (double dx : offsets) for (double dy : offsets) fixture.check(point(x + dx, y + dy));
            // The exact antimeridian has identical fractions on both sides of the wrap.
            fixture.prepare(new Point(20, -180));
            fixture.prepare(new Point(20, 180));
            assertBits(fixture.map.strength(20, -180), fixture.map.strength(20, 180), "antimeridian");
        }
    }

    @Test
    void keepsEveryBitForNegativeAndExtremeCoordinates() throws Exception {
        double[] columns = {-2.0 * SurfaceWaterSource.ACROSS - 257.25, -257.5, -1.25, 0,
                SurfaceWaterSource.ACROSS + 0.25, 2.0 * SurfaceWaterSource.ACROSS + 255.5,
                Integer.MIN_VALUE - 2.0, Integer.MIN_VALUE, Integer.MIN_VALUE + 2.0,
                Integer.MAX_VALUE - 2.0, Integer.MAX_VALUE, Integer.MAX_VALUE + 2.0};
        double[] rows = {-2, -0.5, 0, 0.5, 1, 255.25, SurfaceWaterSource.DOWN - 1.5,
                SurfaceWaterSource.DOWN, Integer.MIN_VALUE, Integer.MAX_VALUE};
        try (Fixture fixture = new Fixture(43)) {
            for (double x : columns) for (double y : rows) fixture.check(point(x, y));
            for (double latitude : new double[]{-90, -60, Math.nextUp(-60.0), Math.nextDown(-60.0),
                    80, Math.nextUp(80.0), Math.nextDown(80.0), 90, -1e30, 1e30, Double.NaN})
                for (double longitude : new double[]{-540, -180, 0, 180, 540, -1e30, 1e30, Double.NaN,
                        Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY})
                    fixture.check(new Point(latitude, longitude));
        }
    }

    @Test
    void sharesNoSamplingScratchAcrossThreadsOrRiverMaps() throws Exception {
        try (Fixture first = new Fixture(61); Fixture second = new Fixture(73)) {
            List<Point> points = new ArrayList<>();
            Random random = new Random(0xC0417L);
            for (int i = 0; i < 512; i++) {
                Point point = point(900_000 + random.nextDouble() * 512, 200_000 + random.nextDouble() * 512);
                points.add(point);
                first.prepare(point);
                second.prepare(point);
            }
            long[][] expected = new long[2][points.size()];
            Fixture[] fixtures = {first, second};
            for (int m = 0; m < fixtures.length; m++) for (int i = 0; i < points.size(); i++) {
                Point point = points.get(i);
                expected[m][i] = Double.doubleToRawLongBits(fixtures[m].map.strengthReference(point.latitude, point.longitude));
            }
            List<Callable<Void>> jobs = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int offset = t;
                jobs.add(() -> {
                    for (int r = 0; r < 8; r++) for (int i = 0; i < points.size(); i++) {
                        int index = (i + offset * 67) % points.size(), m = (i + r + offset) & 1;
                        Point point = points.get(index);
                        assertEquals(expected[m][index], Double.doubleToRawLongBits(fixtures[m].map.strength(point.latitude, point.longitude)));
                    }
                    return null;
                });
            }
            try (var executor = Executors.newFixedThreadPool(8)) {
                for (var result : executor.invokeAll(jobs)) result.get();
            }
        }
    }

    private record Point(double latitude, double longitude) {}

    /** x/y are pixel-centre coordinates, before strength's half-pixel adjustment. */
    private static Point point(double x, double y) {
        return new Point(SurfaceWaterSource.NORTH - (y + 0.5) / SurfaceWaterSource.PIXELS_PER_DEGREE,
                (x + 0.5) / SurfaceWaterSource.PIXELS_PER_DEGREE - 180);
    }

    private static void assertBits(double expected, double actual, String where) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual), where);
    }

    /**
     * Inject immutable traced tiles rather than exposing a production cache-writing API. The
     * source is network-disabled: an unprepared tile fails instead of silently testing dry land.
     */
    private static final class Fixture implements AutoCloseable {
        private final SurfaceWaterSource source;
        private final RiverMap map;
        private final Map<Long, Object> memory;
        private final Constructor<?> traced;
        private final int seed;

        @SuppressWarnings("unchecked")
        private Fixture(int seed) throws Exception {
            this.seed = seed;
            source = new SurfaceWaterSource(URI.create("http://127.0.0.1/"), null, false, 4, 1, Duration.ofSeconds(1));
            map = new RiverMap(source, 256);
            Field field = RiverMap.class.getDeclaredField("memory");
            field.setAccessible(true);
            memory = (Map<Long, Object>) field.get(map);
            traced = Class.forName(RiverMap.class.getName() + "$Traced").getDeclaredConstructor(long.class, byte[].class, byte[].class);
            traced.setAccessible(true);
        }

        private void check(Point point) throws Exception {
            prepare(point);
            assertBits(map.strengthReference(point.latitude, point.longitude), map.strength(point.latitude, point.longitude), point.toString());
        }

        private void prepare(Point point) throws Exception {
            int ix = (int) Math.floor((point.longitude + 180) * SurfaceWaterSource.PIXELS_PER_DEGREE - 0.5);
            int iy = (int) Math.floor((SurfaceWaterSource.NORTH - point.latitude) * SurfaceWaterSource.PIXELS_PER_DEGREE - 0.5);
            for (int j = 0; j < 4; j++) for (int i = 0; i < 4; i++) {
                int y = iy - 1 + j;
                if (y < 0 || y >= SurfaceWaterSource.DOWN) continue;
                int x = Math.floorMod(ix - 1 + i, SurfaceWaterSource.ACROSS);
                long key = (long) (y / TILE) << 32 | x / TILE;
                if (memory.containsKey(key)) continue;
                byte[] values = new byte[TILE * TILE];
                int x0 = (x / TILE) * TILE, y0 = (y / TILE) * TILE;
                for (int py = 0; py < TILE; py++) for (int px = 0; px < TILE; px++) {
                    int hash = (x0 + px) * 0x45D9F3B ^ (y0 + py) * 0x119DE1F3 ^ seed;
                    values[py * TILE + px] = (byte) (hash ^ hash >>> 16);
                }
                memory.put(key, traced.newInstance(key, values, new byte[256]));
            }
        }

        @Override
        public void close() {
            source.close();
        }
    }
}
