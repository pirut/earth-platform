package io.earthplatform.minecraft.cubic.far;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            // However sharp, the coarsest level keeps a ring of its own and requests stay inside the server's slack.
            Far.sharpness(100);
            assertTrue(Far.hole() < Far.OUTER);
            assertTrue(2 * Far.hole() + 2 <= Far.SLACK);
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
}
