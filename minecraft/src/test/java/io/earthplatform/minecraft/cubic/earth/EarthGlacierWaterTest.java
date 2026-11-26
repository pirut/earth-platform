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

    @Test
    void slopingShadowBodyClassifierSeparatesShadowsFromLakesAndShallowWater() {
        assertTrue(EarthGenerator.isSlopingShadowBody(180, 0, 863.0),
                "Trollveggen-like 0% flat shadow spanning 863 m must classify as sloping shadow");
        assertTrue(EarthGenerator.isSlopingShadowBody(20, 1, EarthGenerator.SHADOW_MIN_SPAN + 1.0),
                "5% flat (< 10%) body spanning more than SHADOW_MIN_SPAN must classify as sloping shadow");
        assertFalse(EarthGenerator.isSlopingShadowBody(10, 1, 200.0),
                "10% flat body (flat * 10 == evaluated) must NOT classify as sloping shadow");
        assertFalse(EarthGenerator.isSlopingShadowBody(100, 30, 80.0),
                "proglacial lake with 30% flat surface must NOT classify as sloping shadow");
        assertFalse(EarthGenerator.isSlopingShadowBody(50, 0, EarthGenerator.SHADOW_MIN_SPAN),
                "shallow body with span <= SHADOW_MIN_SPAN (24 m) must NOT classify as sloping shadow");
        assertFalse(EarthGenerator.isSlopingShadowBody(0, 0, 500.0),
                "body with zero evaluated samples must NOT classify as sloping shadow");
    }

    @Test
    void detectShadowsPreservesProglacialLakesAndDrownedReservoirs() {
        float[] water = new float[SPAN * SPAN], ground = new float[SPAN * SPAN];
        boolean[] iceAt = new boolean[SPAN * SPAN], seaAt = new boolean[SPAN * SPAN];
        setupSteppedLake(water, ground, iceAt, true);

        EarthGenerator.SteppedWater glacialResult = EarthGenerator.drownedGround(
                SPAN, water, ground, iceAt, () -> {}, seaAt,
                (wi, wj, di, dj) -> ground[dj * SPAN + di],
                new IntArrayFIFOQueue(), true);
        assertNull(glacialResult.sunk(), "proglacial lake must not be classified as drowned with detectShadows=true");
        assertNotNull(glacialResult.glacial(), "proglacial lake must remain glacial with detectShadows=true");
        assertNull(glacialResult.shadow(), "proglacial lake must not be marked as mountain shadow");

        Arrays.fill(iceAt, false);
        setupSteppedLake(water, ground, iceAt, false);
        EarthGenerator.SteppedWater reservoirResult = EarthGenerator.drownedGround(
                SPAN, water, ground, iceAt, () -> {}, seaAt,
                (wi, wj, di, dj) -> ground[dj * SPAN + di],
                new IntArrayFIFOQueue(), true);
        assertNotNull(reservoirResult.sunk(), "drowned reservoir must remain sunk with detectShadows=true");
        assertNull(reservoirResult.glacial(), "drowned reservoir must not be glacial with detectShadows=true");
        assertNull(reservoirResult.shadow(), "drowned reservoir must not be marked as mountain shadow");
    }

    @Test
    void slopingMountainShadowIsMaskedWithoutTrippingGlacialWhileFlatValleyRiverStaysUnmarked() {
        float[] water = new float[SPAN * SPAN], ground = new float[SPAN * SPAN];
        boolean[] iceAt = new boolean[SPAN * SPAN], seaAt = new boolean[SPAN * SPAN];
        Arrays.fill(water, Float.NaN);
        Arrays.fill(ground, Float.NaN);

        // Background mountain wall: rows 0..17 slope down at 0.45 m/block (28.8 m per 64-block sample);
        // rows 18..23 form a flat 100 m valley floor.
        for (int j = 0; j < SPAN; j++) {
            float elev = j <= 17 ? 100f + (18 - j) * 28.8f : 100f;
            for (int i = 0; i < SPAN; i++) {
                ground[j * SPAN + i] = elev;
            }
        }
        // Wide sloping mountain shadow body: rows 4..13, cols 4..11 (> 250 m vertical span, >= 3 crossings, 0% flat).
        for (int j = 4; j <= 13; j++) {
            for (int i = 4; i <= 11; i++) {
                int at = j * SPAN + i;
                water[at] = ground[at];
                ground[at] = Float.NaN;
            }
        }
        // Attached narrow 1-sample-wide sloping tail descending from row 14..17 at col 8 (not wideWater, grad = 0.45).
        for (int j = 14; j <= 17; j++) {
            int at = j * SPAN + 8;
            water[at] = ground[at];
            ground[at] = Float.NaN;
        }
        // Elevated SNOW_AND_ICE along the dry ridges above/beside the shadow, tripping isGlacierFront in legacy mode.
        for (int j = 0; j <= 7; j++) {
            for (int i = 0; i < SPAN; i++) {
                int at = j * SPAN + i;
                if (!Float.isNaN(ground[at])) iceAt[at] = true;
            }
        }
        // Flat valley river at rows 19..21, cols 2..21 (grad == 0.0 <= 0.15) touching a foot pixel at (8, 18).
        water[18 * SPAN + 8] = ground[18 * SPAN + 8];
        ground[18 * SPAN + 8] = Float.NaN;
        for (int j = 19; j <= 21; j++) {
            for (int i = 2; i <= 21; i++) {
                int at = j * SPAN + i;
                water[at] = 100f;
                ground[at] = Float.NaN;
            }
        }

        // Hydrology v2 (detectShadows = true): shadow body + narrow sloping tail are masked, never glacial/sunk;
        // flat valley river remains completely unmarked.
        EarthGenerator.SteppedWater v2 = EarthGenerator.drownedGround(
                SPAN, water, ground, iceAt, () -> {}, seaAt,
                (wi, wj, di, dj) -> ground[dj * SPAN + di],
                new IntArrayFIFOQueue(), true);
        assertNull(v2.sunk(), "sloping mountain shadow must not classify as drowned reservoir");
        assertNull(v2.glacial(), "sloping mountain shadow touching snow/ice must not classify as glacial");
        assertNotNull(v2.shadow(), "sloping mountain shadow must produce a shadow mask");
        assertTrue(v2.shadow()[8 * SPAN + 8], "wide shadow interior must be marked shadowed");
        assertTrue(v2.shadow()[16 * SPAN + 8], "attached narrow sloping tail must be marked shadowed");
        assertFalse(v2.shadow()[20 * SPAN + 8], "flat valley river below sloping tail must remain unmarked");
        assertFalse(v2.shadow()[20 * SPAN + 14], "flat valley river away from tail must remain unmarked");

        // Legacy (detectShadows = false): preserves pre-v2 glacial classification with no shadow mask.
        EarthGenerator.SteppedWater legacy = EarthGenerator.drownedGround(
                SPAN, water, ground, iceAt, () -> {}, seaAt,
                (wi, wj, di, dj) -> ground[dj * SPAN + di],
                new IntArrayFIFOQueue(), false);
        assertNull(legacy.shadow(), "legacy detectShadows=false must never allocate shadow mask");
        assertNotNull(legacy.glacial(), "legacy detectShadows=false must preserve glacial classification");
        assertNull(legacy.sunk(), "legacy sloping shadow touching ice should match glacial branch");
    }
}
