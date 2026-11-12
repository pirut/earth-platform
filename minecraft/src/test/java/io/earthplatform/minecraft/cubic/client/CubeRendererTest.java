package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CubeRendererTest {
    @Test void staleMeshesGiveWayToNewerOnesOnlyWhileTheCubeHasAMeshAndNotForever() {
        assertFalse(CubeRenderer.dropStale(3, 3, true, 0));
        assertTrue(CubeRenderer.dropStale(3, 4, true, 0));
        assertFalse(CubeRenderer.dropStale(3, 4, false, 0));
        assertTrue(CubeRenderer.dropStale(3, 4, true, CubeRenderer.STALE_DROPS - 1));
        assertFalse(CubeRenderer.dropStale(3, 4, true, CubeRenderer.STALE_DROPS));
    }
}
