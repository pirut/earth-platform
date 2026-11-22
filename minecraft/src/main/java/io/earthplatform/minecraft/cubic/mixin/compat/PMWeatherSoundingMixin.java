package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import io.earthplatform.minecraft.cubic.compat.EarthWeatherProfile;
import io.earthplatform.minecraft.cubic.compat.PMWeatherCompat;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Shapes PM Weather's convective severe-storm risk by regional tornado and storm-energy climatology. */
@Pseudo
@Mixin(targets = "dev.protomanly.pmweather.weather.Sounding", remap = false)
abstract class PMWeatherSoundingMixin {
    @Shadow Vec3 position;

    @ModifyExpressionValue(method = "getRisk", require = 0,
            at = @At(value = "INVOKE", target = "Ldev/protomanly/pmweather/weather/WeatherHandler;getWorld()Lnet/minecraft/world/level/Level;"))
    private Level earthplatform$captureSoundingLevel(Level level, @Share("level") LocalRef<Level> shared) {
        shared.set(level);
        return level;
    }

    @ModifyReturnValue(method = "getRisk", at = @At("RETURN"), require = 0)
    private float earthplatform$regionalRisk(float original, @Share("level") LocalRef<Level> shared) {
        Level level = shared.get();
        if (level == null || position == null) return original;
        EarthWeatherProfile profile = PMWeatherCompat.profile(level, position.x, position.y, position.z);
        return profile != null ? PMWeatherCompat.soundingRisk(original, profile) : original;
    }
}
