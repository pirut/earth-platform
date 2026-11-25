package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.*;

import io.earthplatform.minecraft.cubic.Cubic;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector4d;
import org.junit.jupiter.api.Test;

/**
 * Far terrain's own projection and its near plane, for the projections the game really hands the renderer. The game folds
 * its view bobbing, hurt tilt and nausea distortion into the projection before the level is drawn (it is
 * {@code perspective * bob * nausea}), so the depth row of the matrix has terms in x and y, not only in z, and the field of
 * view and aspect are whatever the player, a zoom mod or the window made them.
 */
class FarPlainProjectionTest {
    private static final double[] DISTANCES = {1_000, 5_000, 30_000, 100_000};

    private static Matrix4f perspective(double fovDegrees, double aspect) {
        return new Matrix4f().perspective((float) Math.toRadians(fovDegrees), (float) aspect, 0.05F, 2048);
    }

    /** What GameRenderer does after the perspective: a step's bob (up to 5 degrees of tilt and a shift), as the game's {@code bobView}. */
    private static Matrix4f bobbing(Matrix4f projection) {
        return new Matrix4f(projection).translate(0.31F, -0.27F, 0).rotateZ((float) Math.toRadians(1.8)).rotateX((float) Math.toRadians(5));
    }

    /** The game's nausea and portal distortion: a rotation, a stretch of one axis, and the rotation back (a shear of the view). */
    private static Matrix4f nausea(Matrix4f projection, double degrees, float stretch) {
        float root = (float) Math.sqrt(0.5);
        org.joml.AxisAngle4f turn = new org.joml.AxisAngle4f((float) Math.toRadians(degrees), 0, root, root);
        return new Matrix4f(projection).rotate(turn).scale(1.0F / stretch, 1, 1).rotate(new org.joml.AxisAngle4f(-turn.angle, 0, root, root));
    }

    /** A pure tilt: the projection of a view rolled or pitched by this many degrees. */
    private static Matrix4f tilted(Matrix4f projection, double pitch, double roll) {
        return new Matrix4f(projection).rotateX((float) Math.toRadians(pitch)).rotateZ((float) Math.toRadians(roll));
    }

    private static Matrix4f[] projections() {
        Matrix4f plain = perspective(70, 16.0 / 9);
        return new Matrix4f[] {plain, bobbing(plain), nausea(plain, 37, 0.62F), nausea(bobbing(plain), 120, 0.8F), tilted(plain, 5, 0),
            tilted(plain, 0, 5), tilted(plain, 5, 5), tilted(perspective(1, 16.0 / 9), 5, 0), tilted(perspective(110, 32.0 / 9), 5, 5)};
    }

    /**
     * A point at this distance from the eye along the ray through a place on the screen, in the projection's own space. The
     * eye is the apex every ray passes through, where the clip w is nothing.
     */
    private static Vector3d along(Matrix4f projection, double ndcX, double ndcY, double distance) {
        Matrix4d inverse = new Matrix4d(projection).invert();
        Vector4d near = inverse.transform(new Vector4d(ndcX, ndcY, -1, 1)), far = inverse.transform(new Vector4d(ndcX, ndcY, 1, 1));
        Vector3d from = new Vector3d(near.x / near.w, near.y / near.w, near.z / near.w);
        Vector3d direction = new Vector3d(far.x / far.w, far.y / far.w, far.z / far.w).sub(from).normalize();
        double wFrom = projection.m03() * from.x + projection.m13() * from.y + projection.m23() * from.z + projection.m33();
        double wAlong = projection.m03() * direction.x + projection.m13() * direction.y + projection.m23() * direction.z;
        Vector3d eye = new Vector3d(from).sub(new Vector3d(direction).mul(wFrom / wAlong));
        return eye.add(direction.mul(distance));
    }

    private static Vector4d clip(Matrix4f projection, Vector3d point) { return new Matrix4d(projection).transform(new Vector4d(point, 1)); }

    /** The depth far terrain's own projection must give a point whose clip w (the view depth the game's own depth is made of) is {@code w}. */
    private static double expectedDepth(double near, double w) {
        double far = FarRenderer.depthFar();
        return -(far + near) / (near - far) + 2 * far * near / (near - far) / w;
    }

    @Test void theRemadeProjectionKeepsEveryPixelAndRemakesOnlyItsDepthForBobbingNauseaAndTilt() {
        for (Matrix4f game : projections()) {
            assertTrue(FarPlainDepth.remappable(game));
            float near = FarPlainDepth.near(game, 192);
            assertTrue(near >= FarPlainDepth.NEAR, "a near plane worth it: " + near);
            Matrix4f own = FarPlainDepth.projection(game, near);
            assertNotNull(own);
            for (double distance : DISTANCES) for (double x : new double[] {-0.95, 0, 0.95}) for (double y : new double[] {-0.9, 0, 0.9}) {
                Vector3d point = along(game, x, y, distance);
                Vector4d before = clip(game, point), after = clip(own, point);
                assertEquals(before.w, after.w, before.w * 1e-6, "the view depth is the game's");
                assertEquals(before.x, after.x, Math.abs(before.x) * 1e-6 + 1e-6, "the pixel's column");
                assertEquals(before.y, after.y, Math.abs(before.y) * 1e-6 + 1e-6, "the pixel's row");
                double depth = after.z / after.w;
                assertTrue(depth > -1 && depth <= 1, "on screen, not clipped: " + depth + " at " + distance + " (" + x + ", " + y + ")");
                assertEquals(expectedDepth(near, after.w), depth, 2e-6, "depth is a function of the view depth alone, at " + distance);
                // The game's own projection puts this point on screen, so far terrain's must too.
                assertTrue(Math.abs(before.x / before.w) <= 1.0001 && Math.abs(before.y / before.w) <= 1.0001);
            }
        }
    }

    /** What the PR did first: it set two entries of the game's own matrix and left the rest of its depth row. */
    private static Matrix4f twoEntriesOnly(Matrix4f game, float near) {
        float far = FarRenderer.depthFar();
        return new Matrix4f(game).m22((far + near) / (near - far)).m32(2 * far * near / (near - far));
    }

    @Test void overwritingTwoEntriesOfAnInexactProjectionClippedFarTerrainTheRemakeDoesNot() {
        Matrix4f game = tilted(perspective(70, 16.0 / 9), 5, 0);
        float near = FarPlainDepth.near(game, 192);
        Matrix4f old = twoEntriesOnly(game, near), own = FarPlainDepth.projection(game, near);
        int clippedOld = 0, clippedOwn = 0;
        for (double distance : DISTANCES) for (double x : new double[] {-0.95, -0.5, 0, 0.5, 0.95}) for (double y : new double[] {-0.9, 0, 0.9}) {
            Vector3d point = along(game, x, y, distance);
            Vector4d a = clip(old, point), b = clip(own, point);
            if (Math.abs(a.z / a.w) > 1) clippedOld++;
            if (Math.abs(b.z / b.w) > 1) clippedOwn++;
        }
        assertTrue(clippedOld > 10, "the two-entry projection clips terrain a view tilted 5 degrees shows: " + clippedOld);
        assertEquals(0, clippedOwn);
    }

    @Test void thirtyKilometresOutAtFiveDegreesTheTerrainStaysAndIsNotClipped() {
        int clippedOld = 0;
        for (Matrix4f game : new Matrix4f[] {tilted(perspective(70, 16.0 / 9), 5, 0), tilted(perspective(70, 16.0 / 9), 0, 5), bobbing(perspective(70, 16.0 / 9))}) {
            float near = FarPlainDepth.near(game, 192);
            Matrix4f old = twoEntriesOnly(game, near), own = FarPlainDepth.projection(game, near);
            for (int i = -9; i <= 9; i++) for (int j = -9; j <= 9; j++) {
                Vector3d point = along(game, i / 10.0, j / 10.0, 30_000);
                Vector4d a = clip(old, point), b = clip(own, point);
                if (Math.abs(a.z / a.w) > 1) clippedOld++;
                assertTrue(Math.abs(b.z / b.w) < 1, "kept by the remake at screen " + i + ", " + j + ": " + b.z / b.w);
            }
        }
        assertTrue(clippedOld > 0, "the old projection clips some of it");
    }

    @Test void depthTellsPointsThreeBlocksApartFiveKilometresOutInAnyView() {
        for (Matrix4f game : projections()) {
            float near = FarPlainDepth.near(game, 192);
            Matrix4f own = FarPlainDepth.projection(game, near);
            // Two points three blocks apart along a ray fall on different float depths in far terrain's.
            Vector4d a = clip(own, along(game, 0.3, -0.2, 5_000)), b = clip(own, along(game, 0.3, -0.2, 5_003));
            assertTrue((float) (a.z / a.w) < (float) (b.z / b.w));
        }
    }

    @Test void projectionsItCannotRemakeLeaveFarTerrainInTheGamesDepth() {
        Matrix4f ortho = new Matrix4f().ortho(-10, 10, -10, 10, 0.05F, 2048);
        assertFalse(FarPlainDepth.remappable(ortho));
        assertNull(FarPlainDepth.projection(ortho, 40));
        assertEquals(0F, FarPlainDepth.near(ortho, 192));
        // An oblique near plane, which a mirror or portal mod makes by bending the depth row.
        Matrix4f oblique = perspective(70, 16.0 / 9).m02(0.3F).m12(-0.2F);
        assertFalse(FarPlainDepth.remappable(oblique));
        assertNull(FarPlainDepth.projection(oblique, 40));
        // A reversed depth, which maps the near plane to the far end.
        Matrix4f reversed = perspective(70, 16.0 / 9).m22(0.5F).m32(-0.05F);
        assertFalse(FarPlainDepth.remappable(reversed));
        assertFalse(FarPlainDepth.remappable(new Matrix4f()));
        assertNull(FarPlainDepth.projection(perspective(70, 16.0 / 9), 0));
        assertNull(FarPlainDepth.projection(perspective(70, 16.0 / 9), FarRenderer.depthFar()));
    }

    @Test void theDepthShareOfAPlainPerspectiveIsTheCosineOfItsCorner() {
        for (double fov : new double[] {1, 30, 70, 110}) for (double aspect : new double[] {4.0 / 3, 16.0 / 9, 32.0 / 9}) {
            double vertical = Math.tan(Math.toRadians(fov) / 2), horizontal = vertical * aspect;
            assertEquals(1 / Math.sqrt(1 + vertical * vertical + horizontal * horizontal), FarPlainDepth.depthShare(perspective(fov, aspect)), 1e-5,
                    fov + " degrees at " + aspect);
        }
    }

    /** The configurations whose nearest far terrain the near plane has to stay clear of: a normal view, a wide one, an ultrawide one, a zoom. */
    private static final double[][] VIEWS = {{110, 32.0 / 9}, {110, 16.0 / 9}, {110, 21.0 / 9}, {90, 16.0 / 9}, {70, 16.0 / 9}, {50, 16.0 / 9},
        {30, 16.0 / 9}, {30, 32.0 / 9}, {10, 16.0 / 9}, {1, 16.0 / 9}, {1, 32.0 / 9}, {110, 4.0 / 3}, {140, 16.0 / 9}};

    @Test void theNearPlaneStaysUnderEveryFarTerrainFragmentOnScreenWhateverTheFieldOfViewAndAspect() {
        for (double[] view : VIEWS) for (Matrix4f game : new Matrix4f[] {perspective(view[0], view[1]), bobbing(perspective(view[0], view[1])),
                nausea(perspective(view[0], view[1]), 60, 0.7F)}) for (float reach : new float[] {32, 96, 192, 384}) {
            float near = FarPlainDepth.near(game, reach);
            if (near < FarPlainDepth.NEAR) continue;
            Matrix4f own = FarPlainDepth.projection(game, near);
            assertNotNull(own, view[0] + " degrees at " + view[1]);
            double nearest = FarPlainDepth.nearest(reach);
            // Everywhere on screen, the corners and edges too, from the nearest far terrain out.
            for (int i = 0; i <= 20; i++) for (int j = 0; j <= 20; j++) for (double distance : new double[] {nearest, nearest * 1.3, nearest * 4, 3000}) {
                Vector4d at = clip(own, along(game, -1 + i * 0.1, -1 + j * 0.1, distance));
                assertTrue(at.z / at.w >= -1 - 1e-6, view[0] + " degrees at " + view[1] + ", reach " + reach + ", screen (" + i + "," + j + ") " + distance
                        + " blocks out: depth " + at.w + " under the near plane " + near);
            }
        }
    }

    /** The reviewer's case: 110 degrees on 32:9, where a point 199 blocks off sideways can be on screen with 40 blocks of view depth. */
    @Test void anUltrawideWideViewShowsFarTerrainNearerInDepthThanAThirdOfTheReach() {
        Matrix4f game = perspective(110, 32.0 / 9);
        float near = FarPlainDepth.near(game, 192);
        Vector4d edge = clip(game, new Vector3d(199, 0, -40));
        assertTrue(Math.abs(edge.x / edge.w) < 1 && edge.w > 0, "the point is on screen, " + edge.w + " deep");
        assertTrue(near < 40, "the plane stays under it: " + near);
        assertTrue(near >= FarPlainDepth.NEAR);
        // The first rule, 0.3 of the reach whatever the view, would have clipped it.
        assertTrue(0.3F * 192 > 40);
        Vector4d inOwn = clip(FarPlainDepth.projection(game, near), new Vector3d(199, 0, -40));
        assertTrue(inOwn.z / inOwn.w > -1, "kept: " + inOwn.z / inOwn.w);
    }

    @Test void aViewTooWideForAUsefulPlaneLeavesFarTerrainInTheGamesDepth() {
        // 170 degrees on 32:9: the corner of the screen is almost sideways, so there is nothing to put the plane at.
        Matrix4f game = perspective(170, 32.0 / 9);
        assertTrue(FarPlainDepth.near(game, 192) < FarPlainDepth.NEAR, "plane " + FarPlainDepth.near(game, 192));
    }

    @Test void theNearestFarTerrainIsTheReachSidewaysAndTheCubesOwnHeightUpAndDown() {
        assertEquals(Cubic.VERTICAL_RADIUS * 16F, FarPlainDepth.nearest(192));
        assertEquals(96F, FarPlainDepth.nearest(192));
        assertEquals(64F, FarPlainDepth.nearest(64));
        assertEquals(0F, FarPlainDepth.nearest(0));
    }

    @Test void zoomedOutToNarrowViewsGetsAFinerDepthThanAWideOne() {
        float wide = FarPlainDepth.near(perspective(110, 16.0 / 9), 192), narrow = FarPlainDepth.near(perspective(10, 16.0 / 9), 192);
        assertTrue(narrow > wide, "narrow " + narrow + " wide " + wide);
        assertEquals(0.8F * 96 * (float) FarPlainDepth.depthShare(perspective(10, 16.0 / 9)), narrow, 1e-3);
    }
}
