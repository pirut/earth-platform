package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Pathfinding reads blocks through a snapshot of carrier chunks, which in a cubic level are 16
 * blocks tall and empty, so every mob saw only air and never found a path. In a cubic level the
 * region reads the level itself (its cubes) and spans the Y of the box it was made for, with a
 * margin for falls, instead of the carrier's height.
 */
@Mixin(PathNavigationRegion.class)
abstract class CubicPathRegionMixin {
    /** Blocks below and above the requested box that a search may still read (falls, floating). */
    @Unique private static final int MARGIN = 64;

    @Shadow @Final protected Level level;
    @Unique private boolean earthplatform$cubic;
    @Unique private int earthplatform$minY, earthplatform$height;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void earthplatform$cubicSpan(Level level, BlockPos from, BlockPos to, CallbackInfo info) {
        if (CubicLevel.of(level) == null) return;
        earthplatform$cubic = true;
        earthplatform$minY = Math.min(from.getY(), to.getY()) - MARGIN;
        earthplatform$height = Math.max(from.getY(), to.getY()) + MARGIN + 1 - earthplatform$minY;
    }

    @Unique private boolean earthplatform$outside(BlockPos pos) {
        int y = pos.getY() - earthplatform$minY;
        return y < 0 || y >= earthplatform$height;
    }

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void earthplatform$cubeState(BlockPos pos, CallbackInfoReturnable<BlockState> result) {
        if (earthplatform$cubic) result.setReturnValue(earthplatform$outside(pos) ? Blocks.AIR.defaultBlockState() : level.getBlockState(pos));
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void earthplatform$cubeFluid(BlockPos pos, CallbackInfoReturnable<FluidState> result) {
        if (earthplatform$cubic) result.setReturnValue(earthplatform$outside(pos) ? Fluids.EMPTY.defaultFluidState() : level.getFluidState(pos));
    }

    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void earthplatform$cubeEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> result) {
        if (earthplatform$cubic) result.setReturnValue(level.getBlockEntity(pos));
    }

    /** Collision checks along a path (doors, slabs, fences) see the cubes too. */
    @Inject(method = "getChunkForCollisions", at = @At("HEAD"), cancellable = true)
    private void earthplatform$cubeCollisions(int chunkX, int chunkZ, CallbackInfoReturnable<BlockGetter> result) {
        if (earthplatform$cubic) result.setReturnValue(level);
    }

    @Inject(method = "getMinBuildHeight", at = @At("HEAD"), cancellable = true)
    private void earthplatform$cubeMin(CallbackInfoReturnable<Integer> result) {
        if (earthplatform$cubic) result.setReturnValue(earthplatform$minY);
    }

    @Inject(method = "getHeight", at = @At("HEAD"), cancellable = true)
    private void earthplatform$cubeHeight(CallbackInfoReturnable<Integer> result) {
        if (earthplatform$cubic) result.setReturnValue(earthplatform$height);
    }
}
