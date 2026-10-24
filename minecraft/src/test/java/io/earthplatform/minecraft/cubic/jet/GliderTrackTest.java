package io.earthplatform.minecraft.cubic.jet;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GliderTrackTest {
    @Test void stoppingDeadAtJetSpeedIsFatal() {
        GliderTrack track = new GliderTrack();
        double x = 0;
        for (long t = 0; t < 10; t++) track.at(t, x -= 72, 0);
        assertTrue(track.crash() <= 0, "cruising is no crash");
        for (long t = 10; t < 13; t++) track.at(t, x, 0);
        assertTrue(track.crash() > 500, "hit a wall: " + track.crash());
    }

    @Test void unevenPacketsAreNoCrash() {
        GliderTrack track = new GliderTrack();
        double x = 0;
        // Moves arrive in bursts: nothing one tick, two ticks' worth the next.
        for (long t = 0; t < 30; t++) {
            if (t % 2 == 1) x -= 144;
            track.at(t, x, 0);
            assertTrue(track.crash() < 20, "tick " + t + ": " + track.crash());
        }
    }

    @Test void aTeleportStartsOver() {
        GliderTrack track = new GliderTrack();
        for (long t = 0; t < 10; t++) track.at(t, -72.0 * t, 0);
        track.at(10, 5_000_000, 0);
        track.at(11, 5_000_000, 0);
        track.at(12, 5_000_000, 0);
        assertEquals(0.0F, track.crash());
    }
}
