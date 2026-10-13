package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.compat.SableCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The physics assembler gathers a ship's blocks with its own search, which skips anything outside
 * the level's build range. In the cubic level that was every block above the carrier, so pulling
 * the lever assembled nothing. World columns use the cubes' range.
 */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.util.assembly.SimAssemblyContraption", remap = false)
abstract class SimulatedAssemblyMixin {
    @WrapOperation(method = "moveBlock", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isOutsideBuildHeight(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean earthplatform$cubicHeight(Level level, BlockPos pos, Operation<Boolean> original) {
        return SableCompat.cubes(level, pos.getX(), pos.getZ()) != null ? !CubeWorld.inRange(pos.getY()) : original.call(level, pos);
    }
}
