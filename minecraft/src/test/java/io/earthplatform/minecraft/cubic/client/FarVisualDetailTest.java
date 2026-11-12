package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.*;

import io.earthplatform.minecraft.cubic.far.Far;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

class FarVisualDetailTest {
    @Test void limitingZoomRepeatedlyKeepsTheOriginalViewCone() {
        FarZoom view = FarZoom.at(16, 1.2, 100, 800, 100, 700, 15, 30, null);
        assertNotNull(view);
        assertEquals(view.limited(2), view.limited(4).limited(2));
    }

    @Test void extremeZoomCountsUpToTheStepLimitAndKeepsItsNarrowCone() {
        FarZoom view = FarZoom.at(FarZoom.MAX, 1, 0, 8000, 0, 8000, 0, 0, null);
        assertNotNull(view);
        assertEquals(Math.pow(2, FarZoom.MAX_STEPS / 2.0), view.zoom);
        // A zoom capped at six steps is what limited(6) makes; limiting it again must keep the whole zoom's cone.
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

    @Test void cruisingNeverSmoothsTheFourDetailedBlockLevels() {
        assertEquals(Far.EDIT_LEVELS + 1, FarAtmosphere.FIRST_SMOOTH);
        assertEquals(FarAtmosphere.FIRST_SMOOTH, FarAtmosphere.smoothFrom(true));
        assertEquals(FarMesher.SMOOTH_FROM, FarAtmosphere.smoothFrom(false));
    }

    @Test void cruiseSmoothDoesNothingWithoutSmoothGround() {
        // Far.smooth is off in tests, as by default: SMOOTH_FROM is Integer.MAX_VALUE and nothing cruises.
        assertEquals(Integer.MAX_VALUE, FarMesher.SMOOTH_FROM);
        assertFalse(FarAtmosphere.CRUISES);
    }

    @Test void neverCruisesUnderAShaderPack() {
        assertFalse(FarAtmosphere.cruise(5000, false, true));
        assertFalse(FarAtmosphere.cruise(5000, true, true), "a pack coming mid-cruise ends it");
        assertTrue(FarAtmosphere.cruise(5000, true, false));
    }

    /**
     * Stand-ins at the player's feet, 1.6, 4 and 7.9 blocks in front, are nearer than far terrain's
     * own near plane: drawn in its projection they would clip, so they draw in the game's.
     */
    @Test void nearStandInsWouldClipInFarTerrainsOwnDepth() {
        Matrix4f game = new Matrix4f().perspective(1.2f, 16f / 9, 0.05f, FarRenderer.depthFar());
        Matrix4f own = FarPlainDepth.projection(game);
        for (float z : new float[] {1.6f, 4, 7.9f}) {
            Vector4f inGame = game.transform(new Vector4f(0, -1.6f, -z, 1)), inOwn = own.transform(new Vector4f(0, -1.6f, -z, 1));
            assertTrue(inGame.z >= -inGame.w, "kept in the game's depth at " + z);
            assertTrue(inOwn.z < -inOwn.w, "clipped in far terrain's own at " + z);
        }
    }

    @Test void cruisingHasHysteresis() {
        assertFalse(FarAtmosphere.cruise(2000, false));
        assertTrue(FarAtmosphere.cruise(2300, false));
        assertTrue(FarAtmosphere.cruise(2000, true));
        assertFalse(FarAtmosphere.cruise(1500, true));
        assertFalse(FarAtmosphere.cruise(Double.NaN, true));
    }

    @Test void atmosphereRampsSmoothlyAboveTheGround() {
        assertEquals(0, FarAtmosphere.blend(-100));
        assertEquals(0, FarAtmosphere.blend(2000));
        assertEquals(0.5f, FarAtmosphere.blend(4000));
        assertEquals(1, FarAtmosphere.blend(6000));
        assertEquals(0, FarAtmosphere.blend(Double.NaN));
        float before = 0;
        for (int y = 0; y <= 10000; y += 10) {
            float blend = FarAtmosphere.blend(y);
            assertTrue(blend >= before);
            before = blend;
        }
    }

    @Test void farTerrainsOwnDepthKeepsTheViewAndSeparatesWhatTheGamesDepthCannot() {
        Matrix4f game = new Matrix4f().perspective((float) Math.toRadians(7), 16f / 9, 0.05f, FarRenderer.depthFar());
        Matrix4f own = FarPlainDepth.projection(game);
        assertEquals(game.m00(), own.m00());
        assertEquals(game.m11(), own.m11());
        assertEquals(depth(game, 10000), depth(game, 10002), "the game's depth can't tell two-block voxels apart ten kilometres out");
        assertTrue(depth(own, 10000) < depth(own, 10002));
    }

    private static float depth(Matrix4f projection, float distance) {
        Vector4f clip = projection.transform(new Vector4f(0, 0, -distance, 1));
        return clip.z / clip.w * 0.5f + 0.5f;
    }

    private static FarZoom.Box box(double low, double high) {
        return new FarZoom.Box() {
            public double lowY() { return low; }
            public double highY() { return high; }
            public double voxel() { return Far.voxel(2); }
        };
    }
}
