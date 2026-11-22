package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Immersive Vehicles discards any vehicle entity whose block position is outside the level's build
 * height and below Y 0. In the cubic Earth world, valleys and underground builds extend down to
 * {@link CubeWorld#MIN_Y}, so vehicles below Y 0 should only be discarded outside cubic bounds.
 */
@Pseudo
@Mixin(targets = "mcinterface1211.BuilderEntityExisting", remap = false)
abstract class ImmersiveVehiclesEntityMixin {
    @WrapOperation(method = "baseTick()V", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isOutsideBuildHeight(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean earthplatform$cubicBuildHeight(Level level, BlockPos pos, Operation<Boolean> original) {
        return CubicLevel.of(level) != null ? !CubeWorld.inRange(pos.getY()) : original.call(level, pos);
    }
}
