package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.compat.EarthWeatherProfile;
import io.earthplatform.minecraft.cubic.compat.PMWeatherCompat;
import io.earthplatform.minecraft.cubic.earth.EarthGenerator;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * Feeds real-world Earth climatology, standard tropospheric lapse rate (6.5 C/km instead of
 * 50 C/km), Southern Hemisphere seasonal inversion, and regional ocean SST into PM Weather's
 * thermodynamic sounding engine.
 */
@Pseudo
@Mixin(targets = "dev.protomanly.pmweather.weather.ThermodynamicEngine", remap = false)
abstract class PMWeatherThermodynamicsMixin {
    private static final String SAMPLE_POINT =
            "samplePoint(Ldev/protomanly/pmweather/weather/WeatherHandler;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/Level;Ldev/protomanly/pmweather/block/entity/RadarBlockEntity;ILjava/lang/Integer;ZZ)Ldev/protomanly/pmweather/weather/ThermodynamicEngine$AtmosphericDataPoint;";

    @ModifyConstant(method = SAMPLE_POINT, constant = @Constant(floatValue = 20.0f, ordinal = 1), require = 0)
    private static float earthplatform$standardLapseRate(float original, @Local(argsOnly = true) Level level) {
        return CubicLevel.of(level) != null ? PMWeatherCompat.STANDARD_LAPSE_DENOMINATOR : original;
    }

    @ModifyExpressionValue(method = SAMPLE_POINT, require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/biome/Biome;getBaseTemperature()F", ordinal = 0))
    private static float earthplatform$regionalBiomeTemp(float original,
                                                         @Local(argsOnly = true) Vec3 pos,
                                                         @Local(argsOnly = true) Level level) {
        EarthWeatherProfile profile = PMWeatherCompat.profile(level, pos.x, pos.y, pos.z);
        return profile != null ? PMWeatherCompat.biomeTemperature(profile) : original;
    }

    @ModifyExpressionValue(method = SAMPLE_POINT, require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/biome/Biome$ClimateSettings;downfall()F", ordinal = 0))
    private static float earthplatform$regionalBiomeDownfall(float original,
                                                             @Local(argsOnly = true) Vec3 pos,
                                                             @Local(argsOnly = true) Level level) {
        EarthWeatherProfile profile = PMWeatherCompat.profile(level, pos.x, pos.y, pos.z);
        return profile != null ? PMWeatherCompat.biomeDownfall(profile) : original;
    }

    @ModifyExpressionValue(method = {SAMPLE_POINT, "GetSST"}, require = 0,
            at = @At(value = "INVOKE", target = "Ldev/protomanly/pmweather/seasons/SeasonHandler;getSeasonEffectSine(Lnet/minecraft/world/level/Level;F)F"))
    private static float earthplatform$hemisphereSeason(float original,
                                                        @Local(argsOnly = true) Vec3 pos,
                                                        @Local(argsOnly = true) Level level) {
        return original * PMWeatherCompat.seasonSign(level, pos.x, pos.z);
    }

    @ModifyExpressionValue(method = "GetSST", require = 0,
            at = @At(value = "INVOKE", target = "Ljava/lang/Float;valueOf(F)Ljava/lang/Float;"))
    private static Float earthplatform$regionalSST(Float original,
                                                   @Local(argsOnly = true) Vec3 pos,
                                                   @Local(argsOnly = true) Level level) {
        EarthWeatherProfile profile = PMWeatherCompat.profile(level, pos.x, EarthGenerator.SEA, pos.z);
        return profile != null ? PMWeatherCompat.seaSurfaceTemp(profile) : original;
    }
}
