package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Routes a cubic Level's blocks, block entities, light, biomes and heights to its cube store, at
 * any Y. Levels without a store run vanilla code untouched.
 */
@Mixin(Level.class)
public abstract class CubicLevelMixin implements CubicLevel {
    @Unique @Nullable private CubeWorld earthplatform$cubeWorld;

    @Shadow public abstract void updateNeighbourForOutputSignal(BlockPos pos, Block block);

    @Override public @Nullable CubeWorld earthplatform$cubes() { return earthplatform$cubeWorld; }
    @Override public void earthplatform$setCubes(@Nullable CubeWorld cubes) { earthplatform$cubeWorld = cubes; }

    private Level earthplatform$self() { return (Level) (Object) this; }

    @Inject(method = "getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("HEAD"), cancellable = true)
    private void earthplatform$cubeState(BlockPos pos, CallbackInfoReturnable<BlockState> result) {
        CubeWorld cubes = earthplatform$cubeWorld;
        if (cubes != null) result.setReturnValue(cubes.getBlockState(pos));
    }

    @Inject(method = "getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;", at = @At("HEAD"), cancellable = true)
    private void earthplatform$cubeFluid(BlockPos pos, CallbackInfoReturnable<FluidState> result) {
        CubeWorld cubes = earthplatform$cubeWorld;
        if (cubes != null) result.setReturnValue(cubes.getFluidState(pos));
    }

    /** Mirrors NeoForge's Level.setBlock, including block snapshot capture for place events. */
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"), cancellable = true)
    private void earthplatform$cubeSet(BlockPos pos, BlockState state, int flags, int depth, CallbackInfoReturnable<Boolean> result) {
        CubeWorld cubes = earthplatform$cubeWorld;
        if (cubes == null) return;
        Level level = earthplatform$self();
        if (!CubeWorld.inRange(pos.getY()) || !cubes.isLoaded(pos)) { result.setReturnValue(false); return; }
        pos = pos.immutable();
        net.neoforged.neoforge.common.util.BlockSnapshot snapshot = null;
        if (level.captureBlockSnapshots && !level.isClientSide) {
            snapshot = net.neoforged.neoforge.common.util.BlockSnapshot.create(level.dimension(), level, pos, flags);
            level.capturedBlockSnapshots.add(snapshot);
        }
        BlockState old = cubes.setBlockState(pos, state, (flags & 64) != 0);
        if (old == null) {
            if (snapshot != null) level.capturedBlockSnapshots.remove(snapshot);
            result.setReturnValue(false);
            return;
        }
        if (snapshot == null) cubes.markAndNotify(pos, old, state, flags, depth);
        result.setReturnValue(true);
    }

    @Inject(method = "markAndNotifyBlock", at = @At("HEAD"), cancellable = true)
    private void earthplatform$cubeNotify(BlockPos pos, @Nullable LevelChunk chunk, BlockState old, BlockState state, int flags, int depth, CallbackInfo info) {
        CubeWorld cubes = earthplatform$cubeWorld;
        if (cubes == null) return;
        cubes.markAndNotify(pos, old, state, flags, depth);
        info.cancel();
    }

    @Inject(method = "getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;", at = @At("HEAD"), cancellable = true)
    private void earthplatform$cubeEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> result) {
        CubeWorld cubes = earthplatform$cubeWorld;
        if (cubes != null) result.setReturnValue(cubes.getBlockEntity(pos, true));
    }

    @Inject(method = "setBlockEntity(Lnet/minecraft/world/level/block/entity/BlockEntity;)V", at = @At("HEAD"), cancellable = true)
    private void earthplatform$cubeSetEntity(BlockEntity entity, CallbackInfo info) {
        CubeWorld cubes = earthplatform$cubeWorld;
        if (cubes == null) return;
        cubes.addAndRegisterBlockEntity(entity);
        info.cancel();
    }

    @Inject(method = "removeBlockEntity(Lnet/minecraft/core/BlockPos;)V", at = @At("HEAD"), cancellable = true)
    private void earthplatform$cubeRemoveEntity(BlockPos pos, CallbackInfo info) {
        CubeWorld cubes = earthplatform$cubeWorld;
        if (cubes == null) return;
        cubes.removeBlockEntity(pos);
        updateNeighbourForOutputSignal(pos, cubes.getBlockState(pos).getBlock());
        info.cancel();
    }

    @Inject(method = "blockEntityChanged(Lnet/minecraft/core/BlockPos;)V", at = @At("HEAD"), cancellable = true)
    private void earthplatform$cubeEntityChanged(BlockPos pos, CallbackInfo info) {
        CubeWorld cubes = earthplatform$cubeWorld;
        if (cubes == null) return;
        cubes.blockEntityChanged(pos);
        info.cancel();
    }

    @Inject(method = "isLoaded(Lnet/minecraft/core/BlockPos;)Z", at = @At("HEAD"), cancellable = true)
    private void earthplatform$cubeLoaded(BlockPos pos, CallbackInfoReturnable<Boolean> result) {
        CubeWorld cubes = earthplatform$cubeWorld;
        if (cubes != null) result.setReturnValue(cubes.isLoaded(pos));
    }

    @Inject(method = "isInWorldBounds(Lnet/minecraft/core/BlockPos;)Z", at = @At("HEAD"), cancellable = true)
    private void earthplatform$cubeBounds(BlockPos pos, CallbackInfoReturnable<Boolean> result) {
        if (earthplatform$cubeWorld != null) {
            result.setReturnValue(CubeWorld.inRange(pos.getY()) && Math.abs(pos.getX()) < 30_000_000 && Math.abs(pos.getZ()) < 30_000_000);
        }
    }

    @Inject(method = "loadedAndEntityCanStandOnFace(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/Direction;)Z", at = @At("HEAD"), cancellable = true)
    private void earthplatform$cubeStandOn(BlockPos pos, Entity entity, Direction direction, CallbackInfoReturnable<Boolean> result) {
        CubeWorld cubes = earthplatform$cubeWorld;
        if (cubes != null) result.setReturnValue(cubes.isLoaded(pos) && cubes.getBlockState(pos).entityCanStandOnFace(earthplatform$self(), pos, entity, direction));
    }

    @Inject(method = "getChunkForCollisions(II)Lnet/minecraft/world/level/BlockGetter;", at = @At("HEAD"), cancellable = true)
    private void earthplatform$cubeCollisions(int chunkX, int chunkZ, CallbackInfoReturnable<BlockGetter> result) {
        if (earthplatform$cubeWorld != null) result.setReturnValue(earthplatform$self());
    }

    @Inject(method = "getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I", at = @At("HEAD"), cancellable = true)
    private void earthplatform$cubeHeight(Heightmap.Types type, int x, int z, CallbackInfoReturnable<Integer> result) {
        CubeWorld cubes = earthplatform$cubeWorld;
        if (cubes != null) result.setReturnValue(cubes.skyTop(x, z) + 1);
    }
}
