package io.earthplatform.minecraft.cubic.play.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FlightPathTest {
    @Test void pathStartsAndEndsAtTheTripsEnds() {
        double[] start = FlightPath.along(37.73, -119.57, 27.99, 86.93, 0), end = FlightPath.along(37.73, -119.57, 27.99, 86.93, 1);
        assertEquals(37.73, start[0], 1e-9);
        assertEquals(-119.57, start[1], 1e-9);
        assertEquals(27.99, end[0], 1e-9);
        assertEquals(86.93, end[1], 1e-9);
    }

    @Test void pathCrossesTheDateLineTheShortWay() {
        double[] middle = FlightPath.along(0, 170, 0, -170, 0.5);
        assertEquals(0, middle[0], 1e-9);
        assertEquals(180, Math.abs(middle[1]), 1e-9);
    }

    @Test void oppositePointsStillHaveAPath() {
        double[] middle = FlightPath.along(10, 20, -10, -160, 0.5);
        assertFalse(Double.isNaN(middle[0]) || Double.isNaN(middle[1]));
    }

    @Test void longTripsPullBackFurtherThanShortOnes() {
        double near = 11.5;
        double acrossTheWorld = FlightPath.farZoom(37.73, -119.57, 27.99, 86.93, near);
        double nextValley = FlightPath.farZoom(37.73, -119.57, 37.85, -119.55, near);
        assertEquals(0.15, acrossTheWorld, 1e-9);
        assertTrue(nextValley > acrossTheWorld);
        // Even a hop next door pulls well back from the close view.
        assertTrue(nextValley <= near - 4);
        assertEquals(near - 4, FlightPath.farZoom(1, 1, 1, 1, near), 1e-9);
    }
}
