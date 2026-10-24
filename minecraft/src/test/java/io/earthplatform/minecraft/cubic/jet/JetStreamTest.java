package io.earthplatform.minecraft.cubic.jet;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JetStreamTest {
    private static Vec3 cruise(Vec3 look, double y) {
        Vec3 velocity = Vec3.ZERO;
        for (int tick = 0; tick < 2000; tick++) velocity = JetStream.carry(velocity, look, y);
        return velocity;
    }

    @Test void crossesAContinentInAboutAnHour() {
        // San Francisco to New York is about 4.3 million blocks; an hour or less either way.
        double east = cruise(new Vec3(1, 0, 0), 10500).horizontalDistance() * 20 * 3600;
        double west = cruise(new Vec3(-1, 0, 0), 10500).horizontalDistance() * 20 * 3600;
        assertTrue(west >= 4_300_000, "west " + west);
        assertTrue(east > west, "the westerly wind helps flying east");
    }

    @Test void hittingAWallAtJetSpeedIsFatal() {
        assertTrue(JetStream.crash(JetStream.CRUISE, 0) > 700, "far past any armor or health");
        assertTrue(JetStream.crash(0.2, 0) < 0, "a gentle bump does nothing");
    }

    @Test void weatherStaysBelowTheClearSky() {
        assertEquals(1.0F, JetStream.weather(3000), 1e-6);
        assertEquals(0.0F, JetStream.weather(JetStream.CLEAR_SKY), 1e-6);
        assertEquals(0.0F, JetStream.weather(JetStream.FLOOR + 500), 1e-6);
    }

    @Test void noWindBelowTheFloorAndFullAboveIt() {
        assertEquals(0.0, JetStream.strength(JetStream.FLOOR), 1e-9);
        assertEquals(1.0, JetStream.strength(JetStream.FLOOR + JetStream.FULL_DEPTH), 1e-9);
        assertTrue(JetStream.FLOOR > JetStream.CLEAR_SKY && JetStream.CLEAR_SKY > 8912, "everything generated stays below the clear sky");
    }

    @Test void pitchClimbsAndSinksButNotPastTheCeiling() {
        assertTrue(cruise(new Vec3(0.7, 0.7, 0), 10500).y > 2.0);
        assertTrue(cruise(new Vec3(0.7, -0.7, 0), 10500).y < -2.0);
        assertTrue(cruise(new Vec3(0.7, 0.7, 0), JetStream.CEILING).y <= 1e-9);
    }

    @Test void thickAirBleedsOffJetSpeedGradually() {
        Vec3 velocity = new Vec3(65, 0, 0);
        double travelled = 0;
        int ticks = 0;
        while (velocity.horizontalDistance() > 4.0 + 1e-6 && ticks < 10_000) {
            velocity = JetStream.slow(velocity);
            travelled += velocity.x;
            ticks++;
        }
        assertTrue(ticks > 100, "slows over seconds, not at once: " + ticks);
        assertTrue(travelled > 2000, "keeps going for kilometres: " + travelled);
        assertEquals(new Vec3(3, 1, 0), JetStream.slow(new Vec3(3, 1, 0)));
    }

    @Test void rocketsClimbStraightUpAndAddNothingInTheWind() {
        Vec3 up = new Vec3(0, 1, 0), level = new Vec3(1, 0, 0);
        Vec3 climbing = Vec3.ZERO;
        for (int tick = 0; tick < 30; tick++) climbing = JetStream.rocket(climbing, up, 1000);
        assertTrue(climbing.y > 9, "a rocket pointed up climbs fast: " + climbing.y);
        Vec3 flat = Vec3.ZERO;
        for (int tick = 0; tick < 30; tick++) flat = JetStream.rocket(flat, level, 1000);
        assertTrue(flat.x < 2, "level flight boosts like vanilla: " + flat.x);
        Vec3 riding = new Vec3(60, 0, 0);
        assertEquals(riding, JetStream.rocket(riding, level, JetStream.FLOOR + 100));
    }
}
