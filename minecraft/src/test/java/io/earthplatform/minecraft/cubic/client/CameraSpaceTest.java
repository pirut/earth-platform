package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector4d;
import org.junit.jupiter.api.Test;

/**
 * Far terrain and the cubes near it must draw the same picture wherever the player stands: at the origin and
 * at the 11.7 million blocks where the Earth's far side of the Atlantic is. A float holds whole blocks there,
 * so any world position that passes through one before the camera is taken off it jumps a block at a time, and a
 * zoomed view (a spyglass, a zoom key) magnifies each jump to many pixels.
 */
class CameraSpaceTest {
    /** Where JR plays (about 11.7 million west, 5.9 million north), a sub-block fraction on every axis. */
    private static final double[] FAR = {-11_760_398.37, 1362.5, -5_906_993.81};
    private static final double[] ORIGIN = {0.37, 1362.5, 0.81};
    private static final double[][] CAMERAS = {FAR, ORIGIN};

    /** The column origins and offsets in one column the vertex shader adds up, near and far, on each axis. */
    private static final int[] COLUMN_OFFSETS = {-65_536, -4096, -1024, -300, -16, 0, 16, 64, 300, 1024, 4096, 65_536};
    private static final float[] LOCAL = {0.0F, 0.0625F, 0.5F, 1.0F, 15.5F, 16.0F, 255.0F, 1024.0F};

    /** What far terrain's vertex shader works out for one axis of a vertex: the column's origin from the camera, plus where it lies in the column. */
    private static float shaderPosition(int columnOrigin, float local, double camera) {
        int block = CameraSpace.block(camera);
        return CameraSpace.origin(columnOrigin, block, CameraSpace.fraction(camera, block)) + local;
    }

    /** What the vertex position was before the camera was taken off it: a world position as a float, minus the camera as a float (as a 1.21 float renderer would). */
    private static float absoluteFloatPosition(int columnOrigin, float local, double camera) {
        return ((float) columnOrigin + local) - (float) camera;
    }

    private static int near(double camera, int offset) { return (int) Math.floor(camera) + offset; }

    @Test void vertexPositionsFromTheCameraAreRightToAThousandthOfABlockWhereverThePlayerStands() {
        double worst = 0;
        for (double[] camera : CAMERAS) for (double c : camera) for (int offset : COLUMN_OFFSETS) for (float local : LOCAL) {
            int column = near(c, offset) & ~15;
            double exact = (double) column + local - c;
            double error = Math.abs(shaderPosition(column, local, c) - exact);
            // A float holds a number of its size to this much, whatever the camera's: that is the most a vertex can be off by.
            double spacing = Math.ulp((float) Math.max(Math.abs(exact), Math.abs((double) column - Math.floor(c)) + local));
            assertTrue(error <= Math.max(1e-3, spacing), "off by " + error + " at " + exact + " (camera " + c + ", column " + column + ")");
            if (Math.abs(exact) <= 4096) worst = Math.max(worst, error);
        }
        assertTrue(worst < 1e-3, "worst error within 4,096 blocks " + worst);
    }

    @Test void theSameEverywhere() {
        // The origin's camera has the same fraction as the far one's, so the same vertex lands in the same place from both.
        double farCamera = -11_760_398.37, nearCamera = 0.63;
        for (int offset : new int[] {-300, -16, 0, 5, 16, 300}) for (float local : LOCAL) {
            float far = shaderPosition((int) Math.floor(farCamera) + offset, local, farCamera);
            float near = shaderPosition(offset, local, nearCamera);
            assertEquals(near, far, 2e-6 * Math.max(1, Math.abs(near)), "offset " + offset + " local " + local);
        }
    }

    @Test void aVertexHoldsStillAsTheCameraCreepsASubBlockAtATime() {
        // A vertex 100 blocks off, seen from a camera moving a hundredth of a block at a time along each axis.
        for (double[] camera : CAMERAS) for (int axis = 0; axis < 3; axis++) {
            int column = near(camera[axis], 96) & ~15;
            float last = Float.NaN;
            for (int step = 0; step <= 200; step++) {
                double moved = camera[axis] + step * 0.01;
                float at = shaderPosition(column, 3.25F, moved);
                if (step > 0) assertEquals(-0.01, at - last, 1e-3, "step " + step + " on axis " + axis + " from " + camera[axis]);
                last = at;
            }
        }
    }

    @Test void theWorldPositionAsAFloatJumpsAWholeBlockAtATimeAtTheFarCamera() {
        // The check above has teeth: the absolute-float way of working it out misses by up to a block there, and by nothing at the origin.
        double worst = 0, worstOrigin = 0;
        for (double[] camera : CAMERAS) for (int step = 0; step <= 400; step++) {
            double moved = camera[0] + step * 0.01;
            int column = near(camera[0], 96) & ~15;
            double exact = (double) column + 3.25 - moved;
            double error = Math.abs(absoluteFloatPosition(column, 3.25F, moved) - exact);
            if (camera == FAR) worst = Math.max(worst, error); else worstOrigin = Math.max(worstOrigin, error);
        }
        assertTrue(worst > 0.4, "absolute floats at 11.7 million blocks were off by only " + worst);
        assertTrue(worstOrigin < 1e-4, "absolute floats at the origin " + worstOrigin);
    }

    /**
     * The projected picture: a vertex through the game's model-view (the camera's rotation, with no position in it) and a
     * projection zoomed to a 1 degree field of view, in pixels of a 1920 by 1080 screen, against the same vertex worked out
     * in doubles.
     */
    @Test void aZoomedViewPutsVerticesWithinAHundredthOfAPixelWhereverThePlayerStands() {
        float fov = (float) Math.toRadians(1.0);
        Matrix4f projection = new Matrix4f().perspective(fov, 16.0F / 9.0F, 0.05F, 600_000.0F);
        Matrix4f modelView = new Matrix4f().rotateX((float) Math.toRadians(11)).rotateY((float) Math.toRadians(-130));
        Matrix4d exactView = new Matrix4d().rotateX(Math.toRadians(11)).rotateY(Math.toRadians(-130));
        Matrix4d exactProjection = new Matrix4d().perspective(fov, 16.0 / 9.0, 0.05, 600_000.0);
        double worstCorrect = 0, worstAbsolute = 0;
        for (double[] camera : CAMERAS) {
            Vector3d look = new Vector3d(0, 0, -1);
            exactView.invert(new Matrix4d()).transformDirection(look);
            for (double along : new double[] {400, 2000, 9000}) for (double sideways : new double[] {-0.3, 0, 0.4}) {
                // A vertex where the camera is looking, so it lands on screen, with a little across.
                Vector3d at = new Vector3d(camera[0] + look.x * along + sideways * look.z * along * 0.01, camera[1] + look.y * along,
                        camera[2] + look.z * along - sideways * look.x * along * 0.01);
                int[] block = {(int) Math.floor(at.x) & ~15, (int) Math.floor(at.y) & ~15, (int) Math.floor(at.z) & ~15};
                float[] local = {(float) (at.x - block[0]), (float) (at.y - block[1]), (float) (at.z - block[2])};
                Vector4d exact = project(exactProjection, exactView, at.x - camera[0], at.y - camera[1], at.z - camera[2]);
                // As the vertex shader does it: relative position in floats, then the model-view and the projection.
                var relative = new org.joml.Vector4f(shaderPosition(block[0], local[0], camera[0]), shaderPosition(block[1], local[1], camera[1]),
                        shaderPosition(block[2], local[2], camera[2]), 1.0F);
                relative.mul(modelView).mul(projection);
                var absolute = new org.joml.Vector4f(absoluteFloatPosition(block[0], local[0], camera[0]), absoluteFloatPosition(block[1], local[1], camera[1]),
                        absoluteFloatPosition(block[2], local[2], camera[2]), 1.0F);
                absolute.mul(modelView).mul(projection);
                worstCorrect = Math.max(worstCorrect, pixels(relative, exact));
                worstAbsolute = Math.max(worstAbsolute, pixels(absolute, exact));
            }
        }
        assertTrue(worstCorrect < 0.01, "camera-relative vertex off by " + worstCorrect + " pixels");
        assertTrue(worstAbsolute > 10, "absolute-float vertices were off by only " + worstAbsolute + " pixels at 1 degree");
    }

    private static Vector4d project(Matrix4d projection, Matrix4d view, double x, double y, double z) {
        return new Vector4d(x, y, z, 1).mul(view).mul(projection);
    }

    /** The distance on a 1920 by 1080 screen between two clip positions, in pixels. */
    private static double pixels(org.joml.Vector4f got, Vector4d exact) {
        double ex = exact.x / exact.w * 960, ey = exact.y / exact.w * 540;
        double gx = (double) got.x / got.w * 960, gy = (double) got.y / got.w * 540;
        return Math.hypot(gx - ex, gy - ey);
    }

    /** What the cubes' translucent faces are sorted from: where the camera lies from the cube's corner. */
    @Test void translucentFacesSortFromWhereTheCameraReallyIs() {
        double worst = 0, worstLegacy = 0;
        for (double[] camera : CAMERAS) for (int axis = 0; axis < 3; axis++) for (int cube = -20; cube <= 20; cube += 5) {
            int corner = (((int) Math.floor(camera[axis]) >> 4) + cube) << 4;
            double exact = camera[axis] - corner;
            double error = Math.abs(CameraSpace.cubeRelative(camera[axis], corner) - exact);
            double legacy = Math.abs(((float) camera[axis] - corner) - exact);
            if (camera == FAR) { worst = Math.max(worst, error); worstLegacy = Math.max(worstLegacy, legacy); }
            assertTrue(error < 1e-4, "off by " + error + " at " + exact);
        }
        assertTrue(worst < 1e-4);
        // The old sum, (float) camera - corner, was off by up to a block at the far camera: the test fails it.
        assertTrue(worstLegacy > 0.1, "the old sort origin was off by only " + worstLegacy);
    }

    /** Depth does not get worse with a zoom: it follows the planes alone, so a narrow view cannot be what makes layers fight. */
    @Test void zoomLeavesTheDepthRangeAlone() {
        Matrix4f wide = new Matrix4f().perspective((float) Math.toRadians(70), 16.0F / 9.0F, 0.05F, 600_000.0F);
        Matrix4f zoomed = new Matrix4f().perspective((float) Math.toRadians(1), 16.0F / 9.0F, 0.05F, 600_000.0F);
        assertEquals(wide.m22(), zoomed.m22());
        assertEquals(wide.m32(), zoomed.m32());
        assertEquals(0.05, FarRenderer.nearPlane(zoomed), 1e-6);
        // The step of a 24-bit depth buffer at 5 km: tens of blocks, the same zoomed or not, so any fight between layers is the planes'.
        double near = 0.05, far = 600_000.0, distance = 5000;
        double step = distance * distance * (far - near) / (far * near * ((1 << 24) - 1));
        assertTrue(step > 10 && step < 100, "step " + step);
    }
}
