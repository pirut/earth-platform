package io.earthplatform.minecraft.cubic.mixin.compat;

import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.compat.EarthTemperature;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Homeostatic scales altitude by {@code level.getMaxBuildHeight() / 256.0F}, which on Earth
 * Platform's 16-block carrier divides by 0.0625 (a 16x cooling multiplier) and causes instant
 * hypothermia above Y 80. In Earth worlds, supply real CHELSA air temperature (which already
 * includes elevation lapse rate) directly into Homeostatic's height-adjusted biome temperature,
 * and keep {@code getAdjustedHeight} safe in all cubic worlds.
 */
@Pseudo
@Mixin(targets = "homeostatic.common.temperature.EnvironmentData", remap = false)
abstract class HomeostaticHeightMixin {
    @Inject(method = "getAdjustedHeight(Lnet/minecraft/server/level/ServerLevel;F)F",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void earthplatform$adjustedHeight(ServerLevel level, float y, CallbackInfoReturnable<Float> result) {
        if (CubicLevel.of(level) != null) {
            result.setReturnValue(EarthTemperature.hasEarth(level) ? 64.0F : y / (320.0F / 256.0F));
        }
    }

    @Inject(method = "getHeightAdjustedTemperature(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/Holder;Lnet/minecraft/core/BlockPos;)F",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void earthplatform$earthTemperature(ServerLevel level, Holder<Biome> biome, BlockPos pos,
                                                       CallbackInfoReturnable<Float> result) {
        double celsius = EarthTemperature.celsius(level, pos.getX(), pos.getY(), pos.getZ());
        if (Double.isFinite(celsius)) {
            result.setReturnValue(EarthTemperature.homeostatic(celsius));
        }
    }
}
