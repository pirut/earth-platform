package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Cobblemon squeezes spawn areas into the build height; see {@link CobblemonSpawningMixin}. */
@Pseudo
@Mixin(targets = "com.cobblemon.mod.common.util.WorldExtensionsKt", remap = false)
abstract class CobblemonBoundsMixin {
    @WrapOperation(method = "squeezeWithinBounds", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinBuildHeight()I"))
    private static int earthplatform$bottom(Level level, Operation<Integer> original) {
        return CubicLevel.of(level) != null ? CubeWorld.MIN_Y : original.call(level);
    }

    @WrapOperation(method = "squeezeWithinBounds", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMaxBuildHeight()I"))
    private static int earthplatform$top(Level level, Operation<Integer> original) {
        return CubicLevel.of(level) != null ? CubeWorld.MAX_Y + 1 : original.call(level);
    }
}
