package io.earthplatform.minecraft.cubic.play.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShotPathTest {
    @Test
    void orbitStartsBehindThePlayerLookingTheirWay() {
        // Yaw 0 looks south (+Z): the camera starts north of the point, looking south at it.
        ShotPath.Orbit orbit = new ShotPath.Orbit(100, 70, 200, 50, 20, 0, 40);
        ShotPath.View start = orbit.at(0);
        assertEquals(100, start.x(), 1e-9);
        assertEquals(150, start.z(), 1e-9);
        assertEquals(90, start.y(), 1e-9);
        assertEquals(0, start.yaw(), 1e-9);
        // Yaw 90 looks west (-X): a quarter of the way round the camera is east of the point.
        ShotPath.View quarter = orbit.at(10);
        assertEquals(150, quarter.x(), 1e-9);
        assertEquals(200, quarter.z(), 1e-9);
        assertEquals(90, quarter.yaw(), 1e-9);
    }

    @Test
    void orbitAlwaysLooksAtItsPointAndComesRoundToItsStart() {
        ShotPath.Orbit orbit = new ShotPath.Orbit(-3000, 1200, 8000, 300, 135, 77, 30);
        for (double time = 0; time < 30; time += 0.37) {
            ShotPath.View view = orbit.at(time);
            double yaw = Math.toRadians(view.yaw()), pitch = Math.toRadians(view.pitch());
            // Minecraft's look vector for a yaw and pitch.
            double lx = -Math.sin(yaw) * Math.cos(pitch), ly = -Math.sin(pitch), lz = Math.cos(yaw) * Math.cos(pitch);
            double dx = -3000 - view.x(), dy = 1200 - view.y(), dz = 8000 - view.z(), far = Math.sqrt(dx * dx + dy * dy + dz * dz);
            assertEquals(dx / far, lx, 1e-9);
            assertEquals(dy / far, ly, 1e-9);
            assertEquals(dz / far, lz, 1e-9);
            assertEquals(300, Math.hypot(dx, dz), 1e-9);
        }
        ShotPath.View first = orbit.at(0), again = orbit.at(30);
        assertEquals(first.x(), again.x(), 1e-6);
        assertEquals(first.z(), again.z(), 1e-6);
        assertEquals(first.yaw(), again.yaw(), 1e-6);
    }

    @Test
    void viewsBlendTheShortWayRound() {
        ShotPath.View a = new ShotPath.View(0, 0, 0, 170, 0), b = new ShotPath.View(10, 20, 30, -170, 40);
        ShotPath.View half = a.toward(b, 0.5);
        assertEquals(180, Math.abs(ShotPath.wrap(half.yaw())), 1e-9);
        assertEquals(5, half.x(), 1e-9);
        assertEquals(20, half.pitch(), 1e-9);
    }

    @Test
    void flyoverStartsAndStopsGentlyAndCoversTheWholeWay() {
        double seconds = 60, length = 9000, ramp = 4;
        assertEquals(0, ShotPath.travelled(0, seconds, length, ramp), 1e-9);
        assertEquals(length, ShotPath.travelled(seconds, seconds, length, ramp), 1e-9);
        assertEquals(length / 2, ShotPath.travelled(seconds / 2, seconds, length, ramp), 1e-6);
        double cruise = length / (seconds - ramp), before = 0, speedBefore = 0;
        for (double time = 0.01; time <= seconds; time += 0.01) {
            double now = ShotPath.travelled(time, seconds, length, ramp), speed = (now - before) / 0.01;
            assertTrue(speed >= -1e-9 && speed <= cruise + 1e-6, "speed " + speed + " at " + time);
            // No jerk: the speed never changes by more than the ramps' own rate.
            assertTrue(Math.abs(speed - speedBefore) <= cruise / ramp * 0.01 + 1e-6, "jump at " + time);
            before = now;
            speedBefore = speed;
        }
        // A short shot is all ramp.
        assertEquals(50, ShotPath.travelled(3, 6, 100, 4), 1e-9);
    }

    @Test
    void clearanceIsNeverUnderTheGroundAndRisesAheadOfARidge() {
        double[] ground = new double[400];
        for (int i = 0; i < ground.length; i++) ground[i] = 100 + 30 * Math.sin(i / 9.0) + (i >= 200 && i < 215 ? 900 : 0);
        double[] clear = ShotPath.clearance(ground, 24);
        for (int i = 0; i < ground.length; i++) assertTrue(clear[i] >= ground[i] - 1e-9, "under the ground at " + i);
        // Well before the ridge the camera is already most of the way up it.
        assertTrue(clear[190] > 900, "still low at 190: " + clear[190]);
        // And far from it, it is back down near the hills.
        assertTrue(clear[100] < 140 && clear[320] < 140);
        // No steps: neighbors differ by a small share of the ridge.
        for (int i = 1; i < clear.length; i++) assertTrue(Math.abs(clear[i] - clear[i - 1]) < 900.0 / 6, "step at " + i);
    }

    @Test
    void clearanceFillsGroundNotKnownYetFromTheNearestKnown() {
        double[] ground = {Double.NaN, Double.NaN, 50, 60, Double.NaN, Double.NaN};
        double[] clear = ShotPath.clearance(ground, 1);
        assertEquals(50, clear[0], 1e-9);
        assertEquals(60, clear[5], 1e-9);
        double[] none = ShotPath.clearance(new double[]{Double.NaN, Double.NaN}, 1);
        assertEquals(0, none[0], 1e-9);
        assertEquals(55, ShotPath.sample(new double[]{50, 60}, 0.5), 1e-9);
        assertEquals(60, ShotPath.sample(new double[]{50, 60}, 7), 1e-9);
    }

    @Test
    void followerSettlesOnItsTargetWithoutOvershootWhateverTheFrameRate() {
        ShotPath.Follower smooth = new ShotPath.Follower(0, 0.9), choppy = new ShotPath.Follower(0, 0.9);
        double highest = 0;
        for (int i = 0; i < 20 * 60; i++) highest = Math.max(highest, smooth.step(100, 1 / 60.0));
        for (int i = 0; i < 20 * 4; i++) choppy.step(100, 0.25);
        assertTrue(highest <= 100 + 1e-6, "overshot to " + highest);
        assertEquals(100, smooth.value(), 0.01);
        assertEquals(smooth.value(), choppy.value(), 0.05);
        // Three seconds in, most of the way there but not arrived.
        ShotPath.Follower early = new ShotPath.Follower(0, 0.9);
        early.step(100, 3);
        assertTrue(early.value() > 60 && early.value() < 95, "after 3 s: " + early.value());
        early.atLeast(99);
        assertEquals(99, early.value(), 1e-9);
    }
}
