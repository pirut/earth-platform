package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FarPreciseDepthTest {
    @Test void standInsLeftInTheFarPassKeepTheGamesProjection() {
        // With -Dearthplatform.far.nearGround=false, or no copy of the game's depth, the stand-ins
        // under the player draw with the rest: the finer depth's near plane would clip them.
        assertFalse(FarRenderer.preciseDepth(true, true, false));
        // Drawn apart in the game's projection first, they are safe from it.
        assertTrue(FarRenderer.preciseDepth(true, true, true));
        // With the ground near the player loaded there are none to clip.
        assertTrue(FarRenderer.preciseDepth(true, false, false));
    }

    @Test void neverWithoutTheLayeredCloudsAskingForIt() {
        for (boolean standIns : new boolean[] {false, true})
            for (boolean apart : new boolean[] {false, true}) assertFalse(FarRenderer.preciseDepth(false, standIns, apart));
    }
}
