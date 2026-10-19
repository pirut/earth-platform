package io.earthplatform.minecraft.cubic.client;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CloudTerrainGridTest {
    private record Column(int x, int z, int width, int top) {}

    private static CloudTerrainGrid.Source source(List<Column> columns, AtomicInteger visits) {
        return visitor -> { visits.incrementAndGet(); for (Column c : columns) visitor.accept(c.x, c.z, c.width, c.top); };
    }

    /** The pre-cache cloud clearance algorithm, including its exact edge and float rules. */
    private static float[] original(List<Column> columns, int grid, double left, double near, double step, double deck) {
        int[] high = new int[grid * grid];
        Arrays.fill(high, Integer.MIN_VALUE);
        for (Column c : columns) {
            int i0 = Math.max(0, (int) Math.floor((c.x - left) / step)), i1 = Math.min(grid - 1, (int) Math.floor((c.x + c.width - left) / step));
            int j0 = Math.max(0, (int) Math.floor((c.z - near) / step)), j1 = Math.min(grid - 1, (int) Math.floor((c.z + c.width - near) / step));
            for (int i = i0; i <= i1; i++) for (int j = j0; j <= j1; j++) high[i * grid + j] = Math.max(high[i * grid + j], c.top);
        }
        float[] clear = new float[(grid + 1) * (grid + 1)];
        Arrays.fill(clear, 1);
        for (int i = 0; i <= grid; i++) for (int j = 0; j <= grid; j++) {
            int top = Integer.MIN_VALUE;
            for (int ci = i - 1; ci <= i; ci++) for (int cj = j - 1; cj <= j; cj++)
                if (ci >= 0 && cj >= 0 && ci < grid && cj < grid) top = Math.max(top, high[ci * grid + cj]);
            if (top != Integer.MIN_VALUE) clear[i * (grid + 1) + j] = opacity(deck, top);
        }
        return clear;
    }

    private static float opacity(double deck, int top) {
        double t = Math.max(0, Math.min(1, (deck - top - 150) / 450));
        return (float) (t * t * (3 - 2 * t));
    }

    private static void assertClearance(List<Column> columns, int grid, double left, double near, double step, int[] tops, double deck) {
        float[] expected = original(columns, grid, left, near, step, deck);
        for (int i = 0; i < tops.length; i++) {
            float actual = tops[i] == Integer.MIN_VALUE ? 1 : opacity(deck, tops[i]);
            assertEquals(Float.floatToRawIntBits(expected[i]), Float.floatToRawIntBits(actual), "cloud corner " + i);
        }
    }

    @Test void exactClearanceAcrossEdgesNegativeCoordinatesOverlapsAndChangingDecks() {
        Random random = new Random(58);
        List<Column> columns = new ArrayList<>(List.of(new Column(-16000, -16000, 1000, 2500),
                new Column(0, 0, 500, 4000), new Column(-500, -500, 500, -900),
                new Column(Integer.MAX_VALUE - 2, 0, 16, 5000)));
        for (int i = 0; i < 400; i++) columns.add(new Column(random.nextInt(64000) - 32000, random.nextInt(64000) - 32000,
                16 << random.nextInt(10), random.nextInt(18000) - 11000));
        CloudTerrainGrid cache = new CloudTerrainGrid();
        AtomicInteger visits = new AtomicInteger();
        for (int grid : new int[] {1, 7, 32}) for (double left : new double[] {-16000, Math.nextUp(-16000.0), -0.0, 0, 24000}) {
            double near = left - 0.25, step = 32000.0 / grid;
            int[] tops = cache.corners(1, grid, left, near, step, source(columns, visits));
            for (double deck : new double[] {-10000, 0, 2500, 2650.25, 8500, 16000})
                assertClearance(columns, grid, left, near, step, tops, deck);
            assertSame(tops, cache.corners(1, grid, left, near, step, source(columns, visits)));
        }
        assertEquals(15, visits.get());
    }

    @Test void arrivalEditEvictionRevisionWrapAndExplicitInvalidationRebuild() {
        CloudTerrainGrid cache = new CloudTerrainGrid();
        AtomicInteger visits = new AtomicInteger();
        List<Column> columns = new ArrayList<>(List.of(new Column(0, 0, 100, 1000)));
        long revision = Integer.MAX_VALUE;
        for (int change = 0; change < 5; change++) {
            if (change == 1) columns.add(new Column(0, 0, 100, 5000));
            if (change == 2) columns.set(1, new Column(0, 0, 100, 3000));
            if (change == 3) columns.remove(1);
            if (change == 4) columns.clear();
            int[] tops = cache.corners(revision, 4, -100, -100, 100, source(columns, visits));
            assertClearance(columns, 4, -100, -100, 100, tops, 2500);
            assertSame(tops, cache.corners(revision, 4, -100, -100, 100, source(columns, visits)));
            revision = (int) (revision + 1);
        }
        assertEquals(5, visits.get());
        cache.invalidate();
        cache.corners(revision - 1, 4, -100, -100, 100, source(columns, visits));
        assertEquals(6, visits.get());
    }

    @Test void everyGridInputUsesExactBitsAndNonfiniteInputsNeverReuse() {
        CloudTerrainGrid cache = new CloudTerrainGrid();
        AtomicInteger visits = new AtomicInteger();
        var source = source(List.of(new Column(0, 0, 32, 2000)), visits);
        double[][] keys = {{0, 0, 500}, {Math.nextUp(0.0), 0, 500}, {0, Math.nextUp(0.0), 500},
                {0, 0, Math.nextUp(500.0)}, {-0.0, 0, 500}, {0, -0.0, 500}};
        for (double[] key : keys) {
            cache.corners(1, 32, key[0], key[1], key[2], source);
            cache.corners(1, 32, key[0], key[1], key[2], source);
        }
        assertEquals(keys.length, visits.get());
        for (int i = 0; i < 2; i++) cache.corners(1, 32, Double.NaN, 0, 500, source);
        assertEquals(keys.length + 2, visits.get());
    }

    @Test void separateWorldRenderersCannotReuseEachOthersTerrain() {
        AtomicInteger visits = new AtomicInteger();
        int[] first = new CloudTerrainGrid().corners(1, 4, 0, 0, 100, source(List.of(new Column(0, 0, 100, 500)), visits));
        int[] second = new CloudTerrainGrid().corners(1, 4, 0, 0, 100, source(List.of(new Column(0, 0, 100, 5000)), visits));
        assertNotEquals(first[0], second[0]);
        assertEquals(2, visits.get());
    }
}
