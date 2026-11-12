package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        FarZoom view = FarZoom.at(zoom, SPREAD, 0, 1024, 0, 100, 0, 0, null);
        assertNotNull(view);
        assertEquals(64, view.zoom, "counted to the half step of two under its own zoom, not cut at 32");
    }

    @Test void zoomModsNarrowestViewAsksOnlyForItsOwnCone() {
        double zoom = Math.tan(Math.toRadians(35)) / Math.tan(Math.toRadians(0.5));
        FarZoom narrow = FarZoom.at(zoom, SPREAD, 0, 1024, 0, 100, 0, 0, null), wider = FarZoom.at(32, SPREAD, 0, 1024, 0, 100, 0, 0, null);
        assertNotNull(narrow);
        assertNotNull(wider);
        // A level 2 column 5 km straight ahead, its ground 3.3 degrees up: inside a 32x view's cone
        // (2.55 degrees to its corners) with the rounding and the column's size, outside the 80x view's (1 degree).
        FarZoom.Box ground = box(1024 + 288, 1024 + 292);
        assertTrue(wider.refined(2, 0, 78, ground));
        assertFalse(narrow.refined(2, 0, 78, ground), "an 80x view must not ask for the ground round a 32x view");
    }

    @Test void wideViewLeavesOutGroundAboveItThatTheConeRoundItsCornersTookIn() {
        FarZoom cone = FarZoom.at(32, SPREAD, 0, 0, 1024, 0, 100, 0, 0, null);
        FarZoom wide = FarZoom.at(32, SPREAD, WIDE, 0, 1024, 0, 100, 0, 0, null);
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
     * Every column whose ground shows in the view itself is sharpened, whatever the look, zoom and
     * screen shape and wherever the camera is within the blocks it is rounded to: cutting the view to
     * its sides must never leave a hole where a coarse column stays in plain sight.
     */
    @Test void neverLeavesOutGroundInsideTheView() {
        Random random = new Random(20261112L);
        int checked = 0;
        for (int view = 0; view < 400; view++) {
            double zoom = 2 + random.nextDouble() * 100, aspect = 0.5 + random.nextDouble() * 2.5, spread = 0.4 + random.nextDouble() * 1.4;
            float yaw = (float) (random.nextDouble() * 360 - 180), pitch = (float) (random.nextDouble() * 160 - 80);
            double ex = random.nextDouble() * 2000 - 1000, ey = 500 + random.nextDouble() * 1000, ez = random.nextDouble() * 2000 - 1000;
            FarZoom zoomed = FarZoom.at(zoom, spread, aspect, ex, ey, ez, 100, yaw, pitch, null);
            assertNotNull(zoomed);
            // The view's own shape at its own zoom, from the unrounded camera and look.
            double up = spread / zoom / Math.sqrt(1 + aspect * aspect), across = up * aspect;
            double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
            double[] forward = {-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)};
            double[] right = {Math.cos(y), 0, Math.sin(y)};
            double[] top = {right[1] * forward[2] - right[2] * forward[1], right[2] * forward[0] - right[0] * forward[2], right[0] * forward[1] - right[1] * forward[0]};
            for (int point = 0; point < 50; point++) {
                int level = 2 + random.nextInt(Far.MAX_LEVEL - 2);
                double reach = Far.hole(level) * Far.width(level) * zoomed.zoom;
                double distance = 200 + random.nextDouble() * Math.max(0, reach * 0.8 - 200);
                double a = (random.nextDouble() * 2 - 1) * across, b = (random.nextDouble() * 2 - 1) * up;
                double length = Math.sqrt(1 + a * a + b * b);
                double px = ex + (forward[0] + a * right[0] + b * top[0]) / length * distance;
                double py = ey + (forward[1] + a * right[1] + b * top[1]) / length * distance;
                double pz = ez + (forward[2] + a * right[2] + b * top[2]) / length * distance;
                int width = Far.width(level), cx = Math.floorDiv((int) Math.floor(px), width), cz = Math.floorDiv((int) Math.floor(pz), width);
                // Ground at that height, within a voxel or two: the point lies inside the column's box.
                FarZoom.Box ground = box(py - 2 * Far.voxel(level), py + 2 * Far.voxel(level));
                double ox = (cx + 0.5) * width - zoomed.x, oz = (cz + 0.5) * width - zoomed.z;
                // Only where the distance alone has the level split, as refined asks first.
                if (Math.max(Math.hypot(ox, oz), Math.max(0, Math.max(ground.lowY() - zoomed.y, zoomed.y - ground.highY()))) > reach) continue;
                assertTrue(zoomed.refined(level, cx, cz, ground), "view " + view + " point " + point + " left out");
                checked++;
            }
        }
        assertTrue(checked > 10000, "checked " + checked);
    }

    private static FarZoom.Box box(double low, double high) {
        return new FarZoom.Box() {
            public double lowY() { return low; }
            public double highY() { return high; }
            public double voxel() { return 4; }
        };
    }
}
