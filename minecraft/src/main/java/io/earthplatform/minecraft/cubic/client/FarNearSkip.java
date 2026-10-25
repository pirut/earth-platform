package io.earthplatform.minecraft.cubic.client;

/**
 * Far columns the own-depth far pass would draw without a single pixel. Under a shader pack with
 * Voxy support, the far program drops every fragment closer than the game's blocks' reach, across
 * the ground ({@code length(earth_position.xz) < earth_NearReach} in {@link FarShaders}); a column
 * whose every corner lies that close, its faces grown past their edges included, is all dropped,
 * as every point of its faces lies between its corners. Such columns are left out of that pass
 * (the near pass still draws them). {@code -Dearthplatform.far.skipNear=false} draws them anyway.
 */
final class FarNearSkip {
    static final boolean ENABLED = !"false".equals(System.getProperty("earthplatform.far.skipNear"));
    /** The most {@code earth_Grow} in {@link FarShaders#VERTEX} ever is (see {@link FarGpu#grow}): how far a face reaches past its edges per block of distance. */
    static final double GROW = 0.0006;
    /** Blocks kept clear of the reach on top of that, for float rounding in the shader. */
    static final double MARGIN = 2.0;

    private FarNearSkip() {}

    /**
     * Whether every fragment of a column in the box (relative to nothing; the camera at cx, cy, cz)
     * is dropped by the far pass's reach test.
     */
    static boolean dropped(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
            double cx, double cy, double cz, double reach) {
        double fx = Math.max(Math.abs(minX - cx), Math.abs(maxX - cx)), fz = Math.max(Math.abs(minZ - cz), Math.abs(maxZ - cz));
        double across = Math.sqrt(fx * fx + fz * fz);
        double fy = Math.max(Math.abs(minY - cy), Math.abs(maxY - cy));
        // A corner moves by up to GROW times its distance along each of the face's two directions.
        double grow = GROW * Math.sqrt(across * across + fy * fy) * Math.sqrt(2.0);
        return across + grow + MARGIN < reach;
    }
}
