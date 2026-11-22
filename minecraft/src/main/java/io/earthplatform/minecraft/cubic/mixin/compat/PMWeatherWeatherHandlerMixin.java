package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import io.earthplatform.minecraft.cubic.compat.EarthWeatherProfile;
import io.earthplatform.minecraft.cubic.compat.PMWeatherCompat;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Scales PM Weather's precipitation rate by regional rain frequency and monsoon/oceanic intensity. */
@Pseudo
@Mixin(targets = "dev.protomanly.pmweather.weather.WeatherHandler", remap = false)
abstract class PMWeatherWeatherHandlerMixin {
    @Shadow public abstract Level getWorld();

    @ModifyReturnValue(method = "_getPrecipitation", at = @At("RETURN"), require = 0)
    private float earthplatform$regionalPrecipitation(float original, @Local(argsOnly = true) Vec3 pos) {
        if (original <= 0.0f) return original;
        Level level = getWorld();
        if (level == null) return original;
        EarthWeatherProfile profile = PMWeatherCompat.profile(level, pos.x, pos.y, pos.z);
        return profile != null ? PMWeatherCompat.precipitation(original, profile) : original;
    }
}
