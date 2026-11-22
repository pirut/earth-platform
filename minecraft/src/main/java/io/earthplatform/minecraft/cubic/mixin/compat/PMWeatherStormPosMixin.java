package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.compat.PMWeatherCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Resolves Storm.getBlockPos() to the actual cubic terrain surface instead of hardcoded Y=60. */
@Pseudo
@Mixin(targets = "dev.protomanly.pmweather.weather.Storm", remap = false)
abstract class PMWeatherStormPosMixin {
    @Shadow public Level level;

    @ModifyReturnValue(method = "getBlockPos", at = @At("RETURN"), require = 0)
    private BlockPos earthplatform$cubicSurfacePos(BlockPos original) {
        if (level == null || original == null) return original;
        if (CubicLevel.at(level, original.getX(), original.getZ()) == null) return original;
        return original.atY(PMWeatherCompat.surfaceY(level, original.getX(), original.getZ()));
    }
}
