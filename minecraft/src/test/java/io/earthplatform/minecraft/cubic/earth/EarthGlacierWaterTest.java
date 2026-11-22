package io.earthplatform.minecraft.cubic.earth;

import static org.junit.jupiter.api.Assertions.*;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Retreating glacier proglacial lakes stay at their low lake level instead of being raised like drowned reservoirs. */
class EarthGlacierWaterTest {
    private static final int SPAN = 24;

    private static void setupSteppedLake(float[] water, float[] ground, boolean[] iceAt, boolean glacialFront) {
        Arrays.fill(water, Float.NaN);
        Arrays.fill(ground, Float.NaN);
        // Wide lake at rows 6..17, cols 3..16 stepping up from 45 m to 94 m across stale elevated terrain.
        float[] colLevels = {45, 45, 45, 45, 45, 45, 62, 62, 75, 75, 86, 86, 94, 94};
        for (int j = 6; j <= 17; j++) {
            for (int c = 0; c < colLevels.length; c++) {
                int i = 3 + c;
                water[j * SPAN + i] = colLevels[c];
            }
        }
        // Dry valley/glacier walls surrounding the lake.
        for (int j = 0; j < SPAN; j++) {
            for (int i = 0; i < SPAN; i++) {
                int at = j * SPAN + i;
                if (!Float.isNaN(water[at])) continue;
                if (i >= 17) {
                    // Terminus / dam wall at the high end.
                    ground[at] = glacialFront ? 115f : 120f;
                    if (glacialFront) iceAt[at] = true;
                } else {
                    ground[at] = glacialFront ? 50f + Math.max(0, i - 8) * 7f : 120f;
                    if (glacialFront && i >= 11) iceAt[at] = true;
                }
            }
        }
    }

    @Test
    void retreatingGlacierLakeIsMarkedGlacialAtLowWaterInsteadOfDrowned() {
        float[] water = new float[SPAN * SPAN], ground = new float[SPAN * SPAN];
        boolean[] iceAt = new boolean[SPAN * SPAN], seaAt = new boolean[SPAN * SPAN];
        setupSteppedLake(water, ground, iceAt, true);

        EarthGenerator.SteppedWater result = EarthGenerator.drownedGround(
                SPAN, water, ground, iceAt, () -> {}, seaAt,
                (wi, wj, di, dj) -> ground[dj * SPAN + di],
                new IntArrayFIFOQueue());

        assertNull(result.sunk(), "proglacial lake must not be classified as a drowned reservoir");
        assertNotNull(result.glacial(), "proglacial lake must be recorded as glacial");
        // Both the low lake end and the stepped ice-retreat end take the low lake level (~45 m).
        assertEquals(45f, result.glacial()[10 * SPAN + 5], 1f);
        assertEquals(45f, result.glacial()[10 * SPAN + 15], 1f);
    }

    @Test
    void nonGlacialSteppedReservoirRemainsClassifiedAsDrowned() {
        float[] water = new float[SPAN * SPAN], ground = new float[SPAN * SPAN];
        boolean[] iceAt = new boolean[SPAN * SPAN], seaAt = new boolean[SPAN * SPAN];
        setupSteppedLake(water, ground, iceAt, false);

        EarthGenerator.SteppedWater result = EarthGenerator.drownedGround(
                SPAN, water, ground, iceAt, () -> {}, seaAt,
                (wi, wj, di, dj) -> ground[dj * SPAN + di],
                new IntArrayFIFOQueue());

        assertNotNull(result.sunk(), "non-glacial flooded valley should remain a drowned reservoir");
        assertNull(result.glacial(), "non-glacial reservoir must not be marked glacial");
        assertEquals(120f, result.sunk()[10 * SPAN + 5], 2f);
        assertEquals(120f, result.sunk()[10 * SPAN + 15], 2f);
    }

    @Test
    void glacierFrontThresholdRequiresRealIceShare() {
        assertFalse(EarthGenerator.isGlacierFront(20, 0));
        assertFalse(EarthGenerator.isGlacierFront(20, 1));
        assertTrue(EarthGenerator.isGlacierFront(4, 1));
        assertTrue(EarthGenerator.isGlacierFront(20, 2));
    }
}
