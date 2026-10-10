package io.earthplatform.minecraft;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EarthWindowHudTest {
    // Six pixels per ASCII character is a conservative layout fixture, not a screenshot/font test.
    private static int width(String text) { return text.length() * 6; }

    @Test void fitsTheReported1178By814AtGuiScaleThreeAndClearsTutorialToast() {
        var label = EarthWindowHud.layout(393, 272, 9, EarthWindowHudTest::width, 141, 8352, true);
        assertEquals("Earth Y +8493 m | engine 141 | origin +8352", label.coordinates());
        assertEquals("NOAA 15\" | heuristic cover | 256 m window", label.source());
        assertTrue(label.x() + label.width() <= 388);
        assertTrue(label.y() > 32);
        assertTrue(label.y() + label.height() <= 267);
        assertTrue(width(label.coordinates()) <= label.width() - 10);
        assertTrue(width(label.source()) <= label.width() - 10);
    }

    @Test void narrowerWindowsShortenDiagnosticsRatherThanDrawingPastTheEdge() {
        for (int screenWidth : new int[]{320, 260, 220, 180, 100, 20}) {
            var label = EarthWindowHud.layout(screenWidth, 240, 9, EarthWindowHudTest::width, 143, -10656, true);
            assertTrue(label.x() + label.width() <= screenWidth - 5);
            assertTrue(width(label.coordinates()) <= Math.max(0, label.width() - 10));
            assertTrue(width(label.source()) <= Math.max(0, label.width() - 10));
            if (screenWidth >= 220) {
                assertTrue(label.coordinates().contains("Earth Y -10513 m"));
                assertTrue(label.source().contains("heuristic"));
                assertTrue(label.source().contains("256 m"));
            }
        }
    }

    @Test void syntheticFixturesRemainUnambiguouslyLabeled() {
        var label = EarthWindowHud.layout(393, 272, 9, EarthWindowHudTest::width, 145, 8704, false);
        assertTrue(label.coordinates().contains("Earth Y +8849 m"));
        assertEquals("Synthetic calibration | 256 m window", label.source());
        assertFalse(label.source().contains("NOAA"));
    }
}
