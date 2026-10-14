package io.earthplatform.minecraft.cubic;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * Rain or snow in a cubic level follows the biome's own climate. Vanilla cools every biome above
 * Y 80 and so snows on temperate land from about 600 metres up, but the Earth's biomes already
 * come from the real climate at that height, and a snowy biome still snows.
 */
public final class CubicWeather {
    /** Vanilla's line between rain and snow, Biome.warmEnoughToRain. */
    private static final float SNOW_BELOW = 0.15F;

    private CubicWeather() {}

    public static Biome.Precipitation precipitation(Level level, Biome biome, Biome.Precipitation vanilla) {
        return vanilla == Biome.Precipitation.SNOW && level != null && CubicLevel.of(level) != null
                && biome.getBaseTemperature() >= SNOW_BELOW ? Biome.Precipitation.RAIN : vanilla;
    }
}
