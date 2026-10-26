package io.earthplatform.minecraft.cubic.client;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FarOrderTest {
    @Test void preservesExactDistanceAndStableTies() {
        Random random = new Random(741);
        double[] distances = new double[46_000];
        for (int i = 0; i < distances.length; i++) {
            double base = random.nextInt(100) * 1e12;
            distances[i] = i % 3 == 0 ? base : Math.nextUp(base);
        }
        check(distances);
        check(new double[] {4, 1, 4, 0, Math.nextUp(1.0), 1, Double.POSITIVE_INFINITY});
    }

    @Test void reuseDiscardsPreviousEntries() {
        FarOrder order = new FarOrder();
        order.clear(100);
        for (int i = 0; i < 100; i++) order.add(100 - i);
        order.sort();
        order.clear(3);
        order.add(2); order.add(1); order.add(2);
        order.sort();
        assertEquals(1, order.index(0));
        assertEquals(0, order.index(1));
        assertEquals(2, order.index(2));
        order.clear(0);
        assertDoesNotThrow(order::sort);
    }

    /**
     * Frame after frame, as a camera moves (a little, now and then a lot), points come into view and
     * leave, distances tie and epochs change, sorting from the last order gives what a whole sort gives.
     */
    @Test void sortingFromTheLastOrderMatchesAWholeSort() {
        Random random = new Random(1234);
        int count = 4000;
        int[] px = new int[count], pz = new int[count];
        boolean[] in = new boolean[count];
        for (int e = 0; e < count; e++) {
            px[e] = random.nextInt(200) - 100;
            pz[e] = random.nextInt(200) - 100;
            in[e] = random.nextBoolean();
        }
        FarOrder order = new FarOrder(), whole = new FarOrder();
        double cx = 0, cz = 0;
        int epoch = 0;
        for (int frame = 0; frame < 400; frame++) {
            if (random.nextInt(50) == 0) epoch++;
            double step = random.nextInt(20) == 0 ? 60 : 0.7;
            cx += (random.nextDouble() - 0.5) * step;
            cz += (random.nextDouble() - 0.5) * step;
            for (int k = random.nextInt(count / 8); k > 0; k--) {
                int e = random.nextInt(count);
                in[e] = !in[e];
            }
            order.clear(count);
            whole.clear(count);
            int added = 0;
            for (int e = 0; e < count; e++) {
                if (!in[e]) continue;
                // From a whole-number spot now and then, so that many distances tie.
                double x = px[e] - (frame % 7 == 0 ? Math.round(cx) : cx), z = pz[e] - (frame % 7 == 0 ? Math.round(cz) : cz);
                order.add(x * x + z * z, e);
                whole.add(x * x + z * z);
                added++;
            }
            order.sortFrom(epoch, count);
            whole.sort();
            for (int i = 0; i < added; i++) assertEquals(whole.index(i), order.index(i), "frame " + frame + " at " + i);
        }
    }

    @Test void firstMatchesAStableSortOfTheWholeList() {
        Random random = new Random(42);
        long[] values = new long[46_000];
        for (int i = 0; i < values.length; i++) values[i] = (random.nextBoolean() ? 1L << 40 : 0) + random.nextInt(500);
        Integer[] sorted = new Integer[values.length];
        for (int i = 0; i < sorted.length; i++) sorted[i] = i;
        Arrays.sort(sorted, Comparator.comparingLong(i -> values[i]));
        for (int wanted : new int[] {0, 1, 7, 128, values.length, values.length + 5}) {
            int[] first = FarOrder.first(values, wanted);
            assertEquals(Math.min(wanted, values.length), first.length);
            for (int i = 0; i < first.length; i++) assertEquals(sorted[i], first[i], "wanted " + wanted + " at " + i);
        }
        assertEquals(0, FarOrder.first(new long[0], 5).length);
    }

    private static void check(double[] distances) {
        Integer[] expected = new Integer[distances.length];
        FarOrder order = new FarOrder();
        order.clear(distances.length);
        for (int i = 0; i < distances.length; i++) {
            expected[i] = i;
            order.add(distances[i]);
        }
        Arrays.sort(expected, Comparator.comparingDouble(i -> distances[i]));
        order.sort();
        for (int i = 0; i < distances.length; i++) assertEquals(expected[i].intValue(), order.index(i), "position " + i);
    }
}
