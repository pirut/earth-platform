package io.earthplatform.minecraft.cubic.client;

/**
 * The few places a world position meets a float. Earth worlds run to about 20 million blocks from the
 * origin, where a float holds whole blocks only (its spacing is 1 above 8.4 million and 2 above 16.7
 * million), so a world position turned into a float and then moved by the camera's own float jumps a
 * whole block at a time. Everything the GPU gets is therefore the difference between two places taken
 * in {@code double} or {@code int} first, which is small near the camera, and only then made a float.
 *
 * <p>Far terrain's vertex shader does the same sum with the camera split in a whole block and a
 * fraction (see {@link FarGpu}), which {@link #origin} mirrors for the tests.
 */
final class CameraSpace {
    private CameraSpace() {}

    /** The whole block a camera at {@code camera} is in, as the vertex shader's {@code earth_CameraBlock}. */
    static int block(double camera) { return (int) Math.floor(camera); }

    /** How far into its block the camera is, as the vertex shader's {@code earth_CameraFraction}: small, so a float keeps it to a billionth of a block. */
    static float fraction(double camera, int block) { return (float) (camera - block); }

    /** Where a world position lies from the camera on one axis: subtracted in double, so only the small difference is rounded. */
    static float relative(double world, double camera) { return (float) (world - camera); }

    /**
     * What the vertex shader works out for a column's origin: {@code vec3(column.xyz - earth_CameraBlock) - earth_CameraFraction}.
     * The subtraction of the two whole-block numbers is exact (integers), the fraction is small, so the result is as exact as a
     * float holds a number of its size.
     */
    static float origin(int columnOrigin, int cameraBlock, float cameraFraction) {
        return (float) (columnOrigin - cameraBlock) - cameraFraction;
    }

    /**
     * Where the camera lies from a cube's corner, on one axis, for sorting that cube's translucent faces by distance: their
     * corners are measured from the same corner. Rounding the camera to a float first, as {@code (float) camera - corner}
     * does, put it up to a block (two past 16.7 million) from where it is, so glass and water sorted from the wrong side.
     */
    static float cubeRelative(double camera, int cubeCorner) { return (float) (camera - cubeCorner); }

    /**
     * Where the camera lies from a cube's corner, on all three axes, as {@link CubeMesher.Snapshot} carries it: the cube's
     * translucent faces are sorted from this origin (see {@link CubeMesher#sortTranslucent}), measured from the same corner.
     */
    static float[] cubeCamera(double cameraX, double cameraY, double cameraZ, int cubeX, int cubeY, int cubeZ) {
        return new float[] {cubeRelative(cameraX, cubeX << 4), cubeRelative(cameraY, cubeY << 4), cubeRelative(cameraZ, cubeZ << 4)};
    }
}
