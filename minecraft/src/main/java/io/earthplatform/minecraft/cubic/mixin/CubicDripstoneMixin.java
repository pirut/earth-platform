package io.earthplatform.minecraft.cubic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Pointed dripstone finds its tip, base and the cauldron under it at any cubic height, so it grows and drips. */
@Mixin(PointedDripstoneBlock.class)
abstract class CubicDripstoneMixin {
    @WrapOperation(method = "findBlockVertical", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelAccessor;isOutsideBuildHeight(I)Z"))
    private static boolean earthplatform$cubicRange(LevelAccessor level, int y, Operation<Boolean> original) {
        return level instanceof Level live && CubicLevel.of(live) != null ? y < CubeWorld.MIN_Y || y > CubeWorld.MAX_Y : original.call(level, y);
    }
}
