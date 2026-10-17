package io.earthplatform.minecraft.cubic.far;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.Test;

class FarSharpnessTest {
    @Test void sharpnessScalesTheHoleButNeverTheReach() {
        float reach = Far.reach();
        try {
            Far.sharpness(1.0);
            assertEquals(Far.DETAIL, Far.hole());
            Far.sharpness(0.7);
            assertTrue(Far.hole() < Far.DETAIL);
            Far.sharpness(1.6);
            assertTrue(Far.hole() > Far.DETAIL);
            assertEquals(reach, Far.reach());
            // However sharp the normal choices, the coarsest level keeps a ring of its own.
            Far.sharpness(Far.EXTREME_SCALE);
            assertTrue(Far.hole() < Far.OUTER);
            // The extreme ones may cover it, and requests stay inside the server's slack.
            Far.sharpness(100);
            assertEquals(Far.MAX_HOLE, Far.hole());
            assertTrue(2 * Far.hole() + 2 <= Far.SLACK);
            assertTrue(2 * (2 * Far.MAX_DETAIL + 1) + 2 <= Far.SLACK);
            Far.sharpness(0);
            assertEquals(4, Far.hole());
        } finally {
            Far.sharpness(1.0);
        }
    }

    @Test void finerLevelsFillTheirParentsMiddle() {
        try {
            for (double scale : new double[] {0.7, 1.0, 1.3, 1.6, 2.0}) {
                Far.sharpness(scale);
                int[] count = new int[Far.MAX_LEVEL + 1];
                Far.around(12_345, -6_789, key -> count[Far.level(key)]++);
                int side = 4 * Far.hole() + 2;
                for (int level = Far.MIN_LEVEL; level < Far.MAX_LEVEL; level++) assertEquals(side * side, count[level]);
                assertEquals((2 * Far.OUTER + 1) * (2 * Far.OUTER + 1), count[Far.MAX_LEVEL]);
            }
        } finally {
            Far.sharpness(1.0);
        }
    }

    /**
     * At every choice, extreme ones included, the drawn columns (the ones no finer level replaces)
     * cover the coarsest square exactly once, and each replaced column's four children are asked for.
     */
    @Test void drawnColumnsTileTheCoarsestSquare() {
        int bx = 12_345, bz = -6_789;
        try {
            for (double scale : new double[] {1.0, 1.3, 2.0, 3.0, 4.0}) {
                Far.sharpness(scale);
                LongOpenHashSet planned = new LongOpenHashSet();
                Far.around(bx, bz, planned::add);
                long area = 0;
                for (long key : planned) {
                    int level = Far.level(key), x = Far.x(key), z = Far.z(key);
                    if (Far.refined(level, x, z, bx, bz)) {
                        for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++)
                            assertTrue(planned.contains(Far.key(level - 1, 2 * x + dx, 2 * z + dz)), "scale " + scale);
                    } else {
                        area += (long) Far.width(level) * Far.width(level);
                    }
                    // Nothing past the coarsest square.
                    int shift = Far.MAX_LEVEL - level;
                    assertTrue(x >> shift >= Far.column(bx, Far.MAX_LEVEL) - Far.OUTER && x >> shift <= Far.column(bx, Far.MAX_LEVEL) + Far.OUTER);
                    assertTrue(z >> shift >= Far.column(bz, Far.MAX_LEVEL) - Far.OUTER && z >> shift <= Far.column(bz, Far.MAX_LEVEL) + Far.OUTER);
                }
                long side = (2L * Far.OUTER + 1) * Far.width(Far.MAX_LEVEL);
                assertEquals(side * side, area, "scale " + scale);
            }
        } finally {
            Far.sharpness(1.0);
        }
    }
}
