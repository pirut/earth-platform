package io.earthplatform.minecraft.cubic.earth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.earthplatform.geography.GeoPoint;
import org.junit.jupiter.api.Test;

/** The bundled maps and ecoregion realms read what the world is like at places everyone knows. */
class EarthMapsTest {
    @Test void rockFollowsTheLithologicalMap() {
        EarthMaps maps = EarthMaps.get();
        assertNotNull(maps, "the maps ship in the jar");
        assertTrue(EarthMaps.volcanic(maps.rock(19.35, -155.2)), "Kilauea is lava");
        assertTrue(EarthMaps.volcanic(maps.rock(65.0, -17.0)), "Iceland's interior is lava");
        assertTrue(EarthMaps.granitic(maps.rock(37.746, -119.533)), "Half Dome is granite");
        assertFalse(EarthMaps.volcanic(maps.rock(48.3, 8.2)), "the Black Forest is not lava");
        assertEquals(EarthMaps.NO_ROCK, maps.rock(0, -30), "the open Atlantic has no rock");
    }

    @Test void redGroundFollowsTheViewFromOrbit() {
        EarthMaps maps = EarthMaps.get();
        assertTrue(maps.red(-25.345, 131.035) > 0.7, "Uluru stands in red country: " + maps.red(-25.345, 131.035));
        assertTrue(maps.red(37.0, -110.1) > 0.45, "Monument Valley is red: " + maps.red(37.0, -110.1));
        assertTrue(maps.red(25.0, 25.0) < 0.45, "the Libyan Desert is tan: " + maps.red(25.0, 25.0));
        assertTrue(maps.red(64.0, 27.0) < 0.1, "Finland is not red: " + maps.red(64.0, 27.0));
    }

    @Test void soilCarbonIsHighInTheNorthAndLowInDeserts() {
        EarthMaps maps = EarthMaps.get();
        assertTrue(maps.carbon(64.0, 27.0) > 2 * maps.carbon(48.3, 8.2), "Finnish taiga holds more humus than the Black Forest");
        assertTrue(maps.carbon(25.0, 25.0) < 0.05, "the Sahara holds next to none");
    }

    @Test void ecoregionsKnowTheirRealms() {
        Ecoregions eco = Ecoregions.get();
        assertNotNull(eco);
        assertEquals(Ecoregions.NEARCTIC, eco.realm(eco.ecoregion(36.56, -118.75)), "the Sierra Nevada");
        assertEquals(Ecoregions.NEOTROPIC, eco.realm(eco.ecoregion(-4.6, -63.2)), "the Amazon");
        assertEquals(Ecoregions.PALEARCTIC, eco.realm(eco.ecoregion(34.36, 135.86)), "Japan");
        assertEquals(Ecoregions.INDOMALAYAN, eco.realm(eco.ecoregion(4.95, 117.8)), "Borneo");
        assertEquals(Ecoregions.AFROTROPIC, eco.realm(eco.ecoregion(-24.7, 15.4)), "the Namib");
        assertEquals(Ecoregions.AUSTRALASIA, eco.realm(eco.ecoregion(-25.345, 131.035)), "Australia");
        assertEquals(Ecoregions.NO_REALM, eco.realm(0));
    }

    @Test void oldWorldsKeepTheirBoxes() {
        assertTrue(OldRegions.outback(new GeoPoint(-25.345, 131.035)));
        assertTrue(OldRegions.grove(new GeoPoint(36.5700, -118.7600), 2000), "Giant Forest");
        assertFalse(OldRegions.grove(new GeoPoint(36.5700, -118.7600), 800), "below the sequoia belt");
        assertTrue(OldRegions.lava(new GeoPoint(19.35, -155.2)));
        assertFalse(OldRegions.americas(new GeoPoint(25.0, 25.0)));
    }
}
