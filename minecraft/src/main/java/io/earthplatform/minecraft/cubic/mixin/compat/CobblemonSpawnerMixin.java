package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Cobblemon's spawner interface picks where to spawn; see {@link CobblemonSpawningMixin}. */
@Pseudo
@Mixin(targets = "com.cobblemon.mod.common.api.spawning.spawner.Spawner", remap = false)
interface CobblemonSpawnerMixin {
    @WrapOperation(method = "constrainArea", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getMinBuildHeight()I"))
    private int earthplatform$areaBottom(ServerLevel level, Operation<Integer> original) {
        return CubicLevel.of(level) != null ? CubeWorld.MIN_Y : original.call(level);
    }

    @WrapOperation(method = "constrainArea", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getMaxBuildHeight()I"))
    private int earthplatform$areaTop(ServerLevel level, Operation<Integer> original) {
        return CubicLevel.of(level) != null ? CubeWorld.MAX_Y + 1 : original.call(level);
    }

    @WrapOperation(method = "isValidStartPoint", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState earthplatform$startBlock(ChunkAccess chunk, BlockPos pos, Operation<BlockState> original) {
        return chunk instanceof LevelChunk carrier && CubicLevel.of(carrier.getLevel()) != null
                ? carrier.getLevel().getBlockState(pos) : original.call(chunk, pos);
    }
}
