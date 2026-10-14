package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.compat.SableCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.FleeSunGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Sable lets a ship overhead shade mobs from the sun, but only below the level's build top. In Earth
 * that top is the carrier's (Y 320), so above it ships cast no shade; there the same check runs here.
 */
@Mixin({Mob.class, FleeSunGoal.class, GroundPathNavigation.class})
abstract class SableSkyMixin {
    @WrapOperation(method = "*", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;canSeeSky(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean earthplatform$shipShade(Level level, BlockPos pos, Operation<Boolean> original) {
        boolean sky = original.call(level, pos);
        return sky && SableCompat.highShade(level, pos) ? false : sky;
    }
}
