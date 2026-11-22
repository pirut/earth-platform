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
}
