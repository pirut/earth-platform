package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CubeRangesTest {
    @Test
    void takesFirstFitAndJoinsWhatComesBack() {
        CubeRanges ranges = new CubeRanges(100);
        int a = ranges.take(30), b = ranges.take(30), c = ranges.take(40);
        assertEquals(0, a);
        assertEquals(30, b);
        assertEquals(60, c);
        assertEquals(-1, ranges.take(1));
        ranges.give(b, 30);
        assertEquals(-1, ranges.take(31));
        ranges.give(a, 30);
        assertEquals(1, ranges.runs());
        assertEquals(0, ranges.take(60));
        ranges.give(0, 60);
        ranges.give(c, 40);
        assertEquals(1, ranges.runs());
        assertEquals(0, ranges.used());
        assertEquals(0, ranges.take(100));
    }

    @Test
    void refusesARunHandedBackTwice() {
        CubeRanges ranges = new CubeRanges(64);
        int a = ranges.take(16);
        ranges.give(a, 16);
        assertThrows(IllegalStateException.class, () -> ranges.give(a, 16));
    }

    @Test
    void neverGivesOutTheSameSlotTwice() {
        Random random = new Random(7);
        CubeRanges ranges = new CubeRanges(4096);
        boolean[] taken = new boolean[4096];
        List<int[]> held = new ArrayList<>();
        for (int step = 0; step < 20_000; step++) {
            if (held.isEmpty() || random.nextInt(3) != 0) {
                int count = 4 * (1 + random.nextInt(60)), start = ranges.take(count);
                if (start < 0) continue;
                for (int i = start; i < start + count; i++) { assertTrue(!taken[i]); taken[i] = true; }
                held.add(new int[] {start, count});
            } else {
                int[] run = held.remove(random.nextInt(held.size()));
                for (int i = run[0]; i < run[0] + run[1]; i++) taken[i] = false;
                ranges.give(run[0], run[1]);
            }
        }
        int used = 0;
        for (boolean slot : taken) if (slot) used++;
        assertEquals(used, ranges.used());
        for (int[] run : held) ranges.give(run[0], run[1]);
        assertEquals(1, ranges.runs());
    }
}
