package io.earthplatform.minecraft.cubic.play.client;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class EarthCloudsTest {
    @Test void densityTilesWithoutSeamsAndHasSoftGapsAndDenseBanks() {
        for (int kind = 0; kind < 3; kind++) {
            double low = 1, high = 0;
            for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) {
                double u = x / 64.0, v = y / 64.0, d = CloudField.density(kind, u, v);
                assertTrue(d >= 0 && d <= 1); low = Math.min(low, d); high = Math.max(high, d);
                assertEquals(d, CloudField.density(kind, u + 1, v - 1), 1e-12);
            }
            assertTrue(low < .1); assertTrue(high > .8);
            assertEquals(CloudField.density(kind, 1e-8, .37), CloudField.density(kind, 1 - 1e-8, .37), 1e-5);
        }
    }

    @Test void bandsHaveSeparateAltitudesThicknessAndIndependentWind() {
        var bands = CloudField.bands(2500);
        assertEquals(3, bands.length);
        assertTrue(bands[0].height() + bands[0].thickness() < bands[1].height());
        assertTrue(bands[1].height() + bands[1].thickness() < bands[2].height());
        assertNotEquals(bands[0].wind(), bands[1].wind());
        assertEquals(3388, bands[1].height() - bands[1].thickness() / 2);
        assertEquals(4238, bands[1].height() + bands[1].thickness() / 2);
    }
}
