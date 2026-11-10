package io.earthplatform.minecraft.cubic.play;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class EarthTownsTest {
    @Test void theListIsBundled() { assertTrue(EarthTowns.count() > 100_000); }

    @Test void theBiggestTownOfANameComesFirst() {
        List<EarthTowns.Town> found = EarthTowns.search("paris", 5);
        assertEquals("Paris", found.get(0).name());
        assertTrue(found.get(0).region().endsWith("France"), found.get(0).region());
        assertEquals(48.85, found.get(0).latitude(), 0.05);
        assertEquals(2.35, found.get(0).longitude(), 0.05);
    }

    @Test void aStateOrCountryAfterACommaNarrowsIt() {
        EarthTowns.Town texas = EarthTowns.search("Paris, Texas", 1).get(0);
        assertEquals(33.66, texas.latitude(), 0.05);
        assertTrue(EarthTowns.search("Springfield, Illinois", 1).get(0).region().startsWith("Illinois"));
    }

    @Test void accentsCaseAndSpacesDoNotCount() {
        assertEquals("Zürich", EarthTowns.search("zurich", 1).get(0).name());
        assertEquals("São Paulo", EarthTowns.search("SAO PAULO", 1).get(0).name());
        assertEquals("New York City", EarthTowns.search("newyork", 1).get(0).name());
    }

    @Test void exactNamesBeatLongerOnesAndNothingMatchesNonsense() {
        assertEquals("York", EarthTowns.search("York", 3).get(0).name());
        assertTrue(EarthTowns.search("qzxjv", 5).isEmpty());
        assertTrue(EarthTowns.search("  ", 5).isEmpty());
        assertTrue(EarthTowns.search("a", 3).size() <= 3);
    }

    @Test void theNearestTownToAPoint() {
        EarthTowns.Town town = EarthTowns.nearest(46.02, 7.75, 40);
        assertNotNull(town);
        assertEquals("Zermatt", town.name());
        assertNull(EarthTowns.nearest(-48, -123, 40), "the middle of the South Pacific");
        assertFalse(town.label().isEmpty());
    }
}
