package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CubicWeatherTest {
    @Test
    void mildBiomesNeverFreezeOrSnowAtAnyHeight() {
        // river 0.5, plains 0.8: warm enough to rain, so no freezing and no snow, at Y 500 or 2000 alike
        for (float base : new float[] {0.5F, 0.8F, 0.15F}) {
            assertTrue(CubicWeather.warmEnough(base), "base " + base);
        }
    }

    @Test
    void coldBiomesAlwaysFreezeAndSnow() {
        for (float base : new float[] {0.0F, -0.5F, 0.149F}) {
            assertFalse(CubicWeather.warmEnough(base), "base " + base);
        }
    }

    @Test
    void nonCubicLevelsKeepVanillasAnswer() {
        // A reader that is not a Level (world generation) or not a cubic one never changes the result.
        assertTrue(CubicWeather.warmEnoughToRain(null, null, true));
        assertFalse(CubicWeather.warmEnoughToRain(null, null, false));
    }

    @Test
    void frozenOceanWarmPatchesStayWarm() {
        // frozen_ocean has base 0.0 so it is cold, but vanilla's FROZEN modifier warms some patches
        // (vanilla == true). Those keep true; a cold patch (vanilla == false) stays false.
        assertFalse(CubicWeather.warmEnough(0.0F));
        assertTrue(CubicWeather.warmEnoughToRain(null, null, true));
        assertFalse(CubicWeather.warmEnoughToRain(null, null, false));
    }
}
