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
     * Whether a column is kept around a position answers as going over them all does, for every
     * sharpness, finest level and position, near every edge of every level's square.
     */
    @Test void keptAnswersAsAroundGives() {
        java.util.Random random = new java.util.Random(77);
        try {
            for (double scale : new double[] {0.7, 1.0, 1.6, 2.0, 3.0, 4.0}) {
                Far.sharpness(scale);
                int hole = Far.hole();
                for (int round = 0; round < 6; round++) {
                    int bx = random.nextInt(4_000_000) - 2_000_000, bz = random.nextInt(4_000_000) - 2_000_000;
                    int finest = Far.MIN_LEVEL + random.nextInt(Far.MAX_LEVEL - Far.MIN_LEVEL + 1);
                    LongOpenHashSet planned = new LongOpenHashSet();
                    int[] count = new int[1];
                    Far.around(bx, bz, finest, hole, key -> { planned.add(key); count[0]++; });
                    // No column twice, so a count of them is the plan's size.
                    assertEquals(planned.size(), count[0]);
                    for (long key : planned) assertTrue(Far.kept(key, bx, bz, finest, hole));
                    // Every column of each level's square and of a margin two columns wide round it,
                    // and round the player's column for the levels below the finest.
                    for (int level = Far.MIN_LEVEL - 1; level <= Far.MAX_LEVEL; level++) {
                        int minX = Far.column(bx, level), maxX = minX, minZ = Far.column(bz, level), maxZ = minZ;
                        for (long key : planned) {
                            if (Far.level(key) != level) continue;
                            minX = Math.min(minX, Far.x(key)); maxX = Math.max(maxX, Far.x(key));
                            minZ = Math.min(minZ, Far.z(key)); maxZ = Math.max(maxZ, Far.z(key));
                        }
                        for (int z = minZ - 2; z <= maxZ + 2; z++) for (int x = minX - 2; x <= maxX + 2; x++) {
                            long key = Far.key(level, x, z);
                            assertEquals(planned.contains(key), Far.kept(key, bx, bz, finest, hole), "scale " + scale + " level " + level);
                        }
                    }
                }
            }
        } finally {
            Far.sharpness(1.0);
        }
    }

    /**
     * With a coarser level the finest drawn, whether a column is replaced by finer ones asks where
     * the camera is only at the column's own level, and never at or below the finest: anywhere in
     * one column of the level above the finest, every column answers the same. The far walk relies
     * on it to wait for the camera to leave that column (FarRenderer's far.coarseWalks).
     */
    @Test void refinedAboveTheFinestOnlyAsksTheColumnAboveIt() {
        java.util.Random random = new java.util.Random(91);
        try {
            for (double scale : new double[] {0.7, 1.0, 2.0, 4.0}) {
                Far.sharpness(scale);
                for (int round = 0; round < 40; round++) {
                    int finest = Far.MIN_LEVEL + random.nextInt(Far.MAX_LEVEL - Far.MIN_LEVEL);
                    int width = Far.width(finest + 1);
                    int bx = random.nextInt(4_000_000) - 2_000_000, bz = random.nextInt(4_000_000) - 2_000_000;
                    // Elsewhere in the camera's column of the level above the finest, its corners included.
                    int ox = Math.floorDiv(bx, width) * width + (round % 4 == 0 ? 0 : round % 4 == 1 ? width - 1 : random.nextInt(width));
                    int oz = Math.floorDiv(bz, width) * width + (round % 4 == 2 ? 0 : round % 4 == 3 ? width - 1 : random.nextInt(width));
                    for (int level = Far.MIN_LEVEL; level <= Far.MAX_LEVEL; level++) {
                        int reach = Far.hole(level) + 3, cx = Far.column(bx, level), cz = Far.column(bz, level);
                        for (int z = cz - reach; z <= cz + reach; z++) for (int x = cx - reach; x <= cx + reach; x++) {
                            boolean refined = Far.refined(level, x, z, bx, bz, finest);
                            assertEquals(refined, Far.refined(level, x, z, ox, oz, finest), "scale " + scale + " level " + level + " finest " + finest);
                            if (level <= finest) assertTrue(!refined);
                        }
                    }
                }
            }
        } finally {
            Far.sharpness(1.0);
        }
    }

    /**
     * At every choice, extreme ones included, the drawn columns (the ones no finer level replaces)
     * cover the coarsest square exactly once, each replaced column's four children are asked for,
     * and the coarsest level keeps its outer ring.
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
                // The coarsest level keeps its outer ring, so the horizon never waits on finer levels.
                for (int x = -Far.OUTER; x <= Far.OUTER; x++)
                    assertTrue(!Far.refined(Far.MAX_LEVEL, Far.column(bx, Far.MAX_LEVEL) + x, Far.column(bz, Far.MAX_LEVEL) + Far.OUTER, bx, bz), "scale " + scale);
                long side = (2L * Far.OUTER + 1) * Far.width(Far.MAX_LEVEL);
                assertEquals(side * side, area, "scale " + scale);
            }
        } finally {
            Far.sharpness(1.0);
        }
    }
}
