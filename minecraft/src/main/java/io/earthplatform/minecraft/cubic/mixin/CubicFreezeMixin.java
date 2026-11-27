package io.earthplatform.minecraft.cubic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.earthplatform.minecraft.cubic.CubicWeather;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Water freezes and snow settles by the biome's own temperature in a cubic level, not the temperature
 * cooled by height (see CubicWeather). Only the temperature test changes; every other check in
 * shouldFreeze and shouldSnow (light, edge, replaceable block) stays vanilla.
 */
@Mixin(Biome.class)
abstract class CubicFreezeMixin {
    @WrapOperation(method = "shouldFreeze(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Z)Z", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/biome/Biome;warmEnoughToRain(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean earthplatform$freeze(Biome biome, BlockPos pos, Operation<Boolean> original, @Local(argsOnly = true) LevelReader level) {
        return CubicWeather.warmEnoughToRain(level, biome, original.call(biome, pos));
    }

    @WrapOperation(method = "shouldSnow", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/biome/Biome;warmEnoughToRain(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean earthplatform$snow(Biome biome, BlockPos pos, Operation<Boolean> original, @Local(argsOnly = true) LevelReader level) {
        return CubicWeather.warmEnoughToRain(level, biome, original.call(biome, pos));
    }
}
