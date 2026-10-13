package io.earthplatform.minecraft.cubic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.levelgen.feature.AbstractHugeMushroomFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A mushroom grows into a huge mushroom at any cubic height. */
@Mixin(AbstractHugeMushroomFeature.class)
abstract class CubicMushroomGrowthMixin {
    @WrapOperation(method = "isValidPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelAccessor;getMinBuildHeight()I"))
    private int earthplatform$cubicBottom(LevelAccessor level, Operation<Integer> original) {
        return level instanceof Level live && CubicLevel.of(live) != null ? CubeWorld.MIN_Y : original.call(level);
    }

    @WrapOperation(method = "isValidPosition", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelAccessor;getMaxBuildHeight()I"))
    private int earthplatform$cubicTop(LevelAccessor level, Operation<Integer> original) {
        return level instanceof Level live && CubicLevel.of(live) != null ? CubeWorld.MAX_Y + 1 : original.call(level);
    }
}
