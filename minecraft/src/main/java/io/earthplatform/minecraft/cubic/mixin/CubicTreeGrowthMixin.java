package io.earthplatform.minecraft.cubic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A sapling grows into a tree at any cubic height; the tree found no room outside the 16-block carrier. */
@Mixin(TreeFeature.class)
abstract class CubicTreeGrowthMixin {
    @WrapOperation(method = "doPlace", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;getMinBuildHeight()I"))
    private int earthplatform$cubicBottom(WorldGenLevel level, Operation<Integer> original) {
        return level instanceof Level live && CubicLevel.of(live) != null ? CubeWorld.MIN_Y : original.call(level);
    }

    @WrapOperation(method = "doPlace", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/WorldGenLevel;getMaxBuildHeight()I"))
    private int earthplatform$cubicTop(WorldGenLevel level, Operation<Integer> original) {
        return level instanceof Level live && CubicLevel.of(live) != null ? CubeWorld.MAX_Y + 1 : original.call(level);
    }
}
