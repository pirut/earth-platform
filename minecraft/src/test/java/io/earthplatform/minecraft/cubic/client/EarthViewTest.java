package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EarthViewTest {
    @Test void waterGridKeepsTheCurvesSagUnderFiveCentimetres() {
        float curve = EarthView.curvature(8000, 1, false);
        int size = EarthView.WATER_GRID;
        for (int x : new int[] {2048, 60000, 90000}) {
            double chord = (EarthView.drop(x, 3000, curve) + EarthView.drop(x + size, 3000 + size, curve)) * .5;
            double surface = EarthView.drop(x + size * .5, 3000 + size * .5, curve);
            assertTrue(chord - surface >= 0 && chord - surface < .05, "sag " + (chord - surface) + " at " + x);
        }
    }

    @Test void nearbyGroundStaysFlatAndHighUpTheDropIsTheEarths() {
        float curve = EarthView.curvature(8000, 1, false);
        assertEquals(0, EarthView.drop(1200, 1500, curve));
        assertEquals(0, EarthView.curvature(1500, 1, false));
        assertEquals(0, EarthView.curvature(8000, 1, true), "a shader pack's water and positions would part from far terrain's");
        assertEquals(0, EarthView.curvature(Double.NaN, 1, false));
        assertEquals(0, EarthView.curvature(8000, 0, false));
        assertEquals(EarthView.full(1), curve);
        assertEquals(50000.0 * 50000 / (2 * EarthView.RADIUS), EarthView.drop(52000, 0, curve), .001);
        assertEquals(EarthView.drop(52000, 0, curve), EarthView.drop(0, -52000, curve));
    }

    /** #161 divided by blocksPerMetre squared, which drew the curve too flat wherever a block isn't a metre. */
    @Test void theDropIsInMetresWhereABlockIsNotAMetre() {
        // Two blocks to the metre: 50 km is 100,000 blocks, and the 196 m drop is 392 blocks.
        float curve = EarthView.curvature(8000, 2, false);
        double metres = 50000.0 * 50000 / (2 * EarthView.RADIUS);
        assertEquals(metres * 2, EarthView.drop(100_000 + EarthView.FLAT, 0, curve), .01);
        assertEquals(EarthView.curvature(8000, 1, false) / 2, curve, 1e-14);
    }

    @Test void theCurveGrowsSmoothlyWithHeight() {
        float before = 0;
        for (int height = 0; height <= 8000; height += 10) {
            float curve = EarthView.curvature(height, 1, false);
            assertTrue(curve >= before && curve <= EarthView.full(1));
            before = curve;
        }
        assertEquals(EarthView.full(1) / 2, EarthView.curvature(3750, 1, false), 1e-12);
    }
}
