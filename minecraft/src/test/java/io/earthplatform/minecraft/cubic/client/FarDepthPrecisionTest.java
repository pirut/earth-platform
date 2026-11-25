package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

/**
 * Why far terrain's faces fought each other, most visibly zoomed in, and that its own depth stops it.
 * Depth is worked out in double from the projection's own entries and then stored as each buffer
 * stores it: the game's as 24-bit fixed point, far terrain's as a 32-bit float. Both give the same
 * step above half depth, so it is the near plane, not the float format, that makes the difference
 * (reversed Z would need {@code ARB_clip_control}, which macOS's OpenGL 4.1 lacks).
 */
class FarDepthPrecisionTest {
    private static final float GAME_NEAR = 0.05F, VIEW_REACH = 192;
    private static final double FIXED = (1 << 24) - 1;

    private static float far() { return FarRenderer.depthFar(); }

    private static Matrix4f game(double fovDegrees) {
        return new Matrix4f().perspective((float) Math.toRadians(fovDegrees), 16f / 9, GAME_NEAR, far());
    }

    /** Far terrain's own projection for a game projection, with the near plane the renderer works out from it. */
    private static Matrix4f own(Matrix4f game, float reach) { return FarPlainDepth.projection(game, FarPlainDepth.near(game, reach)); }

    /** Depth in [0, 1] of a point straight ahead at a distance, before it is stored. */
    private static double ideal(Matrix4f projection, double distance) {
        return (-projection.m22() + projection.m32() / distance) * 0.5 + 0.5;
    }

    /** The game's depth buffer: 24-bit fixed point. */
    private static long inGame(Matrix4f game, double distance) { return Math.round(ideal(game, distance) * FIXED); }

    /** Far terrain's own: 32-bit float. */
    private static float inOwn(Matrix4f own, double distance) { return (float) ideal(own, distance); }

    /** The smallest gap at a distance the depth tells apart in every alignment: {@code d^2 (far - near) / (near far)} steps. */
    private static double step(double near, double distance) { return distance * distance * (far() - near) / (near * far()) * FarPlainDepth.STEP; }

    /** Of 400 pairs of faces, {@code gap} blocks apart, starting every 0.37 block from {@code distance}, the share the game's depth cannot tell apart. */
    private static double gameCollides(Matrix4f game, double distance, double gap) {
        int same = 0;
        for (int i = 0; i < 400; i++) {
            double near = distance + 0.37 * i;
            if (inGame(game, near) == inGame(game, near + gap)) same++;
        }
        return same / 400.0;
    }

    private static double ownCollides(Matrix4f own, double distance, double gap) {
        int same = 0;
        for (int i = 0; i < 400; i++) {
            double near = distance + 0.37 * i;
            if (inOwn(own, near) == inOwn(own, near + gap)) same++;
        }
        return same / 400.0;
    }

    @Test void theGamesDepthStepIsTensToThousandsOfBlocksFarOut() {
        Matrix4f game = game(70);
        // The numbers behind the bug: with a 0.05 near plane and about 188 km of far plane.
        double km1 = step(GAME_NEAR, 1000), km5 = step(GAME_NEAR, 5000), km20 = step(GAME_NEAR, 20000), km100 = step(GAME_NEAR, 100000);
        System.out.printf("DEPTH_STEP game (near 0.05): 1 km %.2f, 5 km %.1f, 20 km %.0f, 100 km %.0f blocks%n", km1, km5, km20, km100);
        assertEquals(1.2, km1, 0.1);
        assertEquals(29.8, km5, 1);
        assertEquals(477, km20, 20);
        assertTrue(km100 > 10000);
        // The formula is the depth buffer's: two faces a step apart differ, well inside one collide.
        assertNotEquals(inGame(game, 5000), inGame(game, 5000 + 2 * km5));
        assertEquals(inGame(game, 5000), inGame(game, 5001));
    }

    @Test void farTerrainsOwnDepthIsHundredsOfTimesFiner() {
        for (float reach : new float[] {96, 192, 256, 512}) {
            float near = FarPlainDepth.near(game(70), reach);
            Matrix4f own = own(game(70), reach);
            System.out.printf("DEPTH_STEP own (near %.1f, reach %.0f): 1 km %.3f, 5 km %.3f, 20 km %.2f, 100 km %.1f blocks%n", near, reach,
                    step(near, 1000), step(near, 5000), step(near, 20000), step(near, 100000));
            assertTrue(step(near, 5000) < 0.1, "5 km at reach " + reach);
            assertTrue(step(near, 20000) < 1.6, "20 km at reach " + reach);
            assertTrue(step(near, 5000) * 100 < step(GAME_NEAR, 5000));
            assertEquals(near, own.m32() / (own.m22() - 1), near * 1e-3, "the plane the matrix has");
        }
    }

    @Test void surfacesOneAndSixteenBlocksApartCollideInTheGamesDepthAndNotInFarTerrainsOwn() {
        Matrix4f game = game(70), own = own(game, VIEW_REACH);
        for (double distance : new double[] {5000, 20000}) {
            for (double gap : new double[] {1, 16}) {
                double before = gameCollides(game, distance, gap), after = ownCollides(own, distance, gap);
                System.out.printf("DEPTH_COLLIDE %.0f km, %.0f blocks apart: game %.0f%%, own %.0f%%%n", distance / 1000, gap, before * 100, after * 100);
                assertEquals(0.0, after, "own depth keeps " + gap + " blocks apart at " + distance);
                // 16 blocks at 5 km is half a game step; the rest are well over it.
                assertTrue(before >= (distance == 5000 && gap == 16 ? 0.3 : 0.9), "the game's depth fought at " + distance + " by " + gap + ": " + before);
            }
        }
    }

    @Test void orderIsKeptNearerFirst() {
        Matrix4f own = own(game(70), VIEW_REACH);
        for (double distance : new double[] {2000, 5000, 20000})
            for (int i = 0; i < 200; i++) assertTrue(inOwn(own, distance + i) < inOwn(own, distance + i + 1), distance + " + " + i);
    }

    @Test void atOneHundredKilometresOnlyTheLargerGapIsKept() {
        Matrix4f game = game(70), own = own(game, VIEW_REACH);
        System.out.printf("DEPTH_COLLIDE 100 km: 1 block game %.0f%% own %.0f%%; 16 blocks game %.0f%% own %.0f%%%n",
                gameCollides(game, 100000, 1) * 100, ownCollides(own, 100000, 1) * 100, gameCollides(game, 100000, 16) * 100, ownCollides(own, 100000, 16) * 100);
        assertEquals(0.0, ownCollides(own, 100000, 16));
        assertEquals(1.0, gameCollides(game, 100000, 16));
    }

    /** A zoom narrows the field of view, which changes only the projection's first two diagonal entries, so depth stays as it was. */
    @Test void zoomMagnifiesButDoesNotChangeDepth() {
        Matrix4f wide = game(70), own = own(wide, VIEW_REACH);
        for (double fov : new double[] {1, 2, 5, 10}) {
            Matrix4f zoomed = game(fov), zoomedOwn = own(zoomed, VIEW_REACH);
            assertEquals(wide.m22(), zoomed.m22());
            assertEquals(wide.m32(), zoomed.m32());
            // A narrower view puts the nearest far terrain deeper in view, so its near plane is no nearer and its depth no coarser.
            assertTrue(FarPlainDepth.near(zoomed, VIEW_REACH) >= FarPlainDepth.near(wide, VIEW_REACH));
            assertEquals(gameCollides(wide, 5000, 1), gameCollides(zoomed, 5000, 1));
            assertEquals(0.0, ownCollides(zoomedOwn, 5000, 1));
            // What it changes is how big a block is on screen, so a fight a pixel wide at 70 degrees is a patch of pixels.
            double pixelsPerBlock = zoomed.m11() * 1080 / 2 / 5000;
            System.out.printf("ZOOM fov %.0f deg: a block at 5 km spans %.1f px of a 1080p screen; game depth fights %.0f%% of 1-block pairs, own %.0f%%%n",
                    fov, pixelsPerBlock, gameCollides(zoomed, 5000, 1) * 100, ownCollides(zoomedOwn, 5000, 1) * 100);
            if (fov <= 2) assertTrue(pixelsPerBlock > 5, "a block is several pixels wide when zoomed, so a fight shows");
        }
    }

    /** The near plane the renderer really uses at a default 70 degree view and reach: the depth steps the PR's table is made of. */
    @Test void theNearPlaneTheRendererWorksOutGivesTheStepsTheDocsQuote() {
        Matrix4f game = game(70);
        float near = FarPlainDepth.near(game, VIEW_REACH);
        System.out.printf("DEPTH_NEAR 70 degrees, 16:9, reach %.0f: near %.1f; step 1 km %.4f, 5 km %.3f, 20 km %.2f blocks%n", VIEW_REACH, near,
                step(near, 1000), step(near, 5000), step(near, 20000));
        assertTrue(near > 40 && near < 48, "near " + near);
        assertTrue(step(near, 5000) < 0.04);
        assertTrue(step(near, 20000) < 0.7);
        // A wide, a narrow and a very wide view: the plane follows the view, and never leaves the nearest far terrain clipped (see FarPlainProjectionTest).
        assertTrue(FarPlainDepth.near(game(110), VIEW_REACH) < near);
        assertTrue(FarPlainDepth.near(game(30), VIEW_REACH) > near);
    }

    @Test void theFloatFormatAloneWouldNotHaveHelped() {
        assertEquals(1.0 / (1 << 24), Math.ulp(0.99f), 0, "a 32-bit float above one half steps as a 24-bit integer does");
    }
}
