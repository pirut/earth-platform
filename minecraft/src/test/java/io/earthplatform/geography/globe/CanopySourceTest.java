package io.earthplatform.geography.globe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The canopy height map's file names and what it answers without a network. */
class CanopySourceTest {
    @Test void filesAreNamedByQuadkey() {
        // Zoom 7 tiles (x, y) as y << 7 | x: the Sierra Nevada's sequoia groves and the middle of the Amazon.
        assertEquals("0230121.tif", CanopySource.name(50 << 7 | 21));
        assertEquals("2101003.tif", CanopySource.name(65 << 7 | 41));
        assertEquals("0000000.tif", CanopySource.name(0));
        assertEquals("3333333.tif", CanopySource.name(127 << 7 | 127));
    }

    @Test void placesWithoutAFileHaveNoReading(@TempDir Path cache) throws Exception {
        // The source remembers a file the bucket does not have, as over open ocean, and answers without asking again.
        Files.write(cache.resolve("0230121.tif.none"), new byte[0]);
        try (CanopySource canopy = new CanopySource(CanopySource.AWS_CANOPY, cache, false, 4, 1, Duration.ofSeconds(1))) {
            assertEquals(CanopySource.UNKNOWN, canopy.metres(36.5647, -118.7515, 1));
            assertEquals(CanopySource.UNKNOWN, canopy.metres(89.0, 0, 1), "past the map's northern edge");
            assertThrows(RuntimeException.class, () -> canopy.metres(-4.6, -63.2, 1), "offline, and not in the cache");
            assertThrows(IllegalArgumentException.class, () -> canopy.metres(0, 0, 7));
        }
    }
}
