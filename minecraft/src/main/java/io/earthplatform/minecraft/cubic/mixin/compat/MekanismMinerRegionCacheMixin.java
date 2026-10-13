package io.earthplatform.minecraft.cubic.mixin.compat;

import io.earthplatform.minecraft.cubic.Cube;
import io.earthplatform.minecraft.cubic.CubeKey;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The Digital Miner searches on its own thread through a region cache filled with the level's chunks.
 * In the cubic level those are the empty carrier chunks, and the cubes are only readable on the
 * server thread, so the miner found nothing. When the cache is built (on the server thread) it now
 * also keeps the loaded cubes inside the miner's region, and the search reads blocks from them.
 */
@Pseudo
@Mixin(targets = "mekanism.common.content.miner.MinerRegionCache", remap = false)
abstract class MekanismMinerRegionCacheMixin {
    @Unique @Nullable private Long2ObjectOpenHashMap<Cube> earthplatform$cubes;

    @Inject(method = "<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Z)V",
            at = @At("RETURN"), require = 0)
    private void earthplatform$snapshot(ServerLevel level, BlockPos min, BlockPos max, boolean loadChunks, CallbackInfo ci) {
        CubeWorld world = CubicLevel.of(level);
        if (world == null || !world.isOwnerThread()) return;
        int x0 = Math.min(min.getX(), max.getX()) >> 4, x1 = Math.max(min.getX(), max.getX()) >> 4;
        int y0 = Math.min(min.getY(), max.getY()) >> 4, y1 = Math.max(min.getY(), max.getY()) >> 4;
        int z0 = Math.min(min.getZ(), max.getZ()) >> 4, z1 = Math.max(min.getZ(), max.getZ()) >> 4;
        Long2ObjectOpenHashMap<Cube> found = new Long2ObjectOpenHashMap<>();
        for (Cube cube : world.cubes()) {
            if (cube.x >= x0 && cube.x <= x1 && cube.y >= y0 && cube.y <= y1 && cube.z >= z0 && cube.z <= z1) {
                found.put(cube.key, cube);
            }
        }
        earthplatform$cubes = found;
    }

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true, require = 0)
    private void earthplatform$state(BlockPos pos, CallbackInfoReturnable<BlockState> result) {
        Long2ObjectOpenHashMap<Cube> cubes = earthplatform$cubes;
        if (cubes == null) return;
        Cube cube = cubes.get(CubeKey.ofBlock(pos));
        result.setReturnValue(cube == null ? Blocks.AIR.defaultBlockState() : cube.get(pos.getX(), pos.getY(), pos.getZ()));
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true, require = 0)
    private void earthplatform$fluid(BlockPos pos, CallbackInfoReturnable<FluidState> result) {
        Long2ObjectOpenHashMap<Cube> cubes = earthplatform$cubes;
        if (cubes == null) return;
        Cube cube = cubes.get(CubeKey.ofBlock(pos));
        result.setReturnValue(cube == null ? Blocks.AIR.defaultBlockState().getFluidState() : cube.get(pos.getX(), pos.getY(), pos.getZ()).getFluidState());
    }

    /** Block entities live in a plain map the server thread changes; the search never needs them. */
    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true, require = 0)
    private void earthplatform$tile(BlockPos pos, CallbackInfoReturnable<BlockEntity> result) {
        if (earthplatform$cubes != null) result.setReturnValue(null);
    }

    @Inject(method = "getMinBuildHeight", at = @At("HEAD"), cancellable = true, require = 0)
    private void earthplatform$bottom(CallbackInfoReturnable<Integer> result) {
        if (earthplatform$cubes != null) result.setReturnValue(CubeWorld.MIN_Y);
    }

    @Inject(method = "getHeight", at = @At("HEAD"), cancellable = true, require = 0)
    private void earthplatform$height(CallbackInfoReturnable<Integer> result) {
        if (earthplatform$cubes != null) result.setReturnValue(CubeWorld.MAX_Y - CubeWorld.MIN_Y + 1);
    }
}
