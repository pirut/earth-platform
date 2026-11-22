package io.earthplatform.minecraft.cubic.compat;

import io.earthplatform.minecraft.cubic.CubeWorld;
import net.minecraft.core.BlockPos;

/**
 * Cubic-world constants and helpers for Immersive Vehicles (MTS).
 *
 * <p>MTS computes atmospheric air density from {@code world.getMaxHeight()} via
 * {@code 1.225 * 2^(-(y - seaLevel) / (500 * maxHeight / 256))}. Against the cubic level's
 * 16-block carrier that half-density height collapses to 31.25 blocks, stripping aircraft of lift
 * and thrust just above sea level. Using 4352 (17 * 256) yields an 8500 m half-density height so
 * sea-level flight has standard 1.225 kg/m^3 density while high mountain ranges remain flyable.
 */
public final class ImmersiveVehiclesCompat {
    /** Effective world height reported to MTS for atmospheric density (half-density height = 8500 m). */
    public static final int ATMOSPHERE_HEIGHT = 4352;
    /** Standard sea-level air density used by MTS (kg/m^3). */
    public static final double SEA_LEVEL_AIR_DENSITY = 1.225;

    private ImmersiveVehiclesCompat() {}

    /** MTS air-density formula for a given altitude, sea level and reported world max height. */
    public static double airDensity(double y, double seaLevel, long maxHeight) {
        double halfHeight = 500.0 * maxHeight / 256.0;
        return SEA_LEVEL_AIR_DENSITY * Math.pow(2.0, -(y - seaLevel) / halfHeight);
    }

    /**
     * During MTS's downward ground-height scan, unloaded cubes below the column's known sky top
     * count as solid ground so high-altitude aircraft do not scan thousands of unloaded void-air
     * blocks down to {@link CubeWorld#MIN_Y}.
     */
    public static boolean isEmptyForGroundScan(CubeWorld cubes, BlockPos pos, boolean vanillaEmpty) {
        if (cubes.isLoaded(pos)) return vanillaEmpty;
        return pos.getY() > cubes.skyTop(pos.getX(), pos.getZ());
    }
}
