package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.earthplatform.minecraft.cubic.far.Far;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Which far columns a zoomed view sharpens: how much of a zoom mod's zoom counts, and only the ground in the view itself. */
class FarZoomViewTest {
    /** The tangent from the middle of a 70 degree view to its corners on a 16:9 screen. */
    private static final double SPREAD = Math.tan(Math.toRadians(35)) * Math.hypot(16 / 9.0, 1), WIDE = 16 / 9.0;

    @Test void zoomModsNarrowestViewCountsPastThirtyTwoTimes() {
        // Zume's narrowest view, a 1 degree field of view against the game's 70: about 80 times.
        double zoom = Math.tan(Math.toRadians(35)) / Math.tan(Math.toRadians(0.5));
        FarZoom view = FarZoom.at(zoom, SPREAD, 0, false, 0, 0, 1024, 0, 100, 0, 0, null);
        assertNotNull(view);
        assertEquals(64, view.zoom, "counted to the half step of two under its own zoom, not cut at 32");
    }

    @Test void zoomModsNarrowestViewAsksOnlyForItsOwnCone() {
        double zoom = Math.tan(Math.toRadians(35)) / Math.tan(Math.toRadians(0.5));
        FarZoom narrow = FarZoom.at(zoom, SPREAD, 0, false, 0, 0, 1024, 0, 100, 0, 0, null), wider = FarZoom.at(32, SPREAD, 0, false, 0, 0, 1024, 0, 100, 0, 0, null);
        assertNotNull(narrow);
        assertNotNull(wider);
        // A level 2 column 5 km straight ahead, its ground 3.3 degrees up: inside a 32x view's cone
        // (2.55 degrees to its corners) with the rounding and the column's size, outside the 80x view's (1 degree).
        FarZoom.Box ground = box(1024 + 288, 1024 + 292);
        assertTrue(wider.refined(2, 0, 78, ground));
        assertFalse(narrow.refined(2, 0, 78, ground), "an 80x view must not ask for the ground round a 32x view");
    }

    @Test void wideViewLeavesOutGroundAboveItThatTheConeRoundItsCornersTookIn() {
        FarZoom cone = FarZoom.at(32, SPREAD, 0, false, 0, 0, 1024, 0, 100, 0, 0, null);
        FarZoom wide = FarZoom.at(32, SPREAD, WIDE, false, 0, 0, 1024, 0, 100, 0, 0, null);
        assertNotNull(cone);
        assertNotNull(wide);
        // A level 2 column 5 km straight ahead, its ground 260 blocks over the camera: 3 degrees up,
        // outside a 16:9 view zoomed 32 times (its top 1.25 degrees over the middle) but inside the
        // cone round its corners (2.55 degrees), and the view is not rounded more than that.
        FarZoom.Box above = box(1024 + 260, 1024 + 265);
        assertTrue(cone.refined(2, 0, 78, above));
        assertFalse(wide.refined(2, 0, 78, above));
        // Level with the camera it is in the view either way.
        FarZoom.Box ahead = box(1000, 1040);
        assertTrue(cone.refined(2, 0, 78, ahead));
        assertTrue(wide.refined(2, 0, 78, ahead));
    }

    /**
     * The view bobbing the game draws while the player walks: the look tipped by up to half a degree and
     * rolled by up to a third of one. A zoomed 1 degree view on a 3456 by 2112 screen, 59.9 km from level
     * ground a kilometre below it, shows the ground 0.98 degrees below the player's look in its bottom
     * fifth once the view is tipped down by 0.5 degrees, which the sides cut to the player's look left out.
     */
    @Test void viewBobbingTippingTheViewDownKeepsTheGroundLowOnTheScreen() {
        double aspect = 3456 / 2112.0, spread = Math.tan(Math.toRadians(35)) * Math.hypot(aspect, 1), zoom = Math.tan(Math.toRadians(35)) / Math.tan(Math.toRadians(0.5));
        FarZoom bobbed = FarZoom.at(zoom, spread, aspect, true, 0, 0, 1024, 0, 100, 0, 0, null);
        FarZoom still = FarZoom.at(zoom, spread, aspect, false, 0, 0, 1024, 0, 100, 0, 0, null);
        assertNotNull(bobbed);
        assertNotNull(still);
        // Level 4's column 0, 234 (256 blocks wide, 59,904 to 60,160 blocks off) of flat ground at height 0.
        FarZoom.Box ground = box(-4, 4);
        View tipped = new View(0, 1024, 0, 0, 0, Math.toRadians(0.5), 0, zoom, spread, aspect);
        assertTrue(tipped.sees(0, 0, 59_904), "the ground's near edge is on the screen the player sees");
        assertTrue(bobbed.refined(4, 0, 234, ground));
        // As the view stands still, it is 0.98 degrees below the look and the view reaches 0.93.
        assertFalse(still.refined(4, 0, 234, ground));
    }

    /**
     * The Earth's curve, one block to the metre, a camera 6 km up looking 4.69 degrees down: ground
     * at height 0 about 90 km off is drawn 608 blocks lower and shows 0.49 degrees above the middle of
     * a 1 degree view on a 3456 by 2112 screen, but where it lies it is 1.01 degrees over the rounded look, past the view's top.
     */
    @Test void earthCurveDroppingGroundIntoTheViewKeepsItsColumn() {
        double aspect = 3456 / 2112.0, spread = Math.tan(Math.toRadians(35)) * Math.hypot(aspect, 1), zoom = Math.tan(Math.toRadians(35)) / Math.tan(Math.toRadians(0.5));
        float curve = EarthView.full(1.0);
        FarZoom curved = FarZoom.at(zoom, spread, aspect, false, curve, 0, 6000, 0, 100, 0, 4.69F, null);
        FarZoom flat = FarZoom.at(zoom, spread, aspect, false, 0, 0, 6000, 0, 100, 0, 4.69F, null);
        assertNotNull(curved);
        assertNotNull(flat);
        // Level 4's column 0, 351 (89,856 to 90,112 blocks off) of flat ground at height 0.
        FarZoom.Box ground = box(-4, 4);
        View view = new View(0, 6000, 0, 0, 4.69F, 0, 0, zoom, spread, aspect);
        double drawn = -EarthView.drop(0, 90_000, curve);
        assertEquals(-608, drawn, 1);
        assertTrue(view.sees(0, drawn, 90_000), "drawn there it is on the screen");
        assertTrue(curved.refined(4, 0, 351, ground));
        // Without the curve the ground is where it lies, above the view.
        assertFalse(flat.refined(4, 0, 351, ground));
    }

    /**
     * The Earth's curve at a tenth of a block to the metre, a camera 4,512 blocks up looking 45.35 degrees
     * up at level 2 ground between 8,496 and 8,500 blocks, 3,936 off: its flat gap of 3,984 blocks is past
     * the 3,982 a 6x view reaches, but the curve draws it about 2.2 blocks lower, on the screen and inside the reach.
     */
    @Test void earthCurveBringingGroundAboveTheCameraInsideTheReachKeepsItsColumn() {
        double aspect = 16 / 9.0, spread = SPREAD;
        float curve = EarthView.curvature(4512, 0.1, false);
        FarZoom curved = FarZoom.at(6, spread, aspect, false, curve, 0, 4512, 0, 100, 0, -45.35F, null);
        FarZoom flat = FarZoom.at(6, spread, aspect, false, 0, 0, 4512, 0, 100, 0, -45.35F, null);
        assertNotNull(curved);
        assertNotNull(flat);
        assertEquals(Math.pow(2, 2.5), curved.zoom, 1e-9);
        FarZoom.Box ground = box(8496, 8500);
        double reach = Far.hole(2) * Far.width(2) * curved.zoom, drop = EarthView.drop(32, 3936, curve);
        assertEquals(3982.43, reach, 0.01);
        assertTrue(8496 - drop - 4512 < reach && 8496 - 4512 > reach, "its flat gap is past the reach, its drawn gap inside");
        assertTrue(new View(0, 4512, 0, 0, -45.35F, 0, 0, curved.zoom, spread, aspect).sees(32, 8498 - drop, 3936), "drawn there it is on the screen");
        assertTrue(curved.refined(2, 0, 61, ground));
        // On flat ground it is where it lies, past the reach.
        assertFalse(flat.refined(2, 0, 61, ground));
    }

    @Test void bobbingAndTheEarthCurveAreWhatMakeTwoViewsDiffer() {
        double zoom = 64, curve = EarthView.full(1.0);
        FarZoom view = FarZoom.at(zoom, SPREAD, WIDE, false, 0, 0, 1024, 0, 100, 0, 0, null);
        assertNotNull(view);
        assertSame(view, FarZoom.at(zoom, SPREAD, WIDE, false, 0, 0, 1024, 0, 100, 0, 0, view), "nothing changed, so the plan is not made again");
        assertNotEquals(view, FarZoom.at(zoom, SPREAD, WIDE, true, 0, 0, 1024, 0, 100, 0, 0, null));
        assertNotEquals(view, FarZoom.at(zoom, SPREAD, WIDE, false, (float) curve, 0, 1024, 0, 100, 0, 0, null));
        // And they are kept in the view that counts less of the zoom.
        FarZoom bobbed = FarZoom.at(zoom, SPREAD, WIDE, true, (float) curve, 0, 1024, 0, 100, 0, 0, null);
        assertNotNull(bobbed);
        assertEquals(bobbed.limited(2), bobbed.limited(4).limited(2));
        assertNotEquals(view.limited(2), bobbed.limited(2));
    }

    /**
     * The walking bob counts while it can be tipping the view: its own dies away in about a third of a
     * second after the player stops, and the view holds the wider sides for about a second more.
     */
    @Test void bobbingCountsOnlyWhileTheOptionIsOnAndThePlayerBobs() {
        assertTrue(FarZoom.bobs(true, 0.1F, 0.1F));
        assertTrue(FarZoom.bobs(true, 0, 0.05F), "the view lerps from the last tick's");
        assertFalse(FarZoom.bobs(false, 0.1F, 0.1F));
        assertFalse(FarZoom.bobs(true, 0, 0));
        // Dying away by 0.6 a tick from 0.1, it is under the least that counts within 8 ticks.
        float bob = 0.1F;
        for (int tick = 0; tick < 8; tick++) bob *= 0.6F;
        assertFalse(FarZoom.bobs(true, bob, bob));
    }

    @Test void bobbingHoldsThroughAJumpAndEndsAfterAQuietSecond() {
        // Sprint-jumping: 0.1 on the ground, then dying away by 0.6 a tick for a 12 tick jump, then back.
        int hold = 0;
        float bob = 0.1F;
        hold = FarZoom.bobHold(true, bob, bob, hold);
        assertEquals(FarZoom.BOB_HOLD, hold);
        boolean quiet = false;
        for (int tick = 0; tick < 12; tick++) {
            float last = bob;
            bob *= 0.6F;
            hold = FarZoom.bobHold(true, bob, last, hold);
            assertTrue(hold > 0, "still counted as bobbing " + tick + " ticks into the jump");
            quiet |= !FarZoom.bobs(true, bob, last);
        }
        assertTrue(quiet, "the bob itself was under the least that counts for part of the jump");
        // Landing sets it back.
        assertEquals(FarZoom.BOB_HOLD, FarZoom.bobHold(true, 0.04F, bob, hold));
        // Standing still, it counts down by one a tick and is off after a second of quiet.
        hold = FarZoom.BOB_HOLD;
        for (int tick = 1; tick < FarZoom.BOB_HOLD; tick++) {
            hold = FarZoom.bobHold(true, 0, 0, hold);
            assertTrue(hold > 0, "after " + tick + " quiet ticks");
        }
        assertEquals(0, FarZoom.bobHold(true, 0, 0, hold), "off after " + FarZoom.BOB_HOLD + " quiet ticks");
        assertEquals(0, FarZoom.bobHold(true, 0, 0, 0));
        // With the option off it never counts, whatever was held.
        assertEquals(0, FarZoom.bobHold(false, 0.1F, 0.1F, FarZoom.BOB_HOLD));
        assertEquals(0, FarZoom.bobHold(false, 0, 0, FarZoom.BOB_HOLD));
    }

    /**
     * Every column whose ground shows in the view itself is sharpened, whatever the look, zoom and
     * screen shape and wherever the camera is within the blocks it is rounded to: cutting the view to
     * its sides must never leave a hole where a coarse column stays in plain sight.
     */
    @Test void neverLeavesOutGroundInsideTheView() { neverLeavesOut(20261112L, false, false); }

    /** As above, for the view the game draws while the player walks: tipped by up to 0.5 degrees either way and rolled by up to 0.3. */
    @Test void neverLeavesOutGroundInsideTheBobbingView() { neverLeavesOut(20261113L, true, false); }

    /** As above under the Earth's curve, for ground drawn where its flat place, which the column holds, has dropped to. */
    @Test void neverLeavesOutGroundDrawnInsideTheViewUnderTheEarthsCurve() { neverLeavesOut(20261114L, false, true); }

    @Test void neverLeavesOutGroundInsideTheBobbingViewUnderTheEarthsCurve() { neverLeavesOut(20261115L, true, true); }

    private void neverLeavesOut(long seed, boolean bobbing, boolean curved) {
        Random random = new Random(seed);
        double maxTip = Math.toRadians(0.5), maxRoll = Math.toRadians(0.3);
        int checked = 0;
        for (int view = 0; view < 400; view++) {
            double zoom = 2 + random.nextDouble() * 100, aspect = 0.5 + random.nextDouble() * 2.5, spread = 0.4 + random.nextDouble() * 1.4;
            float yaw = (float) (random.nextDouble() * 360 - 180), pitch = (float) (random.nextDouble() * 160 - 80);
            double ex = random.nextDouble() * 2000 - 1000, ey = (curved ? 1000 : 500) + random.nextDouble() * (curved ? 7000 : 1000), ez = random.nextDouble() * 2000 - 1000;
            // At the Earth's own drop for 4 to a quarter of a block to the metre.
            float curve = curved ? EarthView.full(0.25 + random.nextDouble() * 4) : 0;
            // Tipped and rolled as far as it goes, or anywhere between.
            double tip = !bobbing ? 0 : random.nextInt(3) == 0 ? (random.nextBoolean() ? maxTip : -maxTip) : (random.nextDouble() * 2 - 1) * maxTip;
            double roll = !bobbing ? 0 : random.nextInt(3) == 0 ? (random.nextBoolean() ? maxRoll : -maxRoll) : (random.nextDouble() * 2 - 1) * maxRoll;
            FarZoom zoomed = FarZoom.at(zoom, spread, aspect, bobbing, curve, ex, ey, ez, 100, yaw, pitch, null);
            assertNotNull(zoomed);
            // The view's own shape at its own zoom, from the unrounded camera and look.
            View screen = new View(ex, ey, ez, yaw, pitch, tip, roll, zoom, spread, aspect);
            for (int point = 0; point < 50; point++) {
                int level = 2 + random.nextInt(Far.MAX_LEVEL - 2);
                double reach = Far.hole(level) * Far.width(level) * zoomed.zoom;
                double distance = 200 + random.nextDouble() * Math.max(0, reach * 0.8 - 200);
                double[] at = screen.along((random.nextDouble() * 2 - 1) * screen.across, (random.nextDouble() * 2 - 1) * screen.up, distance);
                int width = Far.width(level), cx = Math.floorDiv((int) Math.floor(at[0]), width), cz = Math.floorDiv((int) Math.floor(at[2]), width);
                // Where it lies: under the curve the ground drawn there lies higher by what it drops.
                double lies = at[1] + EarthView.drop(at[0] - ex, at[2] - ez, curve);
                // Ground at that height, within a voxel or two: the point lies inside the column's box.
                FarZoom.Box ground = box(lies - 2 * Far.voxel(level), lies + 2 * Far.voxel(level));
                double ox = (cx + 0.5) * width - zoomed.x, oz = (cz + 0.5) * width - zoomed.z;
                // Only where the distance alone has the level split, as refined asks first: ground above
                // the camera as low as the curve can draw it, as it counts.
                double gap = Math.max(0, Math.max(ground.lowY() - zoomed.drop(cx, cz, width) - zoomed.y, zoomed.y - ground.highY()));
                if (Math.max(Math.hypot(ox, oz), gap) > reach) continue;
                assertTrue(zoomed.refined(level, cx, cz, ground), "view " + view + " point " + point + " left out");
                checked++;
            }
        }
        assertTrue(checked > 10000, "checked " + checked);
    }

    /** The view as the game draws it: the player's look tipped down by {@code tip} radians (up if negative), then rolled by {@code roll}. */
    private static final class View {
        final double ex, ey, ez, across, up;
        final double[] forward = new double[3], right = new double[3], top = new double[3];

        View(double ex, double ey, double ez, float yaw, float pitch, double tip, double roll, double zoom, double spread, double aspect) {
            this.ex = ex;
            this.ey = ey;
            this.ez = ez;
            up = spread / zoom / Math.sqrt(1 + aspect * aspect);
            across = up * aspect;
            double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
            double[] look = {-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)};
            double[] side = {Math.cos(y), 0, Math.sin(y)};
            // Straight up from the look, which is the other way from the side crossed with the look.
            double[] above = {-(side[1] * look[2] - side[2] * look[1]), -(side[2] * look[0] - side[0] * look[2]), -(side[0] * look[1] - side[1] * look[0])};
            for (int i = 0; i < 3; i++) {
                forward[i] = Math.cos(tip) * look[i] - Math.sin(tip) * above[i];
                double lifted = Math.cos(tip) * above[i] + Math.sin(tip) * look[i];
                right[i] = Math.cos(roll) * side[i] + Math.sin(roll) * lifted;
                top[i] = -Math.sin(roll) * side[i] + Math.cos(roll) * lifted;
            }
        }

        /** The point {@code distance} blocks off along the ray {@code a} across and {@code b} up from the middle (tangents). */
        double[] along(double a, double b, double distance) {
            double length = Math.sqrt(1 + a * a + b * b);
            return new double[] {ex + (forward[0] + a * right[0] + b * top[0]) / length * distance, ey + (forward[1] + a * right[1] + b * top[1]) / length * distance,
                    ez + (forward[2] + a * right[2] + b * top[2]) / length * distance};
        }

        boolean sees(double px, double py, double pz) {
            double dx = px - ex, dy = py - ey, dz = pz - ez;
            double ahead = dx * forward[0] + dy * forward[1] + dz * forward[2];
            if (ahead <= 0) return false;
            return Math.abs(dx * right[0] + dy * right[1] + dz * right[2]) <= across * ahead && Math.abs(dx * top[0] + dy * top[1] + dz * top[2]) <= up * ahead;
        }
    }

    private static FarZoom.Box box(double low, double high) {
        return new FarZoom.Box() {
            public double lowY() { return low; }
            public double highY() { return high; }
            public double voxel() { return 4; }
        };
    }
}
