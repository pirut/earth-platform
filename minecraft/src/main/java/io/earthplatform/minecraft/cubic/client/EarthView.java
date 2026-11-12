package io.earthplatform.minecraft.cubic.client;

/**
 * The Earth's curve, for the eye only: high above the ground, far terrain and its water are drawn
 * dropping away from the camera as they would on a globe of the Earth's mean radius. The world
 * itself stays flat, and so do its collisions and saves. Off unless
 * {@code -Dearthplatform.far.curvature=true}, and never under a shader pack, whose own water and
 * positions would no longer meet far terrain's.
 */
final class EarthView {
    static final boolean ENABLED = Boolean.getBoolean("earthplatform.far.curvature");
    /** The Earth's mean radius, in meters. */
    static final double RADIUS = 6_371_000;
    /** How far around the camera, in blocks, the ground stays flat; the drop starts from nothing at its edge. */
    static final double FLAT = 2_000;
    /**
     * The widest flat water face drawn under curvature, in blocks. Its corners follow the curve and
     * its middle does not: a 1,024-block face's diagonal sags at most about 4.2 cm below it.
     */
    static final int WATER_GRID = 1024;

    private EarthView() {}

    /**
     * The drop per square block past {@link #FLAT}, for a camera this high above the ground: none up
     * to 1,500 blocks, the Earth's own by 6,000, and none under a shader pack.
     */
    static float curvature(double heightAboveGround, double blocksPerMetre, boolean shaderPack) {
        if (shaderPack || !Double.isFinite(heightAboveGround) || !(blocksPerMetre > 0)) return 0;
        double t = Math.clamp((heightAboveGround - 1500) / 4500, 0, 1);
        return (float) (t * t * (3 - 2 * t)) * full(blocksPerMetre);
    }

    /** The Earth's own drop per square block: d²/2R metres at d metres, where both are blocksPerMetre times as many blocks. */
    static float full(double blocksPerMetre) { return (float) (1 / (2 * RADIUS * blocksPerMetre)); }

    /** How far below its flat place a point this far across from the camera is drawn, in blocks. */
    static double drop(double x, double z, float curvature) {
        double r = Math.max(0, Math.hypot(x, z) - FLAT);
        return curvature * r * r;
    }
}
