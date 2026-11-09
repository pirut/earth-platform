package io.earthplatform.minecraft.cubic.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.earthplatform.geography.GeoPoint;
import io.earthplatform.geography.globe.MercatorGrid;
import io.earthplatform.minecraft.cubic.earth.EarthSettings;
import org.junit.jupiter.api.Test;

class EarthStretchTest {
    @Test void trueAtHomeAndWiderTowardThePoles() {
        EarthSettings yosemite = EarthSettings.at(37.724, -119.6365);
        assertEquals(1.0, EarthStretch.at(yosemite.metresPerBlock(), 37.724), 1e-9);
        assertTrue(EarthStretch.close(EarthStretch.at(yosemite.metresPerBlock(), 38.5)));
        // Denali from a Yosemite home: about 1.75 times as wide as in life.
        assertEquals(1.75, EarthStretch.at(yosemite.metresPerBlock(), 63.07), 0.01);
        assertEquals(0.79, EarthStretch.at(yosemite.metresPerBlock(), 0), 0.005);
        assertEquals(EarthStretch.at(yosemite.metresPerBlock(), 63.07), EarthStretch.from(37.724, 63.07), 1e-9);
    }

    /** The stretch is the grid's own: blocks per ground meter where the generator puts the land. */
    @Test void matchesTheGrid() {
        EarthSettings home = EarthSettings.at(-33.86, 151.21);
        MercatorGrid grid = new MercatorGrid(home.metresPerBlock());
        for (double latitude : new double[] {-70, -33.86, 0, 45, 80}) {
            double z = grid.blockZ(new GeoPoint(latitude, 10));
            assertEquals(1.0 / grid.groundMetresPerBlock(z), EarthStretch.at(home.metresPerBlock(), latitude), 1e-9);
        }
    }

    @Test void whereSaysSo() {
        EarthSettings yosemite = EarthSettings.at(37.724, -119.6365);
        assertTrue(EarthStretch.where(yosemite.metresPerBlock(), 37.7).contains("true to size"));
        String denali = EarthStretch.where(yosemite.metresPerBlock(), 63.07);
        assertTrue(denali.contains("1.75 times as wide") && denali.contains("0.57 m"), denali);
    }
}
