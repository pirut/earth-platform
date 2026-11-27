package io.earthplatform.minecraft.cubic;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;

/**
 * Rain or snow in a cubic level follows the biome's own climate. Vanilla cools every biome above
 * Y 80 and so snows on temperate land from about 600 metres up, but the Earth's biomes already
 * come from the real climate at that height, and a snowy biome still snows. The same goes for
 * water freezing and snow settling: vanilla asks the altitude-cooled temperature, which would ice
 * over a mild river 300 metres up (sea level is only 18 blocks under Y 80), so a cubic level asks
 * the biome's own temperature instead.
 */
public final class CubicWeather {
    /** Vanilla's line between rain and snow, Biome.warmEnoughToRain. */
    private static final float SNOW_BELOW = 0.15F;

    private CubicWeather() {}

    public static Biome.Precipitation precipitation(Level level, Biome biome, Biome.Precipitation vanilla) {
        return vanilla == Biome.Precipitation.SNOW && level != null && CubicLevel.of(level) != null
                && biome.getBaseTemperature() >= SNOW_BELOW ? Biome.Precipitation.RAIN : vanilla;
    }

    /**
     * Whether it is too warm for water to freeze or snow to settle at a spot. In a cubic level this is
     * the biome's own temperature, with no cooling for height. Anywhere else it is vanilla's answer.
     */
    public static boolean warmEnoughToRain(LevelReader level, Biome biome, boolean vanilla) {
        return level instanceof Level l && CubicLevel.of(l) != null ? warmEnough(biome.getBaseTemperature()) : vanilla;
    }

    /** The temperature line itself, the same one {@link #precipitation} uses. */
    static boolean warmEnough(float baseTemperature) {
        return baseTemperature >= SNOW_BELOW;
    }
}
