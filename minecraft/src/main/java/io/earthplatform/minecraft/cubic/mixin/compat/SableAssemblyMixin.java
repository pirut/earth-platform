package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.compat.SableCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Moving blocks between the world and a ship writes straight into chunks: the world side when a ship
 * is assembled (blocks removed) or placed back (blocks set). In the cubic level that chunk is an
 * empty carrier, so those reads and writes go to the cubes instead, and clients hear about each change.
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.api.SubLevelAssemblyHelper", remap = false)
abstract class SableAssemblyMixin {
    @WrapOperation(method = "moveBlocks", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/LevelChunk;setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;"))
    private static BlockState earthplatform$cubeSet(LevelChunk chunk, BlockPos pos, BlockState state, boolean moving, Operation<BlockState> original) {
        CubeWorld cubes = SableCompat.cubes(chunk.getLevel(), pos.getX(), pos.getZ());
        if (cubes == null) return original.call(chunk, pos, state, moving);
        BlockState old = SableCompat.set(cubes, pos, state, moving);
        // Sable only tells clients about placed blocks when the carrier chunk is ticking, which a cubic
        // carrier never is, so send the change here.
        if (old != null && old != state) chunk.getLevel().sendBlockUpdated(pos, old, state, 3);
        return old;
    }

    @WrapOperation(method = "moveBlocks", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/LevelChunk;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private static BlockState earthplatform$cubeGet(LevelChunk chunk, BlockPos pos, Operation<BlockState> original) {
        CubeWorld cubes = SableCompat.cubes(chunk.getLevel(), pos.getX(), pos.getZ());
        return cubes != null ? cubes.getBlockState(pos) : original.call(chunk, pos);
    }
}
