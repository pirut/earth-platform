package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.*;

import io.earthplatform.minecraft.cubic.far.Far;
import org.junit.jupiter.api.Test;

class FarVisualDetailTest {
    @Test void limitingZoomRepeatedlyKeepsTheOriginalViewCone() {
        FarZoom view = FarZoom.at(16, 1.2, 100, 800, 100, 700, 15, 30, null);
        assertNotNull(view);
        assertEquals(view.limited(2), view.limited(4).limited(2));
    }

    @Test void extremeZoomCountsUpToTheStepLimitAndKeepsItsNarrowCone() {
        FarZoom view = FarZoom.at(32, 1, 0, 8000, 0, 8000, 0, 0, null);
        assertNotNull(view);
        assertEquals(Math.pow(2, FarZoom.MAX_STEPS / 2.0), view.zoom);
        // A zoom capped at six steps is what limited(6) makes; limiting it again must keep the 32x cone.
        FarZoom capped = view.limited(6);
        assertEquals(8, capped.zoom);
        assertEquals(view.limited(2), capped.limited(2));
    }

    @Test void highCameraRefinesNearbyPeaksInsteadOfUsingTheValleyBelowAsTheirDistance() {
        FarZoom zoom = FarZoom.at(2, 1, 0, 5000, 0, 5000, 0, 0, null);
        assertNotNull(zoom);
        FarZoom.Box peak = box(4900, 5100), valley = box(0, 50);
        assertTrue(zoom.refined(2, 0, 5, peak));
        assertFalse(zoom.refined(2, 0, 5, valley));
        assertFalse(zoom.refined(1, 0, 5, peak), "cannot refine below the finest far level");
    }

    private static FarZoom.Box box(double low, double high) {
        return new FarZoom.Box() {
            public double lowY() { return low; }
            public double highY() { return high; }
            public double voxel() { return Far.voxel(2); }
        };
    }
}
