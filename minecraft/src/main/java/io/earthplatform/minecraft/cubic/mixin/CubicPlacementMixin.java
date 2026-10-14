package io.earthplatform.minecraft.cubic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Two-block-tall placements check room above against the cubic range. */
@Mixin({DoorBlock.class, DoublePlantBlock.class})
abstract class CubicPlacementMixin {
    @WrapOperation(method = "getStateForPlacement",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMaxBuildHeight()I"))
    private int earthplatform$cubicTop(Level level, Operation<Integer> original) {
        return CubicLevel.of(level) != null ? CubeWorld.MAX_Y + 1 : original.call(level);
    }
}
