package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.*;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class EarthViewTest {
    @Test void waterGridKeepsCurvatureChordErrorBelowFiveCentimetres() {
        float curve = EarthView.curvature(8000, 1, false);
        int size = EarthView.WATER_GRID;
        for (int x : new int[] {2048, 60000, 90000}) {
            double chord = (EarthView.drop(x, 3000, curve) + EarthView.drop(x + size, 3000 + size, curve)) * .5;
            double surface = EarthView.drop(x + size * .5, 3000 + size * .5, curve);
            assertTrue(chord - surface >= 0 && chord - surface < .05);
        }
    }

    @Test void curvatureKeepsNearbyBuildsFlatAndHasRealScaleAtCruiseAltitude() {
        float curve = EarthView.curvature(8000, 1, false);
        assertEquals(0, EarthView.drop(1200, 1500, curve));
        assertEquals(0, EarthView.curvature(1500, 1, false));
        assertEquals(0, EarthView.curvature(8000, 1, true), "shader-pack projections stay consistent with their water");
        assertEquals(0, EarthView.curvature(Double.NaN, 1, false));
        assertEquals(50000.0 * 50000 / (2 * EarthView.RADIUS), EarthView.drop(52000, 0, curve), .001);
        assertEquals(EarthView.drop(52000, 0, curve), EarthView.drop(0, -52000, curve));
        assertEquals(curve / 4, EarthView.curvature(8000, 2, false), 1e-14);
    }

    @Test void waterTangentMeetsTheGroundWarpAtEachColumnCentre() {
        float curve = EarthView.curvature(8000, 1, false);
        for (int x : new int[] {0, 2000, 10000, 90000}) {
            Matrix4f view = EarthView.water(new Matrix4f(), x, 3000, curve);
            Vector3f warped = view.transformPosition(new Vector3f(x, 63, 3000));
            assertEquals(63 - EarthView.drop(x, 3000, curve), warped.y, .001);
            assertEquals(x, warped.x); assertEquals(3000, warped.z);
        }
    }
}
