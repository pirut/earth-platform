package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import io.earthplatform.minecraft.cubic.compat.EarthWeatherProfile;
import io.earthplatform.minecraft.cubic.compat.PMWeatherCompat;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.joml.Vector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Shapes PM Weather's cloud density and seasonal cloudiness by Earth's regional climate and hemisphere. */
@Pseudo
@Mixin(targets = "dev.protomanly.pmweather.weather.Clouds", remap = false)
abstract class PMWeatherCloudsMixin {
    private static final String GET_CLOUD_DENSITY =
            "getCloudDensity(Ldev/protomanly/pmweather/weather/WeatherHandler;Lorg/joml/Vector2f;FZI)F";

    @ModifyExpressionValue(method = GET_CLOUD_DENSITY, require = 0,
            at = @At(value = "INVOKE", target = "Ldev/protomanly/pmweather/weather/WeatherHandler;getWorld()Lnet/minecraft/world/level/Level;", ordinal = 0))
    private static Level earthplatform$captureCloudLevel(Level level, @Share("level") LocalRef<Level> shared) {
        shared.set(level);
        return level;
    }

    @ModifyExpressionValue(method = GET_CLOUD_DENSITY, require = 0,
            at = @At(value = "INVOKE", target = "Ldev/protomanly/pmweather/seasons/SeasonHandler;getSeasonEffectSine(Lnet/minecraft/world/level/Level;F)F"))
    private static float earthplatform$hemisphereCloudSeason(float original,
                                                             @Local(argsOnly = true) Vector2f pos,
                                                             @Share("level") LocalRef<Level> shared) {
        Level level = shared.get();
        return level != null ? original * PMWeatherCompat.seasonSign(level, pos.x, pos.y) : original;
    }

    @ModifyExpressionValue(method = GET_CLOUD_DENSITY, require = 0,
            at = @At(value = "FIELD", target = "Ldev/protomanly/pmweather/config/ServerConfig;overcastPercent:D"))
    private static double earthplatform$regionalOvercast(double original,
                                                         @Local(argsOnly = true) Vector2f pos,
                                                         @Share("level") LocalRef<Level> shared) {
        Level level = shared.get();
        if (level == null) return original;
        int y = PMWeatherCompat.surfaceY(level, Mth.floor(pos.x), Mth.floor(pos.y));
        EarthWeatherProfile profile = PMWeatherCompat.profile(level, pos.x, y, pos.y);
        return profile != null ? PMWeatherCompat.overcastPercent(original, profile) : original;
    }
}
