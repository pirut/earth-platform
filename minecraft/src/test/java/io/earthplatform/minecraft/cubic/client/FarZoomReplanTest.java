package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** A zoomed view that changes how much it zooms plans at once; one that only pans waits for the gate. */
class FarZoomReplanTest {
    private static final int LAST_PLAN = 100;

    @Test void zoomingInFromUnzoomedPlansAtOnce() {
        FarZoom view = view(2, 0);
        assertEquals(LAST_PLAN + 1, firstPlan(null, view));
    }

    @Test void changingMagnificationPlansAtOnce() {
        FarZoom twice = view(2, 0), most = FarZoom.at(32, 1, 0, 5000, 0, 5000, 0, 0, twice);
        assertNotSame(twice, most);
        assertEquals(LAST_PLAN + 1, firstPlan(twice, most), "2x to 32x must not wait for the pan gate");
        FarZoom back = FarZoom.at(2, 1, 0, 5000, 0, 5000, 0, 0, most);
        assertEquals(LAST_PLAN + 1, firstPlan(most, back), "zooming back out plans at once too");
    }

    @Test void panningWaitsForTheGate() {
        FarZoom ahead = view(2, 0), turned = FarZoom.at(2, 1, 0, 5000, 0, 5000, 30, 0, ahead);
        assertNotSame(ahead, turned, "the turn is past the look's rounding");
        assertFalse(FarZoom.rezoomed(ahead, turned));
        assertEquals(LAST_PLAN + 4, firstPlan(ahead, turned), "a yaw-only change plans once 4 ticks have passed");
    }

    private static FarZoom view(double zoom, float yaw) {
        FarZoom view = FarZoom.at(zoom, 1, 0, 5000, 0, 5000, yaw, 0, null);
        assertNotNull(view);
        return view;
    }

    /** The first tick after {@link #LAST_PLAN} that plans, when the view changes from {@code before} to {@code after} the tick after it. */
    private static int firstPlan(FarZoom before, FarZoom after) {
        // As FarRenderer.zoom sets its flags for a changed view.
        boolean planNow = FarZoom.rezoomed(before, after), zoomMoved = !planNow;
        for (int tick = LAST_PLAN + 1; tick < LAST_PLAN + 10; tick++) if (FarRenderer.plans(tick, LAST_PLAN, planNow, zoomMoved)) return tick;
        return -1;
    }
}
