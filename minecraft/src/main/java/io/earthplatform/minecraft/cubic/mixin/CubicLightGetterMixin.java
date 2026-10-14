package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A cubic level's light comes from its cubes rather than the carrier's light engine; canSeeSky follows. */
@Mixin(BlockAndTintGetter.class)
interface CubicLightGetterMixin {
    @Inject(method = "getBrightness(Lnet/minecraft/world/level/LightLayer;Lnet/minecraft/core/BlockPos;)I", at = @At("HEAD"), cancellable = true)
    private void earthplatform$cubeBrightness(LightLayer layer, BlockPos pos, CallbackInfoReturnable<Integer> result) {
        if ((Object) this instanceof Level level && CubicLevel.at(level, pos.getX(), pos.getZ()) instanceof CubeWorld cubes) result.setReturnValue(cubes.getBrightness(layer, pos));
    }

    @Inject(method = "getRawBrightness(Lnet/minecraft/core/BlockPos;I)I", at = @At("HEAD"), cancellable = true)
    private void earthplatform$cubeRawBrightness(BlockPos pos, int amount, CallbackInfoReturnable<Integer> result) {
        if ((Object) this instanceof Level level && CubicLevel.at(level, pos.getX(), pos.getZ()) instanceof CubeWorld cubes) result.setReturnValue(cubes.getRawBrightness(pos, amount));
    }
}
