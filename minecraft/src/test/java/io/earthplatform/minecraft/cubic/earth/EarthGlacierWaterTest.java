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

        // Diagonal-only fallback when no cardinal wet neighbour exists.
        water[1 * span + 1] = 100f;
        water[2 * span + 2] = 164f;
        assertEquals(64.0 / (64.0 * Math.sqrt(2.0)), EarthGenerator.waterSlope(water, span, 1 * span + 1, 64.0), 1e-9,
                "diagonal-only wet neighbour must divide by spacingMetres * sqrt(2)");

        // Isolated wet sample and dry sample return NaN.
        water[0 * span + 7] = 50f;
        assertTrue(Double.isNaN(EarthGenerator.waterSlope(water, span, 0 * span + 7, 64.0)),
                "isolated wet sample with no wet neighbours must return NaN");
        assertTrue(Double.isNaN(EarthGenerator.waterSlope(water, span, 0 * span + 0, 64.0)),
                "dry sample must return NaN");
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
        for (int j = 16; j <= 18; j++) {
            for (int i = 9; i <= 11; i++) {
                assertFalse(result.shadow()[j * SPAN + i],
                        "connected 5x5 flat pool interior (" + i + "," + j + ") must remain unmarked");
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
        // 5x5 flat lake (rows 7..11, cols 12..16, water = 100) AND to a 5x5 gently-sloping wide body
        // (rows 1..5, cols 12..16, water = 500 - (i - 12) * 14 -> 0.21875 slope, wideWater interior).
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
                water[at] = 500f - (i - 12) * 14f;
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
        // Branch C: neither the 8-connected flat lake nor the wideWater interior of the sloping wide body is flooded.
        for (int j = 8; j <= 10; j++) {
            for (int i = 13; i <= 15; i++) {
                assertFalse(result.shadow()[j * span + i],
                        "8-connected flat lake interior (" + i + "," + j + ") must remain unshadowed");
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
}
