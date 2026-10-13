package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Before teleporting, Waystones checks that the target waystone is still there. Reading an
 * unloaded block loads its chunk in a normal world, but the cubic level answers air, so every
 * waystone out of range counted as missing. Load the cubes around it first, as a respawn bed does.
 */
@Pseudo
@Mixin(targets = "net.blay09.mods.waystones.core.WaystoneImpl", remap = false)
abstract class WaystonesTargetMixin {
    @WrapOperation(method = "isValidInLevel", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState earthplatform$loadTarget(ServerLevel level, BlockPos pos, Operation<BlockState> original) {
        if (CubicLevel.of(level) instanceof ServerCubeWorld cubes && !cubes.isLoaded(pos)) cubes.loadAround(pos);
        return original.call(level, pos);
    }
}
