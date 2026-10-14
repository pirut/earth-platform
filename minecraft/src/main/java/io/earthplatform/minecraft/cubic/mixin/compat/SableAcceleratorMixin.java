package io.earthplatform.minecraft.cubic.mixin.compat;

import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.compat.SableCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sable's fast block access reads and writes chunk sections directly. In the cubic level the world's
 * blocks live in cubes, so ship gathering found nothing ("no valid blocks") and blocks moved onto a
 * ship came out as air. World columns go to the cubes here; ship plots keep the chunk path.
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.util.LevelAccelerator", remap = false)
abstract class SableAcceleratorMixin {
    @Shadow @Final private Level level;

    @Inject(method = "getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("HEAD"), cancellable = true, require = 0)
    private void earthplatform$cubeState(BlockPos pos, CallbackInfoReturnable<BlockState> result) {
        CubeWorld cubes = SableCompat.cubes(level, pos.getX(), pos.getZ());
        if (cubes != null) result.setReturnValue(cubes.getBlockState(pos));
    }

    @Inject(method = "getBlockState(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("HEAD"), cancellable = true, require = 0)
    private void earthplatform$cubeStateIn(LevelChunk chunk, BlockPos pos, CallbackInfoReturnable<BlockState> result) {
        CubeWorld cubes = SableCompat.cubes(level, pos.getX(), pos.getZ());
        if (cubes != null) result.setReturnValue(cubes.getBlockState(pos));
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true, require = 0)
    private void earthplatform$cubeFluid(BlockPos pos, CallbackInfoReturnable<FluidState> result) {
        CubeWorld cubes = SableCompat.cubes(level, pos.getX(), pos.getZ());
        if (cubes != null) result.setReturnValue(cubes.getFluidState(pos));
    }

    @Inject(method = "setBlockFast", at = @At("HEAD"), cancellable = true, require = 0)
    private void earthplatform$cubeSet(BlockPos pos, BlockState state, CallbackInfo info) {
        CubeWorld cubes = SableCompat.cubes(level, pos.getX(), pos.getZ());
        if (cubes == null) return;
        BlockState old = SableCompat.set(cubes, pos, state, false);
        if (old != null) level.sendBlockUpdated(pos, old, state, 3);
        info.cancel();
    }

    @Inject(method = "isOutsideBuildHeight", at = @At("HEAD"), cancellable = true, require = 0)
    private void earthplatform$cubeHeight(Vec3i pos, CallbackInfoReturnable<Boolean> result) {
        if (SableCompat.cubes(level, pos.getX(), pos.getZ()) != null) result.setReturnValue(!CubeWorld.inRange(pos.getY()));
    }
}
