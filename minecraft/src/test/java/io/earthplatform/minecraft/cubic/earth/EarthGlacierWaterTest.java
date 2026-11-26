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

    @Test
    void waterSlopeUsesOnlyWaterNeighboursAndPhysicalSpacing() {
        int span = 8;
        float[] water = new float[span * span];
        Arrays.fill(water, Float.NaN);

        // Flat 1-wide river running along row 3 (water = 100), flanked by dry neighbours (NaN in water[]).
        // Even if dry valley walls stood 200 m higher in ground[], waterSlope never reads ground[] and stays 0.0.
        for (int i = 1; i <= 6; i++) water[3 * span + i] = 100f;
        assertEquals(0.0, EarthGenerator.waterSlope(water, span, 3 * span + 3, 64.0), 1e-9,
                "flat 1-wide river interior must have zero water-only slope");
        assertEquals(0.0, EarthGenerator.waterSlope(water, span, 3 * span + 1, 64.0), 1e-9,
                "one-sided flat river endpoint must have zero water-only slope");

        // Central difference vs one-sided cardinal difference and physical spacing scaling.
        water[5 * span + 2] = 100f;
        water[5 * span + 3] = 116f;
        water[5 * span + 4] = 132f;
        assertEquals(0.25, EarthGenerator.waterSlope(water, span, 5 * span + 3, 64.0), 1e-9,
                "central difference (132 - 100) / (2 * 64) = 0.25");
        assertEquals(0.50, EarthGenerator.waterSlope(water, span, 5 * span + 3, 32.0), 1e-9,
                "halving physical ground spacing (e.g. 60 deg latitude) must double slope");
        assertEquals(0.25, EarthGenerator.waterSlope(water, span, 5 * span + 2, 64.0), 1e-9,
                "one-sided difference (116 - 100) / 64 = 0.25");

        // Cliff-to-pool rim kink: two-sided min one-sided difference reads 0.0 on the flat pool rim
        // while preserving true slope on the continuous ramp.
        water[7 * span + 2] = 128.8f;
        water[7 * span + 3] = 100f;
        water[7 * span + 4] = 100f;
        assertEquals(0.0, EarthGenerator.waterSlope(water, span, 7 * span + 3, 64.0), 1e-9,
                "flat pool rim meeting cliff apron must read 0.0 via min one-sided difference");

        // Diagonal-only fallback when no cardinal wet neighbour exists uses minimum diagonal slope.
        water[1 * span + 1] = 100f;
        water[2 * span + 2] = 164f;
        assertEquals(64.0 / (64.0 * Math.sqrt(2.0)), EarthGenerator.waterSlope(water, span, 1 * span + 1, 64.0), 1e-9,
                "diagonal-only wet neighbour must divide by spacingMetres * sqrt(2)");
        water[0 * span + 2] = 116f;
        assertEquals(16.0 / (64.0 * Math.sqrt(2.0)), EarthGenerator.waterSlope(water, span, 1 * span + 1, 64.0), 1e-9,
                "diagonal-only fallback with multiple diagonal neighbours must return minimum diagonal slope");

        // Isolated wet sample, dry sample, and invalid spacingMetres return NaN.
        water[0 * span + 7] = 50f;
        assertTrue(Double.isNaN(EarthGenerator.waterSlope(water, span, 0 * span + 7, 64.0)),
                "isolated wet sample with no wet neighbours must return NaN");
        assertTrue(Double.isNaN(EarthGenerator.waterSlope(water, span, 0 * span + 0, 64.0)),
                "dry sample must return NaN");
        for (double badSpacing : new double[]{0.0, -64.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertTrue(Double.isNaN(EarthGenerator.waterSlope(water, span, 5 * span + 3, badSpacing)),
                    "invalid spacingMetres=" + badSpacing + " must return NaN");
        }
    }

    @Test
    void mixedFlatPoolConnectedToSlopingShadowApronKeepsPoolUnmarked() {
        float[] water = new float[SPAN * SPAN], ground = new float[SPAN * SPAN];
        boolean[] iceAt = new boolean[SPAN * SPAN], seaAt = new boolean[SPAN * SPAN];
        Arrays.fill(water, Float.NaN);
        Arrays.fill(ground, Float.NaN);

        for (int j = 0; j < SPAN; j++) {
            float elev = j <= 14 ? 100f + (15 - j) * 28.8f : 100f;
            for (int i = 0; i < SPAN; i++) ground[j * SPAN + i] = elev;
        }
        // Large 0.45-grade sloping shadow apron: rows 1..14, cols 2..19 (252 samples, all sloping).
        for (int j = 1; j <= 14; j++) {
            for (int i = 2; i <= 19; i++) {
                int at = j * SPAN + i;
                water[at] = ground[at];
                ground[at] = Float.NaN;
            }
        }
        // Directly connected 5x5 flat pool at 100 m: rows 15..19, cols 8..12 (25 samples; < 10% flat overall).
        for (int j = 15; j <= 19; j++) {
            for (int i = 8; i <= 12; i++) {
                int at = j * SPAN + i;
                water[at] = 100f;
                ground[at] = Float.NaN;
            }
        }

        EarthGenerator.SteppedWater result = EarthGenerator.drownedGround(
                SPAN, water, ground, iceAt, () -> {}, seaAt,
                (wi, wj, di, dj) -> ground[dj * SPAN + di],
                new IntArrayFIFOQueue(), true, 64.0);

        assertNull(result.sunk(), "mixed shadow+pool body must not classify as sunk");
        assertNull(result.glacial(), "mixed shadow+pool body must not classify as glacial");
        assertNotNull(result.shadow(), "sloping shadow apron must produce shadow mask");
        assertTrue(result.shadow()[7 * SPAN + 10], "sloping apron interior must be marked shadowed");
        for (int j = 15; j <= 19; j++) {
            for (int i = 8; i <= 12; i++) {
                assertFalse(result.shadow()[j * SPAN + i],
                        "connected 5x5 flat pool sample (" + i + "," + j + ") including rim and corners must remain unmarked");
            }
        }
    }

    @Test
    void shadowTailFloodStopsAtMaxStepsAndNeverCreepsIntoFlatRiverOrWideLake() {
        int span = 32;
        float[] water = new float[span * span], ground = new float[span * span];
        boolean[] iceAt = new boolean[span * span], seaAt = new boolean[span * span];
        Arrays.fill(water, Float.NaN);
        Arrays.fill(ground, 400f); // Steep 400 m dry valley walls everywhere outside water.

        // Wide sloping shadow core: rows 1..8, cols 2..9 (0.46875 grade -> 30 m per 64 m sample).
        for (int j = 1; j <= 8; j++) {
            for (int i = 2; i <= 9; i++) {
                int at = j * span + i;
                water[at] = 700f - j * 30f;
                ground[at] = Float.NaN;
            }
        }
        // Branch A: 12-step narrow 1-wide sloping tail descending from (9, 4) to (20, 4).
        for (int j = 9; j <= 20; j++) {
            int at = j * span + 4;
            water[at] = 700f - j * 30f;
            ground[at] = Float.NaN;
        }
        // Branch B: 2-step sloping tail at (9, 8)..(10, 8) touching a flat 1-wide river (water = 200)
        // running east along row 11 (cols 8..22) flanked by 400 m dry valley walls.
        water[9 * span + 8] = 430f;  ground[9 * span + 8] = Float.NaN;
        water[10 * span + 8] = 400f; ground[10 * span + 8] = Float.NaN;
        for (int i = 8; i <= 22; i++) {
            int at = 11 * span + i;
            water[at] = 200f;
            ground[at] = Float.NaN;
        }
        // Branch C: 2-step sloping tail at (6, 10)..(6, 11) diagonally 8-connected at (7, 12) to a
        // 5x5 flat lake (rows 7..11, cols 12..16, water = 100) AND to a 5x5 low-span wide body
        // (rows 1..5, cols 12..16, total span 15 m <= SHADOW_MIN_SPAN so Phase 1 skips it while every
        // sample has waterSlope = hypot(7.5/64, 7.5/64) = 0.166 > 0.15 to exercise Phase 2's wideWater guard).
        water[6 * span + 10] = 490f; ground[6 * span + 10] = Float.NaN;
        water[6 * span + 11] = 460f; ground[6 * span + 11] = Float.NaN;
        for (int j = 7; j <= 11; j++) {
            for (int i = 12; i <= 16; i++) {
                int at = j * span + i;
                water[at] = 100f;
                ground[at] = Float.NaN;
            }
        }
        for (int j = 1; j <= 5; j++) {
            for (int i = 12; i <= 16; i++) {
                int at = j * span + i;
                water[at] = 500f - ((i - 12) & 1) * 7.5f - ((j - 1) & 1) * 7.5f;
                ground[at] = Float.NaN;
            }
        }

        assertEquals(0.0, EarthGenerator.waterSlope(water, span, 11 * span + 10, 64.0), 1e-9,
                "flat river flanked by 400 m dry valley walls must have 0.0 waterSlope");

        EarthGenerator.SteppedWater result = EarthGenerator.drownedGround(
                span, water, ground, iceAt, () -> {}, seaAt,
                (wi, wj, di, dj) -> ground[dj * span + di],
                new IntArrayFIFOQueue(), true, 64.0);

        assertNotNull(result.shadow(), "shadow core must produce shadow mask");
        // Branch A: core body ends at j = 7 (wideWater); Phase 2 steps 1..SHADOW_TAIL_STEPS (j = 8..15) are shadowed,
        // while steps beyond SHADOW_TAIL_STEPS (j = 16..20) stop.
        for (int j = 8; j <= 7 + EarthGenerator.SHADOW_TAIL_STEPS; j++) {
            assertTrue(result.shadow()[j * span + 4], "tail step " + (j - 7) + " within SHADOW_TAIL_STEPS must be shadowed");
        }
        for (int j = 8 + EarthGenerator.SHADOW_TAIL_STEPS; j <= 20; j++) {
            assertFalse(result.shadow()[j * span + 4], "tail step " + (j - 7) + " beyond SHADOW_TAIL_STEPS must not be shadowed");
        }
        // Branch B: flat river beside steep valley walls never rim-creeps.
        for (int i = 9; i <= 22; i++) {
            assertFalse(result.shadow()[11 * span + i],
                    "flat river at col " + i + " beside 400 m valley walls must remain unshadowed");
        }
        // Branch C: neither the 8-connected flat lake (all 25 samples) nor the wideWater interior of the sloping wide body is flooded.
        for (int j = 7; j <= 11; j++) {
            for (int i = 12; i <= 16; i++) {
                assertFalse(result.shadow()[j * span + i],
                        "8-connected flat lake sample (" + i + "," + j + ") must remain unshadowed");
            }
        }
        for (int j = 2; j <= 4; j++) {
            for (int i = 13; i <= 15; i++) {
                assertFalse(result.shadow()[j * span + i],
                        "wideWater interior (" + i + "," + j + ") must not be entered by Phase 2 tail flood");
            }
        }
    }

    @Test
    void phaseTwoShadowTailNeverOverwritesSouthernGlacialOrSunkBody() {
        for (boolean southernGlacial : new boolean[]{true, false}) {
            float[] water = new float[SPAN * SPAN], ground = new float[SPAN * SPAN];
            boolean[] iceAt = new boolean[SPAN * SPAN], seaAt = new boolean[SPAN * SPAN];
            Arrays.fill(water, Float.NaN);
            Arrays.fill(ground, Float.NaN);

            for (int j = 0; j < SPAN; j++) {
                for (int i = 0; i < SPAN; i++) {
                    ground[j * SPAN + i] = j <= 10 ? 500f - j * 30f : 120f;
                }
            }
            // Northern sloping shadow (rows 1..7, cols 3..10): scanned FIRST in row-major order.
            for (int j = 1; j <= 7; j++) {
                for (int i = 3; i <= 10; i++) {
                    int at = j * SPAN + i;
                    water[at] = 500f - j * 30f;
                    ground[at] = Float.NaN;
                }
            }
            // Narrow 1-wide sloping tail descending from (8, 6)..(10, 6) straight to the northern rim of the southern body.
            for (int j = 8; j <= 10; j++) {
                int at = j * SPAN + 6;
                water[at] = 500f - j * 30f;
                ground[at] = Float.NaN;
            }
            // Southern stepped body (rows 11..19, cols 3..16): either proglacial (iceAt) or drowned reservoir (sunk).
            float[] colLevels = {45, 45, 45, 45, 45, 45, 62, 62, 75, 75, 86, 86, 94, 94};
            for (int j = 11; j <= 19; j++) {
                for (int c = 0; c < colLevels.length; c++) {
                    int at = j * SPAN + (3 + c);
                    water[at] = colLevels[c];
                    ground[at] = Float.NaN;
                }
            }
            if (southernGlacial) {
                for (int j = 11; j <= 20; j++) {
                    for (int i = 11; i < SPAN; i++) {
                        int at = j * SPAN + i;
                        if (!Float.isNaN(ground[at])) {
                            ground[at] = 115f;
                            iceAt[at] = true;
                        }
                    }
                }
            }

            EarthGenerator.SteppedWater result = EarthGenerator.drownedGround(
                    SPAN, water, ground, iceAt, () -> {}, seaAt,
                    (wi, wj, di, dj) -> ground[dj * SPAN + di],
                    new IntArrayFIFOQueue(), true, 64.0);

            assertNotNull(result.shadow(), "northern sloping shadow must be masked");
            assertTrue(result.shadow()[4 * SPAN + 6], "northern shadow core must be shadowed");
            assertTrue(result.shadow()[9 * SPAN + 6], "northern shadow tail must be shadowed");
            if (southernGlacial) {
                assertNotNull(result.glacial(), "southern proglacial lake must stay glacial");
                assertNull(result.sunk(), "southern proglacial lake must not become sunk");
            } else {
                assertNotNull(result.sunk(), "southern drowned reservoir must stay sunk");
                assertNull(result.glacial(), "southern drowned reservoir must not become glacial");
            }
            for (int j = 11; j <= 19; j++) {
                for (int i = 3; i <= 16; i++) {
                    int at = j * SPAN + i;
                    assertFalse(result.shadow()[at],
                            "Phase 2 tail flood must never overwrite southern "
                                    + (southernGlacial ? "glacial" : "sunk") + " body at (" + i + "," + j + ")");
                    boolean outerCorner = (i == 3 || i == 16) && (j == 11 || j == 19);
                    if (outerCorner) continue;
                    if (southernGlacial) {
                        assertFalse(Float.isNaN(result.glacial()[at]),
                                "southern glacial sample (" + i + "," + j + ") must keep glacial level");
                    } else {
                        assertFalse(Float.isNaN(result.sunk()[at]),
                                "southern sunk sample (" + i + "," + j + ") must keep sunk level");
                    }
                }
            }
        }
    }

    @Test
    void retreatedGlacierTongueAtTwentyPercentSlopeStaysGlacialWhileSteeperShadowStrips() {
        // Same stepped geometry touching elevated SNOW_AND_ICE shores:
        // - 0.21875 slope (14 m / 64 m <= SHADOW_GLACIAL_FLAT_GRAD = 0.30): preserved as glacial.
        // - 0.390625 slope (25 m / 64 m > SHADOW_GLACIAL_FLAT_GRAD = 0.30): stripped as shadow.
        for (boolean steepShadow : new boolean[]{false, true}) {
            float[] water = new float[SPAN * SPAN], ground = new float[SPAN * SPAN];
            boolean[] iceAt = new boolean[SPAN * SPAN], seaAt = new boolean[SPAN * SPAN];
            Arrays.fill(water, Float.NaN);
            Arrays.fill(ground, Float.NaN);

            float stepMetres = steepShadow ? 25f : 14f;
            for (int j = 0; j < SPAN; j++) {
                float elev = 100f + (SPAN - 1 - j) * stepMetres;
                for (int i = 0; i < SPAN; i++) ground[j * SPAN + i] = elev;
            }
            for (int j = 4; j <= 13; j++) {
                for (int i = 4; i <= 12; i++) {
                    int at = j * SPAN + i;
                    water[at] = ground[at];
                    ground[at] = Float.NaN;
                }
            }
            for (int j = 0; j <= 8; j++) {
                for (int i = 0; i < SPAN; i++) {
                    int at = j * SPAN + i;
                    if (!Float.isNaN(ground[at])) iceAt[at] = true;
                }
            }

            EarthGenerator.SteppedWater result = EarthGenerator.drownedGround(
                    SPAN, water, ground, iceAt, () -> {}, seaAt,
                    (wi, wj, di, dj) -> ground[dj * SPAN + di],
                    new IntArrayFIFOQueue(), true, 64.0);

            if (steepShadow) {
                assertNotNull(result.shadow(), "0.39-slope mountain shadow touching ice must be marked shadow");
                assertTrue(result.shadow()[8 * SPAN + 8], "steep shadow interior must be masked");
                assertNull(result.glacial(), "0.39-slope shadow must not classify as glacial");
                assertNull(result.sunk(), "0.39-slope shadow must not classify as sunk");
            } else {
                assertNull(result.shadow(), "0.22-slope retreated glacier tongue must not be marked shadow");
                assertNotNull(result.glacial(), "0.22-slope retreated glacier tongue must stay glacial");
                assertNull(result.sunk(), "0.22-slope retreated glacier tongue must not classify as sunk");
            }
        }
    }

    @Test
    void cornerConnectedFlatPoolKeepsAllTwentyFiveSamplesUnmarkedThroughPhaseTwo() {
        for (boolean cardinalTailToCorner : new boolean[]{false, true}) {
            float[] water = new float[SPAN * SPAN], ground = new float[SPAN * SPAN];
            boolean[] iceAt = new boolean[SPAN * SPAN], seaAt = new boolean[SPAN * SPAN];
            Arrays.fill(water, Float.NaN);
            Arrays.fill(ground, Float.NaN);

            for (int j = 0; j < SPAN; j++) {
                float elev = j <= 14 ? 100f + (15 - j) * 28.8f : 100f;
                for (int i = 0; i < SPAN; i++) ground[j * SPAN + i] = elev;
            }
            // Sloping 0.45-grade shadow apron: rows 1..14, cols 2..13.
            for (int j = 1; j <= 14; j++) {
                for (int i = 2; i <= 13; i++) {
                    int at = j * SPAN + i;
                    water[at] = ground[at];
                    ground[at] = Float.NaN;
                }
            }
            if (cardinalTailToCorner) {
                // 1-wide sloping spur at (14, 14) meeting the NW corner (14, 15) cardinally.
                water[14 * SPAN + 14] = 128.8f;
                ground[14 * SPAN + 14] = Float.NaN;
            }
            // 5x5 flat pool at rows 15..19, cols 14..18 meeting the apron at single corner (14, 15).
            for (int j = 15; j <= 19; j++) {
                for (int i = 14; i <= 18; i++) {
                    int at = j * SPAN + i;
                    water[at] = 100f;
                    ground[at] = Float.NaN;
                }
            }

            EarthGenerator.SteppedWater result = EarthGenerator.drownedGround(
                    SPAN, water, ground, iceAt, () -> {}, seaAt,
                    (wi, wj, di, dj) -> ground[dj * SPAN + di],
                    new IntArrayFIFOQueue(), true, 64.0);

            assertNotNull(result.shadow(), "sloping shadow apron must produce shadow mask");
            assertTrue(result.shadow()[7 * SPAN + 7], "shadow apron interior must be marked shadowed");
            for (int j = 15; j <= 19; j++) {
                for (int i = 14; i <= 18; i++) {
                    assertFalse(result.shadow()[j * SPAN + i],
                            "corner-connected 5x5 flat pool sample (" + i + "," + j + ") must remain unmarked (cardinalTail=" + cardinalTailToCorner + ")");
                }
            }
        }
    }

    @Test
    void steepWalledDrownedVGorgeReservoirStaysSunkInV2() {
        // Steep V-gorge reservoir: 0% flat old-DEM floor (0.45 grade on both canyon walls) surrounded by
        // level, non-sea-bounded waterline shores at 260 m (!touchesSea, shore IQR <= 2 * DROP,
        // p90Water <= q25Shore) -> must classify as sunk, NOT shadow.
        for (boolean levelBasinShores : new boolean[]{true, false}) {
            float[] water = new float[SPAN * SPAN], ground = new float[SPAN * SPAN];
            boolean[] iceAt = new boolean[SPAN * SPAN], seaAt = new boolean[SPAN * SPAN];
            Arrays.fill(water, Float.NaN);
            Arrays.fill(ground, Float.NaN);

            for (int j = 0; j < SPAN; j++) {
                for (int i = 0; i < SPAN; i++) {
                    // V-gorge cross section centered at col 10, sloping down toward south (floor 100..254.2 m <= 260 m).
                    float vWall = 100f + Math.abs(i - 10) * 28.8f + (16 - j) * 3f;
                    ground[j * SPAN + i] = levelBasinShores ? 260f : vWall;
                }
            }
            for (int j = 3; j <= 16; j++) {
                for (int i = 6; i <= 14; i++) {
                    int at = j * SPAN + i;
                    water[at] = 100f + Math.abs(i - 10) * 28.8f + (16 - j) * 3f;
                    ground[at] = Float.NaN;
                }
            }

            EarthGenerator.SteppedWater result = EarthGenerator.drownedGround(
                    SPAN, water, ground, iceAt, () -> {}, seaAt,
                    (wi, wj, di, dj) -> ground[dj * SPAN + di],
                    new IntArrayFIFOQueue(), true, 64.0);

            if (levelBasinShores) {
                assertNull(result.shadow(), "steep V-gorge drowned reservoir inside basin waterline must NOT classify as shadow");
                assertNull(result.glacial(), "non-glacial V-gorge reservoir must not classify as glacial");
                assertNotNull(result.sunk(), "steep V-gorge drowned reservoir must classify as sunk");
                assertEquals(260f, result.sunk()[10 * SPAN + 10], 1e-3f, "reservoir must take its 260 m waterline");
            } else {
                assertNotNull(result.shadow(), "same sloping water with sloping mountain shores must classify as shadow");
                assertNull(result.sunk(), "sloping mountain shadow must not classify as sunk");
            }
        }
    }

    @Test
    void phaseOneCrossingsAreInvariantUnderNorthSouthReflection() {
        // Exact 2x2 wideWater matrix [[107, 114], [160, 100]] surrounded by a 1-sample wet rim:
        // undirected wide-wide drop edges = 3 (>= DROWNED_CROSSINGS) regardless of N-S orientation.
        int span = 12;
        float[] waterNormal = new float[span * span], groundNormal = new float[span * span];
        boolean[] iceNormal = new boolean[span * span], seaNormal = new boolean[span * span];
        Arrays.fill(waterNormal, Float.NaN);
        Arrays.fill(groundNormal, 100f);

        // 4x4 wet patch at rows 4..7, cols 4..7 so rows 5..6, cols 5..6 are the exact 2x2 wideWater interior.
        float[][] patch = {
                { 97f,  97f, 124f, 124f},
                { 97f, 107f, 114f, 124f},
                {170f, 160f, 100f,  90f},
                {170f, 170f,  90f,  90f},
        };
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                int at = (4 + r) * span + (4 + c);
                waterNormal[at] = patch[r][c];
                groundNormal[at] = Float.NaN;
            }
        }

        float[] waterReflected = new float[span * span], groundReflected = new float[span * span];
        boolean[] iceReflected = new boolean[span * span], seaReflected = new boolean[span * span];
        for (int j = 0; j < span; j++) {
            int rj = span - 1 - j;
            for (int i = 0; i < span; i++) {
                waterReflected[rj * span + i] = waterNormal[j * span + i];
                groundReflected[rj * span + i] = groundNormal[j * span + i];
            }
        }

        EarthGenerator.SteppedWater normal = EarthGenerator.drownedGround(
                span, waterNormal, groundNormal, iceNormal, () -> {}, seaNormal,
                (wi, wj, di, dj) -> groundNormal[dj * span + di],
                new IntArrayFIFOQueue(), true, 32.0);
        EarthGenerator.SteppedWater reflected = EarthGenerator.drownedGround(
                span, waterReflected, groundReflected, iceReflected, () -> {}, seaReflected,
                (wi, wj, di, dj) -> groundReflected[dj * span + di],
                new IntArrayFIFOQueue(), true, 32.0);

        assertNotNull(normal.shadow(), "normal [[107,114],[160,100]] must count 3 undirected crossings and classify as shadow");
        assertNotNull(reflected.shadow(), "N-S reflected [[160,100],[107,114]] must count 3 undirected crossings and classify as shadow");
        for (int j = 0; j < span; j++) {
            int rj = span - 1 - j;
            for (int i = 0; i < span; i++) {
                assertEquals(normal.shadow()[j * span + i], reflected.shadow()[rj * span + i],
                        "shadow mask must be strictly invariant under N-S reflection at (" + i + "," + j + ")");
            }
        }
    }

    @Test
    void phaseTwoShadowTailNeverOverwritesQualifyingFallingWaterFaceAndRespectsGlacialTailThreshold() {
        int span = 32;
        float[] water = new float[span * span], ground = new float[span * span];
        boolean[] iceAt = new boolean[span * span], seaAt = new boolean[span * span];
        Arrays.fill(water, Float.NaN);
        Arrays.fill(ground, 150f);

        // Wide sloping shadow core on the left: rows 1..7, cols 1..7 (0.47 slope, elev 570..390).
        for (int j = 1; j <= 7; j++) {
            for (int i = 1; i <= 7; i++) {
                int at = j * span + i;
                water[at] = 600f - j * 30f;
                ground[at] = Float.NaN;
            }
        }
        // Narrow 1-wide sloping shadow tail descending east along row 6, cols 8..12 (elev 400..320, > 300 + BANK),
        // directly touching the waterfall face at (13, 6).
        for (int i = 8; i <= 12; i++) {
            int at = 6 * span + i;
            water[at] = 420f - (i - 7) * 20f;
            ground[at] = Float.NaN;
        }
        // Upper flat lake (>= FALL_POOL = 8): rows 2..4, cols 12..15 (12 samples at 300 m).
        for (int j = 2; j <= 4; j++) {
            for (int i = 12; i <= 15; i++) {
                int at = j * span + i;
                water[at] = 300f;
                ground[at] = Float.NaN;
            }
        }
        // Lower flat lake (>= FALL_POOL = 8): rows 9..11, cols 12..15 (12 samples at 200 m).
        for (int j = 9; j <= 11; j++) {
            for (int i = 12; i <= 15; i++) {
                int at = j * span + i;
                water[at] = 200f;
                ground[at] = Float.NaN;
            }
        }
        // Qualifying narrow stepped waterfall face: rows 5..8, col 13 (elev 280, 260, 240, 220; 0.31 slope > 0.15).
        float[] faceElev = {280f, 260f, 240f, 220f};
        for (int k = 0; k < faceElev.length; k++) {
            int at = (5 + k) * span + 13;
            water[at] = faceElev[k];
            ground[at] = Float.NaN;
        }

        EarthGenerator.SteppedWater result = EarthGenerator.drownedGround(
                span, water, ground, iceAt, () -> {}, seaAt,
                (wi, wj, di, dj) -> ground[dj * span + di],
                new IntArrayFIFOQueue(), true, 64.0);

        assertTrue(result.fallsComputed(), "drownedGround must precompute falls when shadow cores exist");
        assertNotNull(result.falls(), "qualifying waterfall face between two FALL_POOL lakes must populate falls[]");
        assertNotNull(result.shadow(), "sloping shadow core must produce shadow mask");
        assertTrue(result.shadow()[4 * span + 4], "shadow core interior must be shadowed");
        for (int i = 8; i <= 12; i++) {
            assertTrue(result.shadow()[6 * span + i], "shadow tail at (" + i + ",6) must be shadowed");
        }
        for (int j = 5; j <= 8; j++) {
            int at = j * span + 13;
            assertFalse(Float.isNaN(result.falls()[at]), "waterfall face sample (13," + j + ") must keep its falls[] level");
            assertFalse(result.shadow()[at], "Phase 2 shadow tail must NEVER overwrite qualifying waterfall face at (13," + j + ")");
        }

        // Glacier-front shadow core (0.39 slope touching SNOW_AND_ICE) uses SHADOW_GLACIAL_FLAT_GRAD (0.30) in Phase 2:
        // a 0.22-grade tail stays unshadowed while a 0.39-grade tail is shadowed.
        float[] gWater = new float[SPAN * SPAN], gGround = new float[SPAN * SPAN];
        boolean[] gIce = new boolean[SPAN * SPAN], gSea = new boolean[SPAN * SPAN];
        Arrays.fill(gWater, Float.NaN);
        Arrays.fill(gGround, Float.NaN);
        for (int j = 0; j < SPAN; j++) {
            float elev = 100f + (SPAN - 1 - j) * 25f;
            for (int i = 0; i < SPAN; i++) gGround[j * SPAN + i] = elev;
        }
        for (int j = 2; j <= 10; j++) {
            for (int i = 4; i <= 11; i++) {
                int at = j * SPAN + i;
                gWater[at] = gGround[at];
                gGround[at] = Float.NaN;
            }
        }
        for (int j = 0; j <= 6; j++) {
            for (int i = 0; i < SPAN; i++) {
                if (!Float.isNaN(gGround[j * SPAN + i])) gIce[j * SPAN + i] = true;
            }
        }
        // Tail A (col 6): 0.22 grade (14 m / 64 m <= 0.30) -> must stay unshadowed off glacier-front core.
        float base = gWater[10 * SPAN + 6];
        for (int step = 1; step <= 4; step++) {
            int at = (10 + step) * SPAN + 6;
            gWater[at] = base - step * 14f;
            gGround[at] = Float.NaN;
        }
        // Tail B (col 9): 0.39 grade (25 m / 64 m > 0.30) -> must be shadowed off glacier-front core.
        for (int step = 1; step <= 4; step++) {
            int at = (10 + step) * SPAN + 9;
            gWater[at] = base - step * 25f;
            gGround[at] = Float.NaN;
        }
        EarthGenerator.SteppedWater gRes = EarthGenerator.drownedGround(
                SPAN, gWater, gGround, gIce, () -> {}, gSea,
                (wi, wj, di, dj) -> gGround[dj * SPAN + di],
                new IntArrayFIFOQueue(), true, 64.0);
        assertNotNull(gRes.shadow(), "steep glacier-front shadow core must produce shadow mask");
        assertTrue(gRes.shadow()[12 * SPAN + 9], "0.39-grade tail off glacier-front shadow core must be shadowed");
        assertFalse(gRes.shadow()[12 * SPAN + 6], "0.22-grade tail (<= 0.30) off glacier-front shadow core must stay unshadowed");
    }

    @Test
    void disconnectedShadowCoreDoesNotDemoteSteepSideReachOrBreakWaterfall() {
        int span = 36;
        float[] water = new float[span * span], ground = new float[span * span];
        boolean[] iceAt = new boolean[span * span], seaAt = new boolean[span * span];
        Arrays.fill(water, Float.NaN);
        Arrays.fill(ground, 150f);

        // Disconnected sloping shadow core far away in the top-left (rows 1..6, cols 1..6, 0.78 grade).
        for (int j = 1; j <= 6; j++) {
            for (int i = 1; i <= 6; i++) {
                int at = j * span + i;
                water[at] = 700f - j * 25f;
                ground[at] = Float.NaN;
            }
        }
        // Upper flat pool (300 m, 12 samples >= FALL_POOL): rows 2..4, cols 12..15.
        for (int j = 2; j <= 4; j++) {
            for (int i = 12; i <= 15; i++) {
                int at = j * span + i;
                water[at] = 300f;
                ground[at] = Float.NaN;
            }
        }
        // Lower flat pool (200 m, 12 samples >= FALL_POOL): rows 9..11, cols 12..15.
        for (int j = 9; j <= 11; j++) {
            for (int i = 12; i <= 15; i++) {
                int at = j * span + i;
                water[at] = 200f;
                ground[at] = Float.NaN;
            }
        }
        // 4 waterfall face samples along col 13, rows 5..8: 280, 260, 240, 220.
        float[] faceElev = {280f, 260f, 240f, 220f};
        for (int k = 0; k < faceElev.length; k++) {
            int at = (5 + k) * span + 13;
            water[at] = faceElev[k];
            ground[at] = Float.NaN;
        }
        // 66-sample steep side reach (NOT 8-adjacent to the shadow core) rising 6 m/sample at 32 m spacing
        // (waterSlope = 6/32 = 0.1875 > SHADOW_FLAT_GRAD = 0.15 everywhere -> 0 flat samples), snaking east/west
        // starting at (14, 6) = 320 m (touching face sample (13, 6) across > DROP).
        int[][] snake = new int[66][2];
        int idx = 0;
        // Row 6: cols 14..34 (21 samples)
        for (int i = 14; i <= 34; i++) snake[idx++] = new int[]{i, 6};
        // Turnaround at (34, 7) (1 sample)
        snake[idx++] = new int[]{34, 7};
        // Row 8: cols 34..16 (19 samples)
        for (int i = 34; i >= 16; i--) snake[idx++] = new int[]{i, 8};
        // Turnaround at (16, 9) (1 sample)
        snake[idx++] = new int[]{16, 9};
        // Row 10: cols 16..34 (19 samples)
        for (int i = 16; i <= 34; i++) snake[idx++] = new int[]{i, 10};
        // Turnaround at (34, 11) (1 sample)
        snake[idx++] = new int[]{34, 11};
        // Row 12: cols 34..31 (4 samples) -> total 21+1+19+1+19+1+4 = 66 samples.
        for (int i = 34; i >= 31; i--) snake[idx++] = new int[]{i, 12};
        assertEquals(66, idx);
        for (int k = 0; k < snake.length; k++) {
            int at = snake[k][1] * span + snake[k][0];
            water[at] = 320f + k * 6f;
            ground[at] = Float.NaN;
        }

        // Precondition: every sample of the 66-sample side reach has waterSlope > SHADOW_FLAT_GRAD.
        for (int k = 0; k < snake.length; k++) {
            int at = snake[k][1] * span + snake[k][0];
            assertTrue(EarthGenerator.waterSlope(water, span, at, 32.0) > EarthGenerator.SHADOW_FLAT_GRAD,
                    "side reach sample " + k + " must have waterSlope > 0.15");
        }

        EarthGenerator.SteppedWater result = EarthGenerator.drownedGround(
                span, water, ground, iceAt, () -> {}, seaAt,
                (wi, wj, di, dj) -> ground[dj * span + di],
                new IntArrayFIFOQueue(), true, 32.0);

        assertNotNull(result.shadow(), "disconnected shadow core must produce shadow mask");
        assertTrue(result.shadow()[3 * span + 3], "disconnected shadow core interior must be shadowed");
        assertTrue(result.fallsComputed(), "falls must be precomputed when shadow core exists");
        assertNotNull(result.falls(), "unrelated waterfall must remain intact despite disconnected shadow core");
        float[] expected = {300f, 300f, 200f, 200f};
        for (int k = 0; k < expected.length; k++) {
            int at = (5 + k) * span + 13;
            assertEquals(expected[k], result.falls()[at], 1e-3f,
                    "waterfall face sample (13," + (5 + k) + ") must keep its exact pool level " + expected[k]);
            assertFalse(result.shadow()[at], "waterfall face sample must not be shadowed");
        }
    }

    @Test
    void singleDiagonalShadowContactDoesNotDemoteSteepSideReachOrBreakWaterfall() {
        // At-least-half shadow-adjacent FALL_POOL gating: a 66-sample steep mountain side reach (waterSlope = 0.1875 > 0.15)
        // that joins the unmasked 6-sample top row of the shadow core during fallingWater (72 samples total, with Phase 1
        // shadow starting at coreTopRow + 1 giving shadowAdjacent = 6 or 7 out of 72, well below half) must remain an
        // eligible FALL_POOL so an adjacent waterfall never loses its falls[] levels.
        // Test both coreTopRow = 13 (diagonal contact at (34, 12)-(35, 13)) and coreTopRow = 12 (cardinal contact at (34, 12)-(35, 12)).
        for (int coreTopRow : new int[]{13, 12}) {
            int span = 48;
            float[] water = new float[span * span], ground = new float[span * span];
            boolean[] iceAt = new boolean[span * span], seaAt = new boolean[span * span];
            Arrays.fill(water, Float.NaN);
            Arrays.fill(ground, 150f);

            // Shadow core at cols 35..40, rows coreTopRow..coreTopRow+5 (0.78 grade, 16 wideWater samples; Phase 1
            // shadow starts at coreTopRow + 1 while row coreTopRow is shadowed in Phase 2).
            for (int j = coreTopRow; j <= coreTopRow + 5; j++) {
                for (int i = 35; i <= 40; i++) {
                    int at = j * span + i;
                    water[at] = 700f - (j - coreTopRow) * 25f;
                    ground[at] = Float.NaN;
                }
            }
            // Upper flat pool (300 m, 12 samples): rows 2..4, cols 12..15.
            for (int j = 2; j <= 4; j++) {
                for (int i = 12; i <= 15; i++) {
                    int at = j * span + i;
                    water[at] = 300f;
                    ground[at] = Float.NaN;
                }
            }
            // Lower flat pool (200 m, 12 samples): rows 9..11, cols 12..15.
            for (int j = 9; j <= 11; j++) {
                for (int i = 12; i <= 15; i++) {
                    int at = j * span + i;
                    water[at] = 200f;
                    ground[at] = Float.NaN;
                }
            }
            // 4 waterfall face samples along col 13, rows 5..8: 280, 260, 240, 220.
            float[] faceElev = {280f, 260f, 240f, 220f};
            for (int k = 0; k < faceElev.length; k++) {
                int at = (5 + k) * span + 13;
                water[at] = faceElev[k];
                ground[at] = Float.NaN;
            }
            // 66-sample steep side reach (6 m/sample at 32 m spacing -> waterSlope = 0.1875 > 0.15 everywhere),
            // reaching 692 m at (34, 12) where it brushes the shadow core corner at (35, 13) / (35, 12).
            int[][] snake = new int[66][2];
            int idx = 0;
            for (int i = 14; i <= 34; i++) snake[idx++] = new int[]{i, 6};
            snake[idx++] = new int[]{34, 7};
            for (int i = 34; i >= 16; i--) snake[idx++] = new int[]{i, 8};
            snake[idx++] = new int[]{16, 9};
            for (int i = 16; i <= 34; i++) snake[idx++] = new int[]{i, 10};
            snake[idx++] = new int[]{34, 11};
            for (int i = 34; i >= 31; i--) snake[idx++] = new int[]{i, 12};
            assertEquals(66, idx);
            for (int k = 0; k < snake.length; k++) {
                int at = snake[k][1] * span + snake[k][0];
                water[at] = 320f + k * 6f;
                ground[at] = Float.NaN;
            }
            assertEquals(692f, water[12 * span + 34], 1e-3f, "precondition: contact sample (34,12) must be 692 m");

            EarthGenerator.SteppedWater result = EarthGenerator.drownedGround(
                    span, water, ground, iceAt, () -> {}, seaAt,
                    (wi, wj, di, dj) -> ground[dj * span + di],
                    new IntArrayFIFOQueue(), true, 32.0);

            assertNotNull(result.shadow(), "shadow core must produce shadow mask (coreTopRow=" + coreTopRow + ")");
            assertTrue(result.shadow()[coreTopRow * span + 35],
                    "shadow core NW corner (35," + coreTopRow + ") must be shadowed (Phase 2 above Phase 1 row " + (coreTopRow + 1) + ")");
            assertTrue(result.fallsComputed(), "falls must be precomputed when shadow core exists");
            assertNotNull(result.falls(), "waterfall must remain intact when steep side reach barely brushes shadow core");
            float[] expected = {300f, 300f, 200f, 200f};
            for (int k = 0; k < expected.length; k++) {
                int at = (5 + k) * span + 13;
                assertEquals(expected[k], result.falls()[at], 1e-3f,
                        "waterfall face sample (13," + (5 + k) + ") must keep exact pool level " + expected[k]
                                + " (coreTopRow=" + coreTopRow + ")");
                assertFalse(result.shadow()[at], "waterfall face sample must not be shadowed");
            }
        }
    }

    @Test
    void protrudingShadowContourRimWithSingleFlatTipCannotActAsFallPool() {
        int span = 32;
        float[] water = new float[span * span], ground = new float[span * span];
        boolean[] iceAt = new boolean[span * span], seaAt = new boolean[span * span];
        Arrays.fill(water, Float.NaN);
        Arrays.fill(ground, 150f);

        // Wide sloping shadow core: rows 2..15, cols 2..10 (water 610..350 m, 0.625 slope at 32 m spacing).
        for (int j = 2; j <= 15; j++) {
            for (int i = 2; i <= 10; i++) {
                int at = j * span + i;
                water[at] = 610f - (j - 2) * 20f;
                ground[at] = Float.NaN;
            }
        }
        // Unmasked 1-sample-wide contour rim 8-adjacent to shadow core along row 1 (cols 2..10, 9 samples,
        // rising 6 m/sample from 625 to 673 -> not wideWater and > DROP above row 2 so excluded from Phase 1 body,
        // shadowAdjacent = 9, 9*2 >= 10, ws = 0.1875 > 0.15) PLUS a 1-cell protruding flat tip at (10, 0) = 673 m
        // where ws == 0.0 (flatCount = 1, 1*2 < 10, size = 10 >= FALL_POOL).
        for (int i = 2; i <= 10; i++) {
            int at = 1 * span + i;
            water[at] = 625f + (i - 2) * 6f;
            ground[at] = Float.NaN;
        }
        water[0 * span + 10] = 673f;
        ground[0 * span + 10] = Float.NaN;
        assertEquals(0.0, EarthGenerator.waterSlope(water, span, 0 * span + 10, 32.0), 1e-9,
                "protruding rim tip must have waterSlope == 0.0");

        // Narrow descending tail from (10, 1) east along row 1 (cols 11..14, within SHADOW_TAIL_STEPS of (9, 2)).
        float[] tailElev = {640f, 600f, 560f, 520f};
        for (int k = 0; k < tailElev.length; k++) {
            int at = 1 * span + (11 + k);
            water[at] = tailElev[k];
            ground[at] = Float.NaN;
        }
        // Genuine flat lower lake (12 samples at 460 m): rows 1..3, cols 15..18.
        for (int j = 1; j <= 3; j++) {
            for (int i = 15; i <= 18; i++) {
                int at = j * span + i;
                water[at] = 460f;
                ground[at] = Float.NaN;
            }
        }
        // Genuine 2x6 (12-sample) flat control lake at 300 m placed right along the Phase 1 shadow core south edge
        // (rows 16..17, cols 3..8): row 15 cols 4..7 are 3x3 wideWater and Phase 1 shadowed (minmod slope 0.625),
        // while all 12 samples of rows 16..17 have waterSlope == 0.0 so they stay unmasked in Phase 1 (10 flat / 133
        // body samples < 10%), and all 6 samples of row 16 (cols 3..8) are directly 8-adjacent to Phase 1 shadow[row 15]
        // -> shadowAdjacent == 6 (shadowAdjacent * 2 >= size is TRUE), verifying flatCount * 2 >= size rescues it as
        // a FALL_POOL so its waterfall keeps falls[].
        for (int j = 16; j <= 17; j++) {
            for (int i = 3; i <= 8; i++) {
                int at = j * span + i;
                water[at] = 300f;
                ground[at] = Float.NaN;
            }
        }
        float[] realFace = {280f, 260f, 240f};
        for (int k = 0; k < realFace.length; k++) {
            int at = (18 + k) * span + 9;
            water[at] = realFace[k];
            ground[at] = Float.NaN;
        }
        for (int j = 21; j <= 23; j++) {
            for (int i = 8; i <= 11; i++) {
                int at = j * span + i;
                water[at] = 200f;
                ground[at] = Float.NaN;
            }
        }

        EarthGenerator.SteppedWater result = EarthGenerator.drownedGround(
                span, water, ground, iceAt, () -> {}, seaAt,
                (wi, wj, di, dj) -> ground[dj * span + di],
                new IntArrayFIFOQueue(), true, 32.0);

        assertNotNull(result.shadow(), "shadow core must produce shadow mask");
        assertTrue(result.fallsComputed(), "falls must be precomputed");
        // Shadow contour rim with only 1 flat tip (shadowAdjacent = 9, 9*2 >= 10, flatCount = 1, 1*2 < 10) must NOT
        // act as FALL_POOL: tail samples (11..14, 1) must NOT earn falls[] and MUST be stripped by Phase 2!
        for (int i = 11; i <= 14; i++) {
            int at = 1 * span + i;
            assertTrue(result.falls() == null || Float.isNaN(result.falls()[at]),
                    "tail at (" + i + ",1) off shadow contour rim must NOT be marked in falls[]");
            assertTrue(result.shadow()[at],
                    "tail at (" + i + ",1) off shadow contour rim must be stripped as shadow in Phase 2");
        }
        // Lower flat lake stays unmarked.
        assertFalse(result.shadow()[2 * span + 16], "lower flat lake must remain unshadowed");
        // Precondition on the 2x6 flat control lake: core edge row 15 cols 4..7 are shadowed (making all 6 north-edge
        // lake cells at row 16 cols 3..8 directly 8-adjacent -> shadowAdjacent == 6, 6*2 >= 12) while all 12 flat lake
        // cells remain unshadowed.
        for (int i = 4; i <= 7; i++) {
            assertTrue(result.shadow()[15 * span + i],
                    "Phase 1 shadow core south edge at (" + i + ",15) must be shadowed");
        }
        for (int j = 16; j <= 17; j++) {
            for (int i = 3; i <= 8; i++) {
                assertFalse(result.shadow()[j * span + i],
                        "flat control lake cell (" + i + "," + j + ") must remain unshadowed");
            }
        }
        // Majority-shadow-adjacent flat lake is rescued by flatCount * 2 >= size so real waterfall keeps falls[].
        assertNotNull(result.falls(), "real waterfall below shadow-adjacent flat lake must populate falls[]");
        for (int k = 0; k < realFace.length; k++) {
            int at = (18 + k) * span + 9;
            assertFalse(Float.isNaN(result.falls()[at]),
                    "real waterfall face at (9," + (18 + k) + ") below shadow-adjacent flat lake must keep falls[]");
            assertFalse(result.shadow()[at], "real waterfall face must not be shadowed");
        }
    }
    @Test
    void tightenedDrownedBasinVetoStripsCouloirFjordAndSmallPlanarShadowsWhileKeepingVGorgeReservoir() {
        // Case A: Concave mountain couloir shadow with elevated flanking canyon walls (wide shore IQR >> 2 * DROP).
        {
            float[] water = new float[SPAN * SPAN], ground = new float[SPAN * SPAN];
            boolean[] iceAt = new boolean[SPAN * SPAN], seaAt = new boolean[SPAN * SPAN];
            Arrays.fill(water, Float.NaN);
            for (int j = 0; j < SPAN; j++) {
                for (int i = 0; i < SPAN; i++) {
                    if (i < 6 || i > 12) ground[j * SPAN + i] = 380f - Math.max(0, j - 3) * 15f;
                    else ground[j * SPAN + i] = 300f - (j - 3) * 20f;
                }
            }
            for (int j = 3; j <= 14; j++) {
                for (int i = 6; i <= 12; i++) {
                    int at = j * SPAN + i;
                    water[at] = 300f - (j - 3) * 20f;
                    ground[at] = Float.NaN;
                }
            }
            EarthGenerator.SteppedWater res = EarthGenerator.drownedGround(
                    SPAN, water, ground, iceAt, () -> {}, seaAt,
                    (wi, wj, di, dj) -> ground[dj * SPAN + di],
                    new IntArrayFIFOQueue(), true, 64.0);
            assertNotNull(res.shadow(), "concave mountain couloir shadow must classify as shadow (wide shore IQR)");
            assertNull(res.sunk(), "concave mountain couloir shadow must NOT be vetoed into sunk");
            assertTrue(res.shadow()[8 * SPAN + 9], "couloir interior must be masked as shadow");
        }

        // Case B: Wide fjord-wall shadow bordering seaAt (touchesSea == true even with level upper rock rim).
        {
            float[] water = new float[SPAN * SPAN], ground = new float[SPAN * SPAN];
            boolean[] iceAt = new boolean[SPAN * SPAN], seaAt = new boolean[SPAN * SPAN];
            Arrays.fill(water, Float.NaN);
            Arrays.fill(ground, 210f);
            for (int j = 13; j < SPAN; j++) {
                for (int i = 0; i < SPAN; i++) {
                    seaAt[j * SPAN + i] = true;
                    ground[j * SPAN + i] = Float.NaN;
                }
            }
            for (int j = 3; j <= 12; j++) {
                for (int i = 6; i <= 12; i++) {
                    int at = j * SPAN + i;
                    water[at] = 200f - (j - 3) * 18f;
                    ground[at] = Float.NaN;
                }
            }
            EarthGenerator.SteppedWater res = EarthGenerator.drownedGround(
                    SPAN, water, ground, iceAt, () -> {}, seaAt,
                    (wi, wj, di, dj) -> ground[dj * SPAN + di],
                    new IntArrayFIFOQueue(), true, 64.0);
            assertNotNull(res.shadow(), "wide fjord-wall shadow bordering seaAt must classify as shadow");
            assertNull(res.sunk(), "fjord-wall shadow touching seaAt must NOT be vetoed into sunk");
            assertTrue(res.shadow()[7 * SPAN + 9], "fjord-wall shadow interior must be masked");
        }

        // Case C: Small 28 m planar face shadow (wideWater rows 5..8 span 28 m > SHADOW_MIN_SPAN, p90Water > q25Shore).
        {
            float[] water = new float[SPAN * SPAN], ground = new float[SPAN * SPAN];
            boolean[] iceAt = new boolean[SPAN * SPAN], seaAt = new boolean[SPAN * SPAN];
            Arrays.fill(water, Float.NaN);
            for (int j = 0; j < SPAN; j++) {
                for (int i = 0; i < SPAN; i++) {
                    ground[j * SPAN + i] = 100f + (j - 5) * (28f / 3f);
                }
            }
            for (int j = 4; j <= 9; j++) {
                for (int i = 4; i <= 9; i++) {
                    int at = j * SPAN + i;
                    water[at] = ground[at];
                    ground[at] = Float.NaN;
                }
            }
            EarthGenerator.SteppedWater res = EarthGenerator.drownedGround(
                    SPAN, water, ground, iceAt, () -> {}, seaAt,
                    (wi, wj, di, dj) -> ground[dj * SPAN + di],
                    new IntArrayFIFOQueue(), true, 32.0);
            assertNotNull(res.shadow(), "small 28 m planar face shadow must classify as shadow");
            assertNull(res.sunk(), "small 28 m planar face shadow must NOT be vetoed into sunk");
            assertTrue(res.shadow()[5 * SPAN + 5], "planar shadow interior must be masked");
        }

        // Case D: Physical V-gorge drowned reservoir (floor 140..255 m <= 260 m, waterline shores 260..268 m).
        {
            float[] water = new float[SPAN * SPAN], ground = new float[SPAN * SPAN];
            boolean[] iceAt = new boolean[SPAN * SPAN], seaAt = new boolean[SPAN * SPAN];
            Arrays.fill(water, Float.NaN);
            for (int j = 0; j < SPAN; j++) {
                for (int i = 0; i < SPAN; i++) {
                    ground[j * SPAN + i] = 260f + ((i + j) % 9); // 260..268 m level waterline (IQR <= 8 <= 2*DROP)
                }
            }
            for (int j = 4; j <= 15; j++) {
                for (int i = 6; i <= 14; i++) {
                    int at = j * SPAN + i;
                    water[at] = Math.min(255f, 140f + Math.abs(i - 10) * 28.8f);
                    ground[at] = Float.NaN;
                }
            }
            EarthGenerator.SteppedWater res = EarthGenerator.drownedGround(
                    SPAN, water, ground, iceAt, () -> {}, seaAt,
                    (wi, wj, di, dj) -> ground[dj * SPAN + di],
                    new IntArrayFIFOQueue(), true, 64.0);
            assertNull(res.shadow(), "physical V-gorge drowned reservoir with 260..268 m shores must NOT classify as shadow");
            assertNotNull(res.sunk(), "physical V-gorge drowned reservoir must classify as sunk");
            assertTrue(res.sunk()[10 * SPAN + 10] >= 260f && res.sunk()[10 * SPAN + 10] <= 268f,
                    "reservoir must take its 260..268 m waterline");
        }
    }

    @Test
    void rotatedHighLatitudeShadowApronWithSubDropCardinalStepsIsDetected() {
        // 73.5° N equatorial-grid reach spacing ≈ 18.2 m; 45°-rotated 0.45-grade plane has cardinal step 5.78 m < DROP (8 m),
        // giving 0 Phase 1 drop crossings while bodySpan >> 24 m and waterSlope ≈ 0.45.
        int span = 20;
        double spacing = 18.2;
        float[] water = new float[span * span], ground = new float[span * span];
        boolean[] iceAt = new boolean[span * span], seaAt = new boolean[span * span];
        Arrays.fill(water, Float.NaN);
        for (int j = 0; j < span; j++) {
            for (int i = 0; i < span; i++) {
                ground[j * span + i] = 200f + (i + j) * 5.78f;
            }
        }
        for (int j = 3; j <= 14; j++) {
            for (int i = 3; i <= 14; i++) {
                int at = j * span + i;
                water[at] = ground[at];
                ground[at] = Float.NaN;
            }
        }

        EarthGenerator.SteppedWater v2 = EarthGenerator.drownedGround(
                span, water, ground, iceAt, () -> {}, seaAt,
                (wi, wj, di, dj) -> ground[dj * span + di],
                new IntArrayFIFOQueue(), true, spacing);
        assertNotNull(v2.shadow(), "45°-rotated 0.45-grade shadow apron at 73.5° N must be detected despite 0 crossings");
        assertNull(v2.sunk(), "rotated shadow apron must not classify as sunk");
        assertNull(v2.glacial(), "rotated shadow apron must not classify as glacial");
        assertTrue(v2.shadow()[8 * span + 8], "interior of rotated shadow apron must be masked");

        // Legacy (!detectShadows): zero behavioral change (0 crossings -> skipped immediately).
        EarthGenerator.SteppedWater legacy = EarthGenerator.drownedGround(
                span, water, ground, iceAt, () -> {}, seaAt,
                (wi, wj, di, dj) -> ground[dj * span + di],
                new IntArrayFIFOQueue(), false, spacing);
        assertNull(legacy.shadow());
        assertNull(legacy.sunk());
        assertNull(legacy.glacial());

        // Control: 0.20-grade rotated glacier tongue with 0 crossings (step 2.57 m) and span > 24 m must NOT become shadow,
        // sunk, or glacial (preserves exact crossings >= DROWNED_CROSSINGS gating for sunk/glacial).
        float[] gWater = new float[span * span], gGround = new float[span * span];
        boolean[] gIce = new boolean[span * span], gSea = new boolean[span * span];
        Arrays.fill(gWater, Float.NaN);
        for (int j = 0; j < span; j++) {
            for (int i = 0; i < span; i++) {
                gGround[j * span + i] = 200f + (i + j) * 2.57f;
                gIce[j * span + i] = true;
            }
        }
        for (int j = 2; j <= 15; j++) {
            for (int i = 2; i <= 15; i++) {
                int at = j * span + i;
                gWater[at] = gGround[at];
                gGround[at] = Float.NaN;
            }
        }
        EarthGenerator.SteppedWater glacierCtrl = EarthGenerator.drownedGround(
                span, gWater, gGround, gIce, () -> {}, gSea,
                (wi, wj, di, dj) -> gGround[dj * span + di],
                new IntArrayFIFOQueue(), true, spacing);
        assertNull(glacierCtrl.shadow(), "0.20-grade glacier tongue (<= 0.30) must NOT classify as shadow");
        assertNull(glacierCtrl.sunk(), "sub-crossing body must never leak into sunk");
        assertNull(glacierCtrl.glacial(), "sub-crossing body must never leak into glacial");

        // Control: gentle wide river (0.04 grade <= SHADOW_FLAT_GRAD) spanning > 24 m with 0 crossings must bail out
        // BEFORE invoking dryGround or shoreHeight.
        float[] rWater = new float[span * span], rGround = new float[span * span];
        boolean[] rIce = new boolean[span * span], rSea = new boolean[span * span];
        Arrays.fill(rWater, Float.NaN);
        Arrays.fill(rGround, 100f);
        for (int j = 2; j <= 15; j++) {
            for (int i = 2; i <= 15; i++) {
                int at = j * span + i;
                rWater[at] = 200f + j * 2.5f; // span = 32.5 m > 24 m, ws = 2.5 / 64 = 0.039 <= 0.15
                rGround[at] = Float.NaN;
            }
        }
        EarthGenerator.SteppedWater riverCtrl = EarthGenerator.drownedGround(
                span, rWater, rGround, rIce,
                () -> { throw new AssertionError("gentle wide river with 0 crossings must skip dryGround"); },
                rSea,
                (wi, wj, di, dj) -> { throw new AssertionError("gentle wide river with 0 crossings must skip shoreHeight"); },
                new IntArrayFIFOQueue(), true, 64.0);
        assertNull(riverCtrl.shadow(), "gentle wide river must not classify as shadow");
        assertNull(riverCtrl.sunk(), "gentle wide river must not classify as sunk");
        assertNull(riverCtrl.glacial(), "gentle wide river must not classify as glacial");
    }

    @Test
    void phaseTwoShadowTailIsInvariantUnderCoreOrderingAcrossMixedGlacialAndNonGlacialCores() {
        // Odd span = 25 so row 12 is an exact N-S reflection axis (rj = 24 - j).
        // Phase 1 shadow cores occupy rows 5..7 (glacial North) and rows 17..19 (non-glacial South).
        // Two steep (> 0.30) vertical arms along col 7 (rows 8..11 and 16..13) converge onto a SOLE 1-cell steep
        // 8-connected bottleneck at (8, 12) at exact step 5 from BOTH Phase 1 cores (with (7, 12), (8, 11), (8, 13),
        // (9, 11), and (9, 13) dry so neither arm can bypass (8, 12) diagonally into (9, 12)). The 0.22-grade east
        // branch runs along row 12, cols 9..11 (steps 6..8 <= SHADOW_TAIL_STEPS = 8). In a single-pass first-come BFS,
        // scanning the glacial core first claims (8, 12) with the 0.30 threshold and blocks the non-glacial core from
        // entering (9, 12)..(11, 12); two-pass Phase 2 must shadow the branch identically regardless of core scan order.
        int span = 25;
        float[] waterA = new float[span * span], groundA = new float[span * span];
        boolean[] iceA = new boolean[span * span], seaA = new boolean[span * span];
        Arrays.fill(waterA, Float.NaN);
        Arrays.fill(groundA, 100f);

        // North core (rows 4..8, cols 6..11; Phase 1 core = rows 5..7): glacial-front steep shadow (0.47 grade).
        for (int j = 4; j <= 8; j++) {
            for (int i = 6; i <= 11; i++) {
                int at = j * span + i;
                waterA[at] = 700f - (j - 4) * 30f; // row 4 = 700, row 7 = 610, row 8 = 580
                groundA[at] = Float.NaN;
            }
        }
        for (int j = 3; j <= 9; j++) {
            for (int i = 5; i <= 12; i++) {
                int at = j * span + i;
                if (!Float.isNaN(groundA[at])) {
                    groundA[at] = 730f - (j - 3) * 30f;
                    iceA[at] = true;
                }
            }
        }
        // South core (rows 16..20, cols 6..11; Phase 1 core = rows 17..19): non-glacial steep shadow (0.47 grade).
        for (int j = 16; j <= 20; j++) {
            for (int i = 6; i <= 11; i++) {
                int at = j * span + i;
                waterA[at] = 380f - (j - 16) * 30f; // row 16 = 380, row 17 = 350, row 20 = 260
                groundA[at] = Float.NaN;
            }
        }
        // Upper steep (> 0.30) arm along col 7, rows 9..11 (steps 2..4 from Phase 1 row 7 via (7, 8) at step 1).
        for (int j = 9; j <= 11; j++) {
            int at = j * span + 7;
            waterA[at] = 555f - (j - 9) * 25f; // (7,9)=555, (7,10)=530, (7,11)=505
            groundA[at] = Float.NaN;
        }
        // Lower steep (> 0.30) arm along col 7, rows 15..13 (steps 2..4 from Phase 1 row 17 via (7, 16) at step 1).
        for (int j = 13; j <= 15; j++) {
            int at = j * span + 7;
            waterA[at] = 455f - (j - 13) * 25f; // (7,13)=455, (7,14)=430, (7,15)=405
            groundA[at] = Float.NaN;
        }
        // Sole 1-cell steep (> 0.30) bottleneck at (8, 12) = 480 m (step 5 from BOTH Phase 1 cores via diagonal
        // step from (7, 11) and (7, 13)); (7, 12), (8, 11), (8, 13), (9, 11), and (9, 13) remain dry so (9, 12) is
        // strictly unreachable without passing through (8, 12).
        waterA[12 * span + 8] = 480f;
        groundA[12 * span + 8] = Float.NaN;
        // 0.22-grade branch going east from the bottleneck along row 12, cols 9..11 (steps 6..8 <= SHADOW_TAIL_STEPS):
        // (9, 12) = 455 m gives (8, 12) a 25/64 = 0.391 slope (> 0.30), while min(|455-480|, |441-455|)/64 = 14/64 = 0.219.
        float[] branchElev = {455f, 441f, 427f};
        for (int k = 0; k < branchElev.length; k++) {
            int at = 12 * span + (9 + k);
            waterA[at] = branchElev[k];
            groundA[at] = Float.NaN;
        }

        // Preconditions: bottleneck isolation + steepness of arms/bottleneck (> 0.30) + intermediate east branch (0.15, 0.30].
        assertTrue(Float.isNaN(waterA[12 * span + 7]), "precondition: (7,12) must be dry");
        assertTrue(Float.isNaN(waterA[11 * span + 8]) && Float.isNaN(waterA[13 * span + 8]),
                "precondition: (8,11) and (8,13) must be dry");
        assertTrue(Float.isNaN(waterA[11 * span + 9]) && Float.isNaN(waterA[13 * span + 9]),
                "precondition: (9,11) and (9,13) must be dry so (9,12) is only reachable via (8,12)");
        for (int j : new int[]{9, 10, 11, 13, 14, 15}) {
            double armSlope = EarthGenerator.waterSlope(waterA, span, j * span + 7, 64.0);
            assertTrue(armSlope > EarthGenerator.SHADOW_GLACIAL_FLAT_GRAD,
                    "precondition: arm cell (7," + j + ") must have slope > 0.30, got " + armSlope);
        }
        double junctionSlope = EarthGenerator.waterSlope(waterA, span, 12 * span + 8, 64.0);
        assertTrue(junctionSlope > EarthGenerator.SHADOW_GLACIAL_FLAT_GRAD,
                "precondition: sole bottleneck (8,12) must have slope > 0.30, got " + junctionSlope);
        for (int i = 9; i <= 11; i++) {
            double bs = EarthGenerator.waterSlope(waterA, span, 12 * span + i, 64.0);
            assertTrue(bs > EarthGenerator.SHADOW_FLAT_GRAD && bs <= EarthGenerator.SHADOW_GLACIAL_FLAT_GRAD,
                    "precondition: branch cell (" + i + ",12) must have slope in (0.15, 0.30], got " + bs);
        }

        // Reflect North-South so the non-glacial core is scanned BEFORE the glacial core in Phase 1.
        float[] waterB = new float[span * span], groundB = new float[span * span];
        boolean[] iceB = new boolean[span * span], seaB = new boolean[span * span];
        for (int j = 0; j < span; j++) {
            int rj = span - 1 - j;
            for (int i = 0; i < span; i++) {
                waterB[rj * span + i] = waterA[j * span + i];
                groundB[rj * span + i] = groundA[j * span + i];
                iceB[rj * span + i] = iceA[j * span + i];
            }
        }

        EarthGenerator.SteppedWater resA = EarthGenerator.drownedGround(
                span, waterA, groundA, iceA, () -> {}, seaA,
                (wi, wj, di, dj) -> groundA[dj * span + di],
                new IntArrayFIFOQueue(), true, 64.0);
        EarthGenerator.SteppedWater resB = EarthGenerator.drownedGround(
                span, waterB, groundB, iceB, () -> {}, seaB,
                (wi, wj, di, dj) -> groundB[dj * span + di],
                new IntArrayFIFOQueue(), true, 64.0);

        assertNotNull(resA.shadow());
        assertNotNull(resB.shadow());
        assertTrue(resA.shadow()[12 * span + 8], "sole steep bottleneck (8,12) must be shadowed");
        for (int i = 9; i <= 11; i++) {
            assertTrue(resA.shadow()[12 * span + i],
                    "0.22-grade branch cell (" + i + ",12) off sole bottleneck must be shadowed when glacial core scans first");
            assertTrue(resB.shadow()[12 * span + i],
                    "0.22-grade branch cell (" + i + ",12) off sole bottleneck must be shadowed when non-glacial core scans first");
        }
        for (int j = 0; j < span; j++) {
            int rj = span - 1 - j;
            for (int i = 0; i < span; i++) {
                assertEquals(resA.shadow()[j * span + i], resB.shadow()[rj * span + i],
                        "Phase 2 shadow mask must be strictly invariant under N-S core reordering at (" + i + "," + j + ")");
            }
        }
    }
}
