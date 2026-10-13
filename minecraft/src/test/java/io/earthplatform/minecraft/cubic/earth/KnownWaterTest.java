package io.earthplatform.minecraft.cubic.earth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.globe.MercatorGrid;
import org.junit.jupiter.api.Test;

class KnownWaterTest {
    private final MercatorGrid grid = new MercatorGrid(EarthSettings.at(37.724, -119.6365).metresPerBlock());
    private final KnownWater known = new KnownWater(grid);

    private KnownWater.Water at(double latitude, double longitude, int voxel) {
        GeoPoint point = new GeoPoint(latitude, longitude);
        return known.at(grid.blockX(point), grid.blockZ(point), voxel);
    }

    @Test void victoriaFallsFirstGorgeLiesUnderTheLip() {
        KnownWater.Water gorge = at(-17.92443, 25.85272, 1);
        assertNotNull(gorge);
        assertEquals(765, gorge.level(), 1e-9);
        assertTrue(gorge.ground() < gorge.level());
        assertFalse(gorge.wetOnly());
    }

    @Test void gorgeWallsClimbAwayFromTheWater() {
        // A little north of the First Gorge, on the lip: only walls, rising with distance.
        KnownWater.Water near = at(-17.92400, 25.85272, 1), far = at(-17.92370, 25.85272, 1);
        assertNotNull(near);
        assertNotNull(far);
        assertTrue(Double.isNaN(near.level()) && Double.isNaN(far.level()));
        assertTrue(far.ground() > near.ground(), near.ground() + " then " + far.ground());
    }

    @Test void narrowCoursesStayOutOfCoarseFarTerrain() {
        assertNull(at(-17.92443, 25.85272, 8));
    }

    @Test void theNiagaraAboveTheHorseshoeStandsOverItsGorge() {
        KnownWater.Water above = at(43.07700, -79.07400, 1), below = at(43.08425, -79.07410, 1);
        assertNotNull(above);
        assertNotNull(below);
        assertTrue(above.level() - below.level() > 50, above.level() + " over " + below.level());
        assertTrue(above.wetOnly() && below.wetOnly());
    }

    @Test void nothingIsKnownElsewhere() {
        assertNull(at(36.1, -112.1, 1));
        assertNull(at(48.85, 2.35, 1));
    }
}
