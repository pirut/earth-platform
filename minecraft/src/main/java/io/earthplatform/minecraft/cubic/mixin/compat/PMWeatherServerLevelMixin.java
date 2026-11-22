package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Widens the build-height checks inside PM Weather's ServerLevelMixin precipitation helpers
 * (shouldFreeze, shouldSnow, shouldIce, shouldSleet) so snow, ice, and sleet work at mountain elevations.
 */
@Pseudo
@Mixin(value = ServerLevel.class, priority = 1500, remap = false)
abstract class PMWeatherServerLevelMixin {
    @WrapOperation(method = {"shouldFreeze", "shouldSnow", "shouldIce", "shouldSleet"}, require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getMinBuildHeight()I"))
    private int earthplatform$cubicMinY(ServerLevel level, Operation<Integer> original) {
        return CubicLevel.of(level) != null ? CubeWorld.MIN_Y : original.call(level);
    }

    @WrapOperation(method = {"shouldFreeze", "shouldSnow", "shouldIce", "shouldSleet"}, require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getMaxBuildHeight()I"))
    private int earthplatform$cubicMaxY(ServerLevel level, Operation<Integer> original) {
        return CubicLevel.of(level) != null ? CubeWorld.MAX_Y + 1 : original.call(level);
    }
}
