package io.earthplatform.geography.globe;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Isolated photogrammetric spires and pits on sloping terrain are removed while real cliffs, ridges, peaks, and sea stacks stay intact. */
class TileDespikeTest {
    private static final int SIDE = 256;

    private static float[] slopingCraterWall() {
        float[] tile = new float[SIDE * SIDE];
        for (int y = 0; y < SIDE; y++) {
            for (int x = 0; x < SIDE; x++) {
                tile[y * SIDE + x] = 3700f + 4.5f * (x - 128) + 2.0f * (y - 128);
            }
        }
        return tile;
    }

    @Test
    void removesIsolatedPhotogrammetricSpireOnSteepSlope() {
        float[] base = slopingCraterWall();
        float[] tile = base.clone();
        // 3x3 +180 m steam-plume spire with a 1-pixel skirt on a ~30 deg volcano flank.
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int idx = (120 + dy) * SIDE + (120 + dx);
                float bump = Math.abs(dx) <= 1 && Math.abs(dy) <= 1 ? 180f : 65f;
                tile[idx] += bump;
            }
        }
        TileDespike.despike(tile, 8.0);
        assertEquals(base[120 * SIDE + 120], tile[120 * SIDE + 120], 15f, "spire center should blend to crater slope");
        assertEquals(base[121 * SIDE + 121], tile[121 * SIDE + 121], 15f, "spire shoulder should blend to crater slope");
        // Distant slope pixels are untouched.
        assertEquals(base[40 * SIDE + 40], tile[40 * SIDE + 40], 1e-4f);
        assertEquals(base[210 * SIDE + 200], tile[210 * SIDE + 200], 1e-4f);
    }

    @Test
    void fillsIsolatedPhotogrammetricPitOnSteepSlope() {
        float[] base = slopingCraterWall();
        float[] tile = base.clone();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                tile[(140 + dy) * SIDE + (140 + dx)] -= 160f;
            }
        }
        TileDespike.despike(tile, 8.0);
        assertEquals(base[140 * SIDE + 140], tile[140 * SIDE + 140], 15f, "pit center should fill to crater slope");
        assertEquals(base[50 * SIDE + 50], tile[50 * SIDE + 50], 1e-4f);
    }

    @Test
    void preservesSheerCliffPlateauEdge() {
        float[] tile = new float[SIDE * SIDE];
        for (int y = 0; y < SIDE; y++) {
            for (int x = 0; x < SIDE; x++) tile[y * SIDE + x] = x >= 128 ? 1250f : 1000f;
        }
        float[] before = tile.clone();
        TileDespike.despike(tile, 8.0);
        assertArrayEquals(before, tile, 1e-4f);
    }

    @Test
    void preservesLongNarrowRidge() {
        float[] tile = new float[SIDE * SIDE];
        java.util.Arrays.fill(tile, 1500f);
        for (int y = 80; y < 160; y++) {
            for (int x = 127; x <= 129; x++) tile[y * SIDE + x] = 1650f;
        }
        float[] before = tile.clone();
        TileDespike.despike(tile, 8.0);
        assertArrayEquals(before, tile, 1e-4f);
    }

    @Test
    void preservesSeaStackSurroundedByOcean() {
        float[] tile = new float[SIDE * SIDE];
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) tile[(128 + dy) * SIDE + (128 + dx)] = 120f;
        }
        float[] before = tile.clone();
        TileDespike.despike(tile, 8.0);
        assertArrayEquals(before, tile, 1e-4f);
    }

    @Test
    void preservesMountainPeakAtCoarsePixelScale() {
        float[] tile = new float[SIDE * SIDE];
        java.util.Arrays.fill(tile, 2000f);
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) tile[(128 + dy) * SIDE + (128 + dx)] = 2180f;
        }
        float[] before = tile.clone();
        // At 100 m/px, a 3x3 feature is 300 m wide: a real summit, not a stereo artifact.
        TileDespike.despike(tile, 100.0);
        assertArrayEquals(before, tile, 1e-4f);
    }

    @Test
    void zoomAndTileYDespikePolarSpire() {
        float[] base = slopingCraterWall();
        float[] tile = base.clone();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) tile[(100 + dy) * SIDE + (100 + dx)] += 180f;
        }
        // Zoom 12 at Mount Erebus latitude (~-77.5 deg): tileY ~= 3592, ~8.2 m/px.
        TileDespike.despike(tile, 12, 3592);
        assertEquals(base[100 * SIDE + 100], tile[100 * SIDE + 100], 15f);
    }

    @Test
    void v2PreservesRidgeButtressProtrudingAtTileBorderWhileCleaningInteriorSpike() {
        float[] base = slopingCraterWall();
        float[] tile = base.clone();
        // Steep ridge buttress protruding 3 pixels across the western tile border (x = 0..2, y = 120..124).
        for (int y = 120; y <= 124; y++) {
            for (int x = 0; x <= 2; x++) {
                tile[y * SIDE + x] += 180f;
            }
        }
        // Extreme isolated interior photogrammetric spike (3x3, +180 m) at (150, 150).
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                tile[(150 + dy) * SIDE + (150 + dx)] += 180f;
            }
        }
        float[] v1 = tile.clone();
        TileDespike.despike(v1, 8.0, false);
        // v1 slices the border-protruding ridge, creating a straight wall at x = 0.
        assertTrue(v1[122 * SIDE + 1] < base[122 * SIDE + 1] + 50f, "v1 legacy behavior slices border protrusion");

        float[] v2 = tile.clone();
        TileDespike.despike(v2, 8.0, true);
        // v2 preserves the entire border-protruding ridge intact.
        for (int y = 120; y <= 124; y++) {
            for (int x = 0; x <= 2; x++) {
                assertEquals(tile[y * SIDE + x], v2[y * SIDE + x], 1e-4f,
                        "v2 must preserve border ridge at (" + x + "," + y + ")");
            }
        }
        // v2 still cleans the extreme isolated interior spike.
        assertEquals(base[150 * SIDE + 150], v2[150 * SIDE + 150], 15f, "v2 must still remove interior DEM spike");
    }

    @Test
    void v2PreservesNaturalRockSpireWhileCleaningSinglePixelDemSpike() {
        float[] base = slopingCraterWall();
        float[] tile = base.clone();
        // Genuine narrow 24 m wide, 85 m tall rock pinnacle/buttress at 4 m/px (6x6 px).
        for (int dy = 0; dy < 6; dy++) {
            for (int dx = 0; dx < 6; dx++) {
                tile[(80 + dy) * SIDE + (80 + dx)] += 85f;
            }
        }
        // Single-pixel +140 m DEM spike at (180, 180).
        tile[180 * SIDE + 180] += 140f;

        float[] v2 = tile.clone();
        TileDespike.despike(v2, 4.0, true);
        assertEquals(tile[83 * SIDE + 83], v2[83 * SIDE + 83], 1e-4f, "v2 preserves genuine 85 m rock spire");
        assertEquals(base[180 * SIDE + 180], v2[180 * SIDE + 180], 15f, "v2 cleans isolated single-pixel +140 m DEM spike");
    }

    @Test
    void v2UniformlyRemovesFlatLandBuildingsAtTileBorderWithoutStubsOrHalfSlicing() {
        float[] tile = new float[SIDE * SIDE];
        java.util.Arrays.fill(tile, 100f);
        // 2-pixel flat-land building flush against western border (x = 0..1, y = 60).
        tile[60 * SIDE + 0] = 160f;
        tile[60 * SIDE + 1] = 160f;
        // 3-pixel flat-land building spanning x = 0..2, y = 120 (previously half-sliced to 280, 280, 100).
        tile[120 * SIDE + 0] = 280f;
        tile[120 * SIDE + 1] = 280f;
        tile[120 * SIDE + 2] = 280f;

        float[] v2 = tile.clone();
        TileDespike.despike(v2, 8.0, true);
        assertEquals(100f, v2[60 * SIDE + 0], 1e-4f, "v2 must remove 2-px border building at x=0");
        assertEquals(100f, v2[60 * SIDE + 1], 1e-4f, "v2 must remove 2-px border building at x=1");
        assertEquals(100f, v2[120 * SIDE + 0], 1e-4f, "v2 must uniformly remove 3-px border building at x=0");
        assertEquals(100f, v2[120 * SIDE + 1], 1e-4f, "v2 must uniformly remove 3-px border building at x=1");
        assertEquals(100f, v2[120 * SIDE + 2], 1e-4f, "v2 must uniformly remove 3-px border building at x=2");
    }
}
