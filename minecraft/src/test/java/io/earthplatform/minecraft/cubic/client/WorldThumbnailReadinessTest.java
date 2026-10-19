package io.earthplatform.minecraft.cubic.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldThumbnailReadinessTest {
    @Test void emptyOrStillBuildingTerrainCannotProduceTheFirstIcon() {
        assertFalse(WorldThumbnailReadiness.ready(0, 0, 0, 100));
        assertFalse(WorldThumbnailReadiness.ready(10, 0, 10, 100));
        assertFalse(WorldThumbnailReadiness.ready(100, 1, 1000, 100));
    }

    @Test void aReadyNearViewDoesNotWaitForTheEntireFarHorizon() {
        assertTrue(WorldThumbnailReadiness.ready(11, 0, 0, 0));
        assertTrue(WorldThumbnailReadiness.ready(100, 0, 1000, 80));
    }

    @Test void farOnlyViewsWaitForVisibleRefinementAndActualDraws() {
        assertFalse(WorldThumbnailReadiness.ready(0, 0, 1000, 99));
        assertFalse(WorldThumbnailReadiness.ready(0, 0, 0, 100));
        assertTrue(WorldThumbnailReadiness.ready(0, 0, 11, 100));
    }
}
