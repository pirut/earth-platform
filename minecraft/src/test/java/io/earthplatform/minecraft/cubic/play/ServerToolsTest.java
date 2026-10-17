package io.earthplatform.minecraft.cubic.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.earthplatform.geography.GeoPoint;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

class ServerToolsTest {
    @Test void countriesAreFoundByPoint() {
        assertTrue(EarthCountries.all().size() > 200);
        assertEquals("France", EarthCountries.at(48.8566, 2.3522).name());
        assertEquals("Japan", EarthCountries.at(35.6762, 139.6503).name());
        assertEquals("Peru", EarthCountries.at(-13.1631, -72.5450).name());
        assertEquals("United States of America", EarthCountries.at(37.7459, -119.5332).name());
        assertNull(EarthCountries.at(30, -40), "the middle of the Atlantic");
    }

    @Test void holesInCountriesStayOut() {
        assertEquals("Lesotho", EarthCountries.at(-29.6, 28.2).name());
        assertEquals("South Africa", EarthCountries.at(-26.2, 28.0).name());
    }

    @Test void countriesAreFoundByName() {
        assertEquals("Switzerland", EarthCountries.find("switzerland").orElseThrow().name());
        assertEquals("New Zealand", EarthCountries.find("New Zeal").orElseThrow().name());
        assertTrue(EarthCountries.find("xyzzy").isEmpty());
    }

    @Test void randomPointsStayInTheirLatitudes() {
        RandomSource random = RandomSource.create(1);
        for (int i = 0; i < 10_000; i++) {
            double[] point = EarthCommands.anywhere(random);
            assertTrue(point[0] >= EarthCommands.RANDOM_SOUTH && point[0] <= EarthCommands.RANDOM_NORTH);
            assertTrue(point[1] >= -180 && point[1] < 180);
        }
    }

    @Test void nearbyPointsStayWithinTheirDistance() {
        RandomSource random = RandomSource.create(2);
        GeoPoint centre = new GeoPoint(46.5, 7.9);
        for (int i = 0; i < 10_000; i++) {
            double[] point = EarthCommands.near(random, centre, 50);
            assertTrue(EarthRules.kilometers(centre.latitude(), centre.longitude(), point[0], point[1]) <= 50.5);
        }
        // Across the date line the longitude wraps.
        double[] wrapped = EarthCommands.near(RandomSource.create(3), new GeoPoint(-17, 179.99), 100);
        assertTrue(wrapped[1] >= -180 && wrapped[1] < 180);
    }

    @Test void bordersHoldTheirBoxAndCountry() {
        EarthSpawns.Border box = new EarthSpawns.Border(40, -10, 50, 10, "");
        assertTrue(box.contains(45, 0));
        assertFalse(box.contains(39, 0));
        EarthCountries.Country swiss = EarthCountries.find("Switzerland").orElseThrow();
        EarthSpawns.Border country = new EarthSpawns.Border(swiss.south(), swiss.west(), swiss.north(), swiss.east(), swiss.name());
        assertTrue(country.contains(46.95, 7.45), "Bern");
        assertFalse(country.contains(47.9, 7.8), "Freiburg, Germany, inside Switzerland's box");
        RandomSource random = RandomSource.create(4);
        for (int i = 0; i < 1000; i++) {
            double[] point = EarthCommands.within(random, box);
            assertTrue(box.contains(point[0], point[1]));
        }
    }

    @Test void distancesAndHeadings() {
        assertEquals(343.5, EarthRules.kilometers(51.5074, -0.1278, 48.8566, 2.3522), 2, "London to Paris");
        assertEquals("southeast", EarthRules.heading(51.5074, -0.1278, 48.8566, 2.3522));
        assertEquals("north", EarthRules.heading(0, 0, 10, 0));
        assertEquals("west", EarthRules.heading(0, 10, 0, 0));
    }

    @Test void guessesScoreLikeGeoGuessr() {
        assertEquals(5000, EarthGuess.points(0));
        assertTrue(EarthGuess.points(1400) > 2400 && EarthGuess.points(1400) < 2600);
        assertTrue(EarthGuess.points(20_000) < 1);
    }
}
