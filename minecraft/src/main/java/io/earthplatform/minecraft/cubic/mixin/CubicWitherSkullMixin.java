package io.earthplatform.minecraft.cubic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.WitherSkullBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The wither can be built and summoned below the carrier's floor at Y 0 too. */
@Mixin(WitherSkullBlock.class)
abstract class CubicWitherSkullMixin {
    @WrapOperation(method = {"checkSpawn(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/SkullBlockEntity;)V", "canSpawnMob"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinBuildHeight()I"))
    private static int earthplatform$cubicBottom(Level level, Operation<Integer> original) {
        return CubicLevel.of(level) != null ? CubeWorld.MIN_Y : original.call(level);
    }
}
