package io.earthplatform.geography.globe;

import io.earthplatform.geography.GeoPoint;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Dgm5ElevationTest {
    private final Dgm5Elevation layer = new Dgm5Elevation();

    @Test void theSurveyRaisesTheHelgolandCliffToItsMeasuredHeight() {
        GeoPoint cliff = new GeoPoint(54.1868709, 7.872419);
        MercatorGrid grid = new MercatorGrid(1);
        GlobalElevation elevation = new GlobalElevation(grid, (zoom, x, y) -> {
            float[] base = new float[256 * 256];
            Arrays.fill(base, 12);
            layer.refine(zoom, x, y, base);
            return base;
        }, 15);
        double metres = elevation.metres(grid.blockX(cliff), grid.blockZ(cliff));
        assertEquals(52.13, metres, 3.0);
    }

    @Test void leavesUncoveredTilesAlone() {
        float[] base = new float[256 * 256];
        Arrays.fill(base, -5.0f);
        layer.refine(15, 0, 0, base);
        for (float h : base) assertTrue(h == -5.0f);
    }
}
