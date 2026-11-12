package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.*;

import io.earthplatform.minecraft.cubic.far.Far;
import org.junit.jupiter.api.Test;

class FarFlightDetailTest {
    @Test void lowFlightKeepsTheFinestFarVoxelsAtEverySharpness() {
        try {
            for (double scale : new double[] {0.7, 1, 2, 4}) {
                Far.sharpness(scale);
                assertEquals(Far.MIN_LEVEL, FarRenderer.finest(100, 0, Far.MIN_LEVEL));
                assertEquals(Far.MIN_LEVEL, FarRenderer.finest(100, 0, 6), "descent restores detail immediately");
                assertEquals(Far.MIN_LEVEL, FarRenderer.finest(-30, 0, Far.MIN_LEVEL));
            }
        } finally { Far.sharpness(1); }
    }

    @Test void altitudeTransitionsHaveHysteresisAndRemainBounded() {
        try {
            Far.sharpness(1);
            double threshold = (double) Far.hole() * Far.width(Far.MIN_LEVEL);
            assertEquals(1, FarRenderer.finest(threshold - 1, 0, 1));
            assertEquals(2, FarRenderer.finest(threshold + 1, 0, 1));
            assertEquals(2, FarRenderer.finest(threshold * 0.9, 0, 2));
            assertEquals(1, FarRenderer.finest(threshold * 0.79, 0, 2));
            assertEquals(Far.MAX_LEVEL, FarRenderer.finest(1e9, 0, 1));
        } finally { Far.sharpness(1); }
    }
}
