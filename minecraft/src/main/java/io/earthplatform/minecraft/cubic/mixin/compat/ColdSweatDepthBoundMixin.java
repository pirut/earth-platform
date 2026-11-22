package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Cold Sweat's overworld depth temperature regions anchor high-altitude cooling to {@code world_top}
 * and void freezing (-273.15 °C) to {@code world_bottom}. On the 16-block carrier, {@code world_top}
 * is Y 16 and {@code world_bottom} is Y 0, chilling every player above Y 16 and freezing sub-sea-level
 * basins like the Dead Sea to absolute zero. Supply the cubic world's true vertical bounds instead.
 */
@Pseudo
@Mixin(targets = "com.momosoftworks.coldsweat.data.codec.configuration.DepthTempData$VerticalBound", remap = false)
abstract class ColdSweatDepthBoundMixin {
    @WrapOperation(method = "getHeight(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/Level;)I",
            require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMaxBuildHeight()I"))
    private int earthplatform$worldTop(Level level, Operation<Integer> original) {
        return CubicLevel.of(level) != null ? CubeWorld.MAX_Y + 1 : original.call(level);
    }

    @WrapOperation(method = "getHeight(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/Level;)I",
            require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinBuildHeight()I"))
    private int earthplatform$worldBottom(Level level, Operation<Integer> original) {
        return CubicLevel.of(level) != null ? CubeWorld.MIN_Y : original.call(level);
    }
}
