package io.earthplatform.minecraft.cubic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.BigDripleafBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** A big dripleaf grows taller at any cubic height. */
@Mixin(BigDripleafBlock.class)
abstract class CubicDripleafMixin {
    @WrapOperation(method = "canPlaceAt", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelHeightAccessor;isOutsideBuildHeight(Lnet/minecraft/core/BlockPos;)Z"))
    private static boolean earthplatform$cubicRange(LevelHeightAccessor level, BlockPos pos, Operation<Boolean> original) {
        return level instanceof Level live && CubicLevel.of(live) != null ? pos.getY() < CubeWorld.MIN_Y || pos.getY() > CubeWorld.MAX_Y : original.call(level, pos);
    }
}
