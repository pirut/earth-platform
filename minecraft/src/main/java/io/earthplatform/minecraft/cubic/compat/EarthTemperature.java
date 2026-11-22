package io.earthplatform.minecraft.cubic.compat;

import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import javax.annotation.Nullable;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Non-blocking real-world air temperature and surface-relative depth for temperature mod
 * compatibility (Homeostatic, Cold Sweat, Tough as Nails). Reads CHELSA climate from memory alone
 * when resident, falling back to {@link EarthGenerator}'s latitude and altitude model so tick
 * threads never block on disk or network I/O.
 */
public final class EarthTemperature {
    /** Homeostatic MC-scale linear constants (inverse of TempHelper.convertMcTemp in °F/°C). */
    private static final double HOMEOSTATIC_F_OFFSET = 25.27027027;
    private static final double HOMEOSTATIC_F_SCALE = 44.86486486;
    private static final double HOMEOSTATIC_C_FACTOR = 0.5556;

    /** Cold Sweat MC-scale factor: 1.0 MC unit = 25 °C (see Temperature.convert). */
    private static final double COLD_SWEAT_DEG_PER_MC = 25.0;
    /** Cold Sweat diurnal half-amplitude in MC units for dry (~±11.25 °C) and humid (~±7 °C) biomes. */
    private static final double COLD_SWEAT_DRY_SWING = 0.45;
    private static final double COLD_SWEAT_WET_SWING = 0.28;

    /** Tough as Nails TemperatureLevel enum ordinals (0=ICY, 1=COLD, 2=NEUTRAL, 3=WARM, 4=HOT). */
    public static final int TAN_ICY = 0, TAN_COLD = 1, TAN_NEUTRAL = 2, TAN_WARM = 3, TAN_HOT = 4;

    private EarthTemperature() {}

    /** Whether {@code level} is a server-side Earth cubic world backed by {@link EarthGenerator}. */
    public static boolean hasEarth(@Nullable Level level) {
        return level != null
                && CubicLevel.of(level) instanceof ServerCubeWorld cubes
                && cubes.generator() instanceof EarthGenerator;
    }

    /**
     * Real-world air temperature in °C at block {@code (x, y, z)}, or {@link Double#NaN} when
     * {@code level} is not a server-side Earth cubic world. Never performs disk or network I/O.
     */
    public static double celsius(@Nullable Level level, int x, int y, int z) {
        if (level != null
                && CubicLevel.of(level) instanceof ServerCubeWorld cubes
                && cubes.generator() instanceof EarthGenerator earth) {
            return earth.airTemperature(x, y, z);
        }
        return Double.NaN;
    }

    /**
     * Blocks below the ground surface at {@code (x, y, z)}: positive underground, zero at the top
     * solid block, negative above ground. Uses {@link CubeWorld#heightTop} so caves and basins
     * behave accurately at any Earth elevation (from the Dead Sea to high mountain ranges).
     */
    public static int depthBelowSurface(@Nullable Level level, int x, int y, int z) {
        if (level == null) return 0;
        CubeWorld cubes = CubicLevel.at(level, x, z);
        int surfaceY = cubes != null
                ? cubes.heightTop(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
                : level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        return surfaceY - y;
    }

    /** Maps real °C to Minecraft's biome temperature scale (0 °C = 0.15, 20 °C = 0.80, 40 °C = 1.45). */
    public static float mcTemperature(double celsius) {
        return (float) (0.15 + celsius * 0.0325);
    }

    /**
     * Maps real °C to Homeostatic's internal MC temperature unit, exact inverse of Homeostatic's
     * {@code TempHelper.convertMcTemp(mc, false)}: 0 °C = 0.150, 23.9 °C ≈ 1.108 (PARITY).
     */
    public static float homeostatic(double celsius) {
        double fahrenheit = celsius / HOMEOSTATIC_C_FACTOR + 32.0;
        return (float) ((fahrenheit - HOMEOSTATIC_F_OFFSET) / HOMEOSTATIC_F_SCALE);
    }

    /** Maps real °C to Cold Sweat's internal MC temperature unit (0 °C = 0.0, 25 °C = 1.0). */
    public static double coldSweat(double celsius) {
        return celsius / COLD_SWEAT_DEG_PER_MC;
    }

    /**
     * Cold Sweat MC temperature at {@code dayTime}, adding a realistic diurnal cycle around the
     * real CHELSA mean temperature (larger day/night swing in dry biomes than humid ones).
     */
    public static double coldSweatDiurnal(double celsius, long dayTime, boolean hasPrecipitation) {
        double swing = hasPrecipitation ? COLD_SWEAT_WET_SWING : COLD_SWEAT_DRY_SWING;
        return coldSweat(celsius) + swing * Math.sin(dayTime * Math.PI / 12000.0);
    }

    /**
     * Maps real °C to Tough as Nails's 5-tier {@code TemperatureLevel} ordinal:
     * {@code < 0 °C -> ICY}, {@code < 9 °C -> COLD}, {@code < 22 °C -> NEUTRAL},
     * {@code < 28 °C -> WARM}, {@code >= 28 °C -> HOT}.
     */
    public static int toughAsNailsOrdinal(double celsius) {
        if (celsius < 0.0) return TAN_ICY;
        if (celsius < 9.0) return TAN_COLD;
        if (celsius < 22.0) return TAN_NEUTRAL;
        if (celsius < 28.0) return TAN_WARM;
        return TAN_HOT;
    }
}
