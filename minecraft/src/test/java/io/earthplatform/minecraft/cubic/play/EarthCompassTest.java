package io.earthplatform.minecraft.cubic.play;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EarthCompassTest {
    @Test void distancesReadAsPeopleSayThem() {
        assertEquals("0 m", EarthCompass.distance(0));
        assertEquals("950 m", EarthCompass.distance(0.95));
        assertEquals("1.0 km", EarthCompass.distance(0.9996));
        assertEquals("65.4 km", EarthCompass.distance(65.43));
        assertEquals("100 km", EarthCompass.distance(99.97));
        assertEquals("8,555 km", EarthCompass.distance(8555.2));
    }

    @Test void unnamedTargetsGoByTheirCoordinates() {
        assertEquals("38.0252° N, 118.9833° W", EarthCompass.label("", 38.0252, -118.9833));
        assertEquals("33.8688° S, 151.2093° E", EarthCompass.label("", -33.8688, 151.2093));
        assertEquals("Mount Fuji, Japan", EarthCompass.label("Mount Fuji, Japan", 35.36, 138.73));
    }
}
