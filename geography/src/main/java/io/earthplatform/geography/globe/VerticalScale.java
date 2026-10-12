package io.earthplatform.geography.globe;

/**
 * Maps real elevation onto a bounded block-Y range, version {@code vertical-log-v1}.
 *
 * <p>A vanilla-height world cannot hold Everest and the Challenger Deep at one block per metre, so the relationship is
 * deliberately not linear. Elevation near sea level is close to 1:1 and higher terrain is compressed logarithmically,
 * so relative ordering and local relief survive while the highest peaks fit. Land elevation is first multiplied by the
 * Mercator scale factor (capped) so slopes keep their proportions where the projection enlarges horizontal distances.
 * Ocean depth is compressed much harder into a Minecraft-like ocean so seafloors stay playable.
 *
 * <p>For terrain with {@code elevation > 0}: {@code y = seaLevel + landKnee * ln(1 + elevation * min(k, maxScale) / landKnee)}.
 * For {@code elevation <= 0}: {@code y = seaLevel - oceanKnee * ln(1 + depth / oceanKnee)}.
 * Both branches have slope 1 per metre (times the capped scale factor on land) at sea level.
 * The result is clamped to [minSurfaceY, maxSurfaceY].
 */
public record VerticalScale(double seaLevelY, double landKnee, double maxScaleFactor, double oceanKnee, double minSurfaceY, double maxSurfaceY) {
    public static final String VERSION = "vertical-log-v1";

    /**
     * Defaults for a world spanning Y -64..2031 with vanilla sea level 63: Everest lands near Y 1790, the Challenger Deep
     * floor near Y -7, a 4 km abyssal plain near Y 3 and a 100 m continental shelf near Y 39.
     */
    public static VerticalScale tallVanilla() { return new VerticalScale(63, 600, 2.0, 10, -48, 2015); }

    public VerticalScale {
        for (double v : new double[]{seaLevelY, landKnee, maxScaleFactor, oceanKnee, minSurfaceY, maxSurfaceY})
            if (!Double.isFinite(v)) throw new IllegalArgumentException("Non-finite vertical scale parameter");
        if (landKnee <= 0 || oceanKnee <= 0 || maxScaleFactor < 1 || minSurfaceY >= seaLevelY || maxSurfaceY <= seaLevelY)
            throw new IllegalArgumentException("Invalid vertical scale");
    }

    /** Continuous surface height in block units. {@code scaleFactor} is the Mercator scale at the sample. */
    public double surfaceY(double elevationMetres, double scaleFactor) {
        if (!Double.isFinite(elevationMetres) || !(scaleFactor >= 1)) throw new IllegalArgumentException("Invalid elevation sample");
        double y;
        if (elevationMetres > 0) {
            double blocks = elevationMetres * Math.min(scaleFactor, maxScaleFactor);
            y = seaLevelY + landKnee * Math.log1p(blocks / landKnee);
        } else {
            y = seaLevelY - oceanKnee * Math.log1p(-elevationMetres / oceanKnee);
        }
        return Math.max(minSurfaceY, Math.min(maxSurfaceY, y));
    }

    /** Inverse of {@link #surfaceY} inside the unclamped range; used for display and teleport targets. */
    public double elevationMetres(double surfaceY, double scaleFactor) {
        if (!Double.isFinite(surfaceY) || !(scaleFactor >= 1)) throw new IllegalArgumentException("Invalid surface height");
        double above = surfaceY - seaLevelY;
        if (above > 0) return landKnee * Math.expm1(above / landKnee) / Math.min(scaleFactor, maxScaleFactor);
        return -oceanKnee * Math.expm1(-above / oceanKnee);
    }

    /** Local vertical exaggeration d(blockY)/d(elevation metres); below 1 means compressed. */
    public double verticalGain(double elevationMetres, double scaleFactor) {
        if (elevationMetres > 0) {
            double s = Math.min(scaleFactor, maxScaleFactor);
            return s / (1 + elevationMetres * s / landKnee);
        }
        return 1 / (1 - elevationMetres / oceanKnee);
    }
}
