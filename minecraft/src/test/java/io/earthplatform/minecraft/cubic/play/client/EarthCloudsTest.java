package io.earthplatform.minecraft.cubic.play.client;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EarthCloudsTest {
    private static final int GRID = EarthClouds.GRID;

    /** The cells as before columns off the grid were skipped: every column worked out in full. */
    private static void raiseInFull(int[] high, double left, double near, double step, int x0, int z0, int width, int top) {
        int i0 = Math.max(0, (int) Math.floor((x0 - left) / step)), i1 = Math.min(GRID - 1, (int) Math.floor((x0 + width - left) / step));
        int j0 = Math.max(0, (int) Math.floor((z0 - near) / step)), j1 = Math.min(GRID - 1, (int) Math.floor((z0 + width - near) / step));
        for (int i = i0; i <= i1; i++)
            for (int j = j0; j <= j1; j++)
                if (top > high[i * GRID + j]) high[i * GRID + j] = top;
    }

    /** Skipping the columns off the grid leaves every cell as working them all out did, edges touching it included. */
    @Test void skippingColumnsOffTheGridChangesNoCell() {
        Random random = new Random(3307);
        for (int round = 0; round < 300; round++) {
            double camX = random.nextInt(4_000_000) - 2_000_000 + random.nextDouble(), camZ = random.nextInt(4_000_000) - 2_000_000 + random.nextDouble();
            double radius = Math.max(8_000, 6 * random.nextDouble() * 9_000), step = 2 * radius / GRID;
            // Now and then a corner on a whole block, so columns can end right at the grid's edge.
            if (random.nextInt(4) == 0) { camX = Math.floor(camX); camZ = Math.floor(camZ); radius = 8_192; step = 2 * radius / GRID; }
            double left = camX - radius, near = camZ - radius;
            int[] high = new int[GRID * GRID], full = new int[GRID * GRID];
            Arrays.fill(high, Integer.MIN_VALUE);
            Arrays.fill(full, Integer.MIN_VALUE);
            for (int column = 0; column < 2_000; column++) {
                int level = 1 + random.nextInt(8), width = 16 << level;
                int reach = (int) (radius * 3) / width + 2;
                int x0 = ((int) Math.floor(camX / width) + random.nextInt(2 * reach) - reach) * width;
                int z0 = ((int) Math.floor(camZ / width) + random.nextInt(2 * reach) - reach) * width;
                int top = random.nextInt(9_000) - 500;
                EarthClouds.Cells.raise(high, left, near, step, x0, z0, width, top);
                raiseInFull(full, left, near, step, x0, z0, width, top);
            }
            assertArrayEquals(full, high, "round " + round);
        }
    }
}
