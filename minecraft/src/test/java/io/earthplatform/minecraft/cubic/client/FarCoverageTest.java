package io.earthplatform.minecraft.cubic.client;

import io.earthplatform.minecraft.cubic.CubeKey;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FarCoverageTest {
    private static final int UNIT = 256;

    @Test void nullAndEmptySnapshotsNeverCoverAnything() {
        for (LongOpenHashSet drawn : new LongOpenHashSet[] {null, new LongOpenHashSet()}) {
            char[] drops = new char[256];
            FarCoverage coverage = new FarCoverage(4, -256, -512, -2, 32, drops, drawn);
            for (int y = -2; y <= 33; y++) for (int z = -2; z <= 17; z++) for (int x = -2; x <= 17; x++)
                assertFalse(coverage.drawn(x, y, z));
        }
    }

    @Test void fractionalDropsRequireTheLowerCubeEvenWhenTheUpperOneIsDrawn() {
        char[] drops = new char[256];
        drops[0] = 1;
        LongOpenHashSet drawn = new LongOpenHashSet();
        drawn.add(CubeKey.pack(0, 0, 0));
        assertFalse(new FarCoverage(4, 0, 0, 0, 16, drops, drawn).drawn(0, 0, 0));
        drawn.add(CubeKey.pack(0, -1, 0));
        assertTrue(new FarCoverage(4, 0, 0, 0, 16, drops, drawn).drawn(0, 0, 0));
        // A whole-block drop still spans both cubes until its full voxel fits in the lower one.
        drops[0] = UNIT;
        assertTrue(new FarCoverage(4, 0, 0, 0, 16, drops, drawn).drawn(0, 0, 0));
        drawn.remove(CubeKey.pack(0, 0, 0));
        assertFalse(new FarCoverage(4, 0, 0, 0, 16, drops, drawn).drawn(0, 0, 0));
        drops[0] = 16 * UNIT;
        assertTrue(new FarCoverage(4, 0, 0, 0, 16, drops, drawn).drawn(0, 0, 0));
    }

    @Test void everyCubeInACoarseFootprintMustBeDrawnAcrossEveryTouchedRow() {
        for (int level = 5; level <= 8; level++) {
            int v = 1 << level, cubes = v >> 4;
            LongOpenHashSet drawn = new LongOpenHashSet();
            for (int y = -1; y < cubes; y++) fill(drawn, -cubes, y, -2 * cubes, cubes);
            char[] drops = new char[256];
            drops[0] = 1;
            FarCoverage full = new FarCoverage(level, -v, -2 * v, 0, 16, drops, drawn);
            assertTrue(full.drawn(0, 0, 0), "level " + level);
            // Any of the first/middle/last footprint cubes can open a hole, including the
            // fractional-drop row below the voxel and its last top cube row.
            for (int y : new int[] {-1, 0, cubes - 1}) for (int z : new int[] {0, cubes / 2, cubes - 1})
                for (int x : new int[] {0, cubes / 2, cubes - 1}) {
                    long missing = CubeKey.pack(-cubes + x, y, -2 * cubes + z);
                    drawn.remove(missing);
                    assertFalse(new FarCoverage(level, -v, -2 * v, 0, 16, drops, drawn).drawn(0, 0, 0),
                            "partial footprint at level " + level);
                    drawn.add(missing);
                }
        }
    }

    @Test void allDropValuesMatchTheReferenceAtNegativeCoordinatesAndVerticalGaps() {
        int x0 = -263, z0 = -519, level = 4, bottom = -1, rows = 32;
        LongOpenHashSet drawn = new LongOpenHashSet();
        for (int y = -25; y <= 20; y++) {
            if (y == -17 || y == -6 || y == -1 || y == 2 || y == 11) continue;
            for (int z = -1; z <= 16; z++) for (int x = -1; x <= 16; x++)
                drawn.add(CubeKey.pack((x0 + x * 16) >> 4, y, (z0 + z * 16) >> 4));
        }
        for (int page = 0; page < 256; page++) {
            char[] drops = new char[256];
            for (int cell = 0; cell < 256; cell++) drops[cell] = (char) (page * 256 + cell);
            FarCoverage coverage = new FarCoverage(level, x0, z0, bottom, rows, drops, drawn);
            for (int y : new int[] {-1, 0, 1, 7, 15, 16, 31, 32})
                for (int z = -1; z <= 16; z++) for (int x = -1; x <= 16; x++)
                    check(coverage, level, x0, z0, bottom, drops, drawn, x, y, z);
        }
    }

    @Test void exhaustiveVerticalCoveragePatternsMatchAtEverySmallVoxelLevel() {
        for (int level = 0; level <= 4; level++) {
            int v = 1 << level, x0 = -17, z0 = -33;
            for (int pattern = 0; pattern < 256; pattern++) {
                LongOpenHashSet drawn = new LongOpenHashSet();
                for (int y = -4; y <= 3; y++) if ((pattern & 1 << (y + 4)) != 0)
                    for (int z = -1; z <= 16; z++) for (int x = -1; x <= 16; x++)
                        drawn.add(CubeKey.pack((x0 + x * v) >> 4, y, (z0 + z * v) >> 4));
                char[] drops = new char[256];
                int[] values = {0, 1, 255, 256, Math.min(65535, v * UNIT - 1)};
                for (int cell = 0; cell < 256; cell++) drops[cell] = (char) values[cell % values.length];
                FarCoverage coverage = new FarCoverage(level, x0, z0, -1, 32, drops, drawn);
                for (int y = -1; y <= 32; y++) for (int x : new int[] {-1, 0, 1, 7, 15, 16})
                    for (int z : new int[] {-1, 0, 7, 15, 16}) check(coverage, level, x0, z0, -1, drops, drawn, x, y, z);
            }
        }
    }

    @Test void randomFullAndPartialFootprintsMatchAtEveryLevelIncludingColumnSeams() {
        Random random = new Random(528627);
        for (int level = 0; level <= 8; level++) for (int round = 0; round < 8; round++) {
            int v = 1 << level, cubes = Math.max(1, v >> 4), rows = 32;
            // Deliberately do not align the base to a voxel or near cube. The old loop starts
            // at floor(block / 16) and still asks for exactly cubes * cubes horizontal cells.
            int x0 = random.nextInt(8192) - 8192, z0 = random.nextInt(8192) - 8192, bottom = random.nextInt(5) - 2;
            char[] drops = new char[256];
            for (int cell = 0; cell < 256; cell++) drops[cell] = (char) random.nextInt(Math.min(65536, v * UNIT));
            LongOpenHashSet drawn = new LongOpenHashSet();
            for (int island = 0; island < 8; island++) {
                int x = island < 2 ? -1 : island < 4 ? 16 : random.nextInt(16);
                int z = island == 0 || island == 2 ? -1 : island == 1 || island == 3 ? 16 : random.nextInt(16);
                int vy = random.nextInt(rows + 2) - 1;
                int by = bottom * (16 << level) + vy * v;
                int drop = drops[Math.clamp(z, 0, 15) * 16 + Math.clamp(x, 0, 15)];
                int low = (by - (drop + 255) / 256) >> 4, high = (by + v - 1 - drop / 256) >> 4;
                for (int y = low; y <= high; y++) fill(drawn, (x0 + x * v) >> 4, y, (z0 + z * v) >> 4, cubes);
                if ((island & 1) != 0) drawn.remove(CubeKey.pack((x0 + x * v) >> 4, low, (z0 + z * v) >> 4));
            }
            FarCoverage coverage = new FarCoverage(level, x0, z0, bottom, rows, drops, drawn);
            for (int y = -1; y <= rows; y++) for (int z = -1; z <= 16; z++) for (int x = -1; x <= 16; x++)
                check(coverage, level, x0, z0, bottom, drops, drawn, x, y, z);
            // Arbitrary out-of-footprint queries still use the unchanged reference semantics.
            for (int x : new int[] {-5, 19}) for (int y : new int[] {-8, rows + 5})
                check(coverage, level, x0, z0, bottom, drops, drawn, x, y, 4);
        }
    }

    @Test void maximumHeightMasksCrossBitWordsAndDoNotShareJobState() {
        char[] drops = new char[256];
        Arrays.fill(drops, (char) 255);
        LongOpenHashSet first = new LongOpenHashSet();
        for (int y = -1; y < 1024; y++) if (y != 62 && y != 63 && y != 64 && y != 1022) first.add(CubeKey.pack(0, y, 0));
        FarCoverage covered = new FarCoverage(4, 0, 0, 0, 1024, drops, first);
        FarCoverage empty = new FarCoverage(4, 0, 0, 0, 1024, drops, new LongOpenHashSet());
        for (int y = -1; y <= 1024; y++) {
            check(covered, 4, 0, 0, 0, drops, first, 0, y, 0);
            assertFalse(empty.drawn(0, y, 0));
        }
    }

    @Test void keyLimitsAndOverflowQueriesKeepTheReferenceWrappingBehavior() {
        char[] drops = new char[256];
        Arrays.fill(drops, (char) 65535);
        for (int level : new int[] {1, 4, 8}) for (int base : new int[] {33_554_416, -33_554_432, Integer.MAX_VALUE - 4, Integer.MIN_VALUE + 4}) {
            LongOpenHashSet drawn = new LongOpenHashSet();
            for (int x = -2; x <= 2; x++) for (int y = -2; y <= 2; y++) for (int z = -2; z <= 2; z++)
                drawn.add(CubeKey.pack((base >> 4) + x, (base >> 4) + y, (base >> 4) + z));
            FarCoverage coverage = new FarCoverage(level, base, base, base, 32, drops, drawn);
            for (int y : new int[] {-2, -1, 0, 1, 32, 33}) for (int z : new int[] {-1, 0, 16}) for (int x : new int[] {-1, 0, 16})
                check(coverage, level, base, base, base, drops, drawn, x, y, z);
        }
    }

    private static void fill(LongOpenHashSet drawn, int x0, int y, int z0, int cubes) {
        for (int z = z0; z < z0 + cubes; z++) for (int x = x0; x < x0 + cubes; x++) drawn.add(CubeKey.pack(x, y, z));
    }

    private static void check(FarCoverage coverage, int level, int x0, int z0, int bottom, char[] drops,
                              LongOpenHashSet drawn, int x, int y, int z) {
        boolean expected = reference(level, x0, z0, bottom, drops, drawn, x, y, z);
        assertEquals(expected, coverage.drawn(x, y, z), () -> "level=" + level + ", base=" + x0 + "," + bottom + "," + z0
                + ", voxel=" + x + "," + y + "," + z + ", drop=" + (int) drops[Math.clamp(z, 0, 15) * 16 + Math.clamp(x, 0, 15)]);
    }

    /** Independent copy of the pre-cache production algorithm; do not replace it with a cached helper. */
    private static boolean reference(int level, int x0, int z0, int bottom, char[] drops, LongOpenHashSet drawn, int x, int vy, int z) {
        int v = 1 << level, drop = drops[Math.clamp(z, 0, 15) * 16 + Math.clamp(x, 0, 15)];
        int bx = x0 + x * v, by = bottom * (16 << level) + vy * v, bz = z0 + z * v;
        int low = by - Math.ceilDiv(drop, UNIT), high = by + v - 1 - drop / UNIT, cubes = Math.max(1, v >> 4);
        for (int cy = low >> 4; cy <= high >> 4; cy++) for (int cz = bz >> 4; cz < (bz >> 4) + cubes; cz++)
            for (int cx = bx >> 4; cx < (bx >> 4) + cubes; cx++) if (!drawn.contains(CubeKey.pack(cx, cy, cz))) return false;
        return true;
    }
}
