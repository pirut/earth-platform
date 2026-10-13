package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.util.GoalUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Wander and flee targets are within the world anywhere in the cubic range, not just Y 0..16. */
@Mixin(GoalUtils.class)
abstract class CubicGoalLimitsMixin {
    @Inject(method = "isOutsideLimits", at = @At("HEAD"), cancellable = true)
    private static void earthplatform$cubicLimits(BlockPos pos, PathfinderMob mob, CallbackInfoReturnable<Boolean> result) {
        if (CubicLevel.of(mob.level()) != null) result.setReturnValue(!CubeWorld.inRange(pos.getY()));
    }
}
