package io.earthplatform.minecraft.cubic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Searches for ground or the end of water below a node stop at the level's floor. In a cubic
 * level that is the floor of the pathfinding region (see CubicPathRegionMixin), not the carrier's.
 */
@Mixin(WalkNodeEvaluator.class)
abstract class CubicWalkNodesMixin extends NodeEvaluator {
    @WrapOperation(method = {"tryFindFirstNonWaterBelow", "tryFindFirstGroundNodeBelow"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinBuildHeight()I"))
    private int earthplatform$regionFloor(Level level, Operation<Integer> original) {
        return CubicLevel.of(level) != null ? currentContext.level().getMinBuildHeight() : original.call(level);
    }
}
