package io.earthplatform.minecraft.cubic.client;

import org.joml.Matrix4f;

/** Camera-relative, visual-only curvature using the Earth's mean radius. */
public final class EarthView {
    public static final double RADIUS = 6_371_000;
    /** At full Earth curvature, a cell's diagonal chord deviates by at most about 4.2 cm. */
    public static final int WATER_GRID = 1024;
    private EarthView() {}

    public static float curvature(double heightAboveGround, double blocksPerMetre, boolean shaderPack) {
        // Packs reconstruct water/world positions using their own inverse matrices. Until that
        // whole pipeline shares the warp, retain their flat projection rather than split shores.
        if (shaderPack || !Boolean.parseBoolean(System.getProperty("earthplatform.far.curvature", "true"))) return 0;
        if (!Double.isFinite(heightAboveGround) || !(blocksPerMetre > 0)) return 0;
        double t = Math.clamp((heightAboveGround - 1500) / 4500, 0, 1);
        return (float) (t * t * (3 - 2 * t) / (2 * RADIUS * blocksPerMetre * blocksPerMetre));
    }

    /** Leave a two-kilometre radius around the camera undistorted, with a continuous join. */
    public static double drop(double x, double z, float curvature) {
        double r = Math.max(0, Math.hypot(x, z) - 2000);
        return curvature * r * r;
    }

    /** Local tangent for a water column's existing block-format shader. */
    public static Matrix4f water(Matrix4f view, double x, double z, float curvature) {
        double r = Math.hypot(x, z), outside = Math.max(0, r - 2000);
        double gradient = r > 0 ? 2 * curvature * outside / r : 0;
        float sx = (float) (gradient * x), sz = (float) (gradient * z);
        Matrix4f bend = new Matrix4f().m01(-sx).m21(-sz).m31((float) (sx * x + sz * z - drop(x, z, curvature)));
        return new Matrix4f(view).mul(bend);
    }
}
