package io.earthplatform.minecraft.cubic.jet;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OceanCurrentTest {
    private static final float EAST = -90, WEST = 90, SOUTH = 0;

    private static Vec3 cruise(float yaw, double latitude) {
        Vec3 velocity = Vec3.ZERO;
        for (int tick = 0; tick < 2000; tick++) velocity = OceanCurrent.carry(velocity, yaw, latitude);
        return velocity;
    }

    @Test void crossesAnOceanInAnHourOrTwo() {
        // New York to Lisbon is about 7.2 million blocks east on the map.
        double hours = 7_200_000 / (cruise(EAST, 40).horizontalDistance() * 20 * 3600);
        assertTrue(hours > 1 && hours < 2, "hours " + hours);
        Vec3 east = cruise(EAST, 40);
        assertTrue(east.x > 0 && Math.abs(east.z) < 0.01 * east.x && east.y == 0, "carried the way the boat faces: " + east);
        assertTrue(OceanCurrent.MAX_STEP > cruise(EAST, 45).horizontalDistance() * 1.5, "a tick in the current is never taken for a teleport");
    }

    @Test void tradeWindsWestAndWesterliesEast() {
        assertEquals(-1, OceanCurrent.flow(0), 1e-9);
        assertEquals(1, OceanCurrent.flow(45), 1e-9);
        assertEquals(1, OceanCurrent.flow(-45), 1e-9);
        assertEquals(-1, OceanCurrent.flow(90), 1e-9);
        assertTrue(OceanCurrent.flow(80) < 0, "west again by the poles");
        assertTrue(cruise(WEST, 15).horizontalDistance() > cruise(WEST, 40).horizontalDistance(), "west goes quicker in the tropics");
        assertTrue(cruise(EAST, 40).horizontalDistance() > cruise(EAST, 15).horizontalDistance(), "east goes quicker in the westerlies");
        assertEquals(OceanCurrent.CRUISE, cruise(SOUTH, 30).horizontalDistance(), 1e-6, "no drift across the current");
    }

    @Test void slowsToAStopWithinTheLookAhead() {
        Vec3 velocity = cruise(EAST, 45);
        double travelled = 0;
        int ticks = 0;
        while (velocity.horizontalDistance() >= OceanCurrent.LETS_GO) {
            velocity = OceanCurrent.brake(velocity);
            travelled += velocity.horizontalDistance();
            ticks++;
        }
        assertTrue(ticks > 20, "slows over a second or two, not at once: " + ticks);
        // A tick of travel and the widest far voxel read at speed to spare.
        assertTrue(travelled + cruise(EAST, 45).horizontalDistance() + 64 <= CurrentRide.LOOK_AHEAD, "stops in " + travelled);
        assertTrue(OceanCurrent.LETS_GO < OceanCurrent.OUTRUNS, "a boat let go is long since back among its cubes");
    }
}
