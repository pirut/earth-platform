package io.earthplatform.geography.globe;

import io.earthplatform.geography.GeoPoint;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Dgm5ElevationTest {
    private final Dgm5Elevation layer = new Dgm5Elevation();

    @Test void theSurveyRaisesTheHelgolandCliffToItsMeasuredHeight() {
        GeoPoint cliff = new GeoPoint(54.1866912, 7.8724239);
        MercatorGrid grid = new MercatorGrid(1);
        GlobalElevation elevation = new GlobalElevation(grid, (zoom, x, y) -> {
            float[] base = new float[256 * 256];
            Arrays.fill(base, 12);
            layer.refine(zoom, x, y, base);
            return base;
        }, 15);
        double metres = elevation.metres(grid.blockX(cliff), grid.blockZ(cliff));
        assertEquals(54.66, metres, 1.0);
    }

    @Test void preservesSurveyedGroundOverTheTownCorrection() {
        float[] baseline = new float[256 * 256];
        Arrays.fill(baseline, 12);
        BareEarth ground = new BareEarth((zoom, x, y) -> baseline,
                (latitude, longitude) -> WorldCoverSource.BUILT_UP, 8, 8, null, layer);
        MercatorGrid grid = new MercatorGrid(1.2642747560461984);
        GlobalElevation elevation = new GlobalElevation(grid, ground, 15);
        GeoPoint plateau = new GeoPoint(54.18495, 7.88172);
        GeoPoint cliff = new GeoPoint(54.1866912, 7.8724239);
        assertEquals(37.4, elevation.metres(grid.blockX(plateau) + 0.5, grid.blockZ(plateau) + 0.5), 1.0);
        assertEquals(54.6, elevation.metres(grid.blockX(cliff) + 0.5, grid.blockZ(cliff) + 0.5), 1.0);
        assertSame(baseline, ground.tile(15, 0, 0));
        for (float metres : baseline) assertEquals(12, metres);
    }

    @Test void leavesUncoveredTilesAlone() {
        float[] base = new float[256 * 256];
        Arrays.fill(base, -5.0f);
        layer.refine(15, 0, 0, base);
        for (float h : base) assertTrue(h == -5.0f);
    }
}
