package io.earthplatform.minecraft.cubic.play.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CloudFieldTest {
    @Test void densityTilesWithoutSeamsAndHasSoftGapsAndDenseBanks() {
        for (int kind = 0; kind < 3; kind++) {
            double low = 1, high = 0;
            for (int y = 0; y < 64; y++)
                for (int x = 0; x < 64; x++) {
                    double u = x / 64.0, v = y / 64.0, d = CloudField.density(kind, u, v);
                    assertTrue(d >= 0 && d <= 1);
                    low = Math.min(low, d);
                    high = Math.max(high, d);
                    assertEquals(d, CloudField.density(kind, u + 1, v - 1), 1e-12);
                }
            assertTrue(low < .1);
            assertTrue(high > .8);
            assertEquals(CloudField.density(kind, 1e-8, .37), CloudField.density(kind, 1 - 1e-8, .37), 1e-5);
        }
    }

    @Test void bandsHaveSeparateAltitudesThicknessAndIndependentWind() {
        var bands = CloudField.bands(2500);
        assertEquals(3, bands.length);
        assertTrue(bands[0].height() + bands[0].thickness() < bands[1].height());
        assertTrue(bands[1].height() + bands[1].thickness() < bands[2].height());
        assertNotEquals(bands[0].wind(), bands[1].wind());
        // The middle bank at the default height is the Y 3400-4200 range players reported as a whiteout.
        assertEquals(3388, bands[1].height() - bands[1].thickness() / 2);
        assertEquals(4238, bands[1].height() + bands[1].thickness() / 2);
    }

    /** At low cloud heights the fixed thicknesses used to stack the lower two banks into each other and push the lowest under the sea. */
    @Test void banksNeverOverlapOrReachTheSeaAtAnyHeight() {
        for (int height = 100; height <= 9000; height += 100) {
            var bands = CloudField.bands(height);
            assertTrue(bands[0].height() - bands[0].thickness() / 2 > 63, "lowest bank under the sea at " + height);
            for (int i = 0; i + 1 < bands.length; i++)
                assertTrue(bands[i].height() + bands[i].thickness() / 2 < bands[i + 1].height() - bands[i + 1].thickness() / 2,
                        "banks " + i + " and " + (i + 1) + " overlap at " + height);
        }
    }

    @Test void defaultBanksStayBelowTheHighestPeaks() {
        for (var band : CloudField.bands(2500)) assertTrue(band.height() + band.thickness() / 2 < 8912);
    }
}
