package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.compat.CubeSections;
import io.earthplatform.minecraft.cubic.compat.SpatialCompat;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * AE2's spatial IO moves a region by reading and writing chunk sections and chunk block entities
 * directly, which in Earth are empty carriers (the old crash: a section index past the carrier's
 * 16 blocks). In the cubic level those accesses go to the cubes; see {@link SpatialCompat}.
 */
@Pseudo
@Mixin(targets = "appeng.spatial.CachedPlane", remap = false)
abstract class SpatialPlaneMixin {
    @WrapOperation(method = "<init>", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;getBlockEntities()Ljava/util/Map;"))
    private Map<BlockPos, BlockEntity> earthplatform$cubeEntities(LevelChunk chunk, Operation<Map<BlockPos, BlockEntity>> original) {
        CubeWorld cubes = SpatialCompat.cubes(chunk);
        return cubes != null ? SpatialCompat.blockEntities(cubes, chunk) : original.call(chunk);
    }

    @WrapOperation(method = "<init>", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;getSectionIndex(I)I"))
    private int earthplatform$cubeIndex(LevelChunk chunk, int y, Operation<Integer> original) {
        return SpatialCompat.cubes(chunk) != null ? CubeSections.indexOfBlock(y) : original.call(chunk, y);
    }

    @WrapOperation(method = "<init>", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;getSection(I)Lnet/minecraft/world/level/chunk/LevelChunkSection;"))
    private LevelChunkSection earthplatform$cubeSection(LevelChunk chunk, int index, Operation<LevelChunkSection> original) {
        return SpatialCompat.cubes(chunk) != null ? SpatialCompat.section(chunk, index) : original.call(chunk, index);
    }

    @WrapOperation(method = "<init>", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;removeBlockEntity(Lnet/minecraft/core/BlockPos;)V"))
    private void earthplatform$cubeRemoveEntity(LevelChunk chunk, BlockPos pos, Operation<Void> original) {
        CubeWorld cubes = SpatialCompat.cubes(chunk);
        if (cubes != null) cubes.removeBlockEntity(pos);
        else original.call(chunk, pos);
    }

    @WrapOperation(method = "addBlockEntity", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getSectionIndex(I)I"))
    private int earthplatform$movedIndex(ChunkAccess chunk, int y, Operation<Integer> original) {
        return chunk instanceof LevelChunk level && SpatialCompat.cubes(level) != null ? CubeSections.indexOfBlock(y) : original.call(chunk, y);
    }

    @WrapOperation(method = "addBlockEntity", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getSection(I)Lnet/minecraft/world/level/chunk/LevelChunkSection;"))
    private LevelChunkSection earthplatform$movedSection(ChunkAccess chunk, int index, Operation<LevelChunkSection> original) {
        return chunk instanceof LevelChunk level && SpatialCompat.cubes(level) != null ? SpatialCompat.section(level, index) : original.call(chunk, index);
    }

    @WrapOperation(method = "attemptRecovery", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;addAndRegisterBlockEntity(Lnet/minecraft/world/level/block/entity/BlockEntity;)V"))
    private void earthplatform$recoverInCube(LevelChunk chunk, BlockEntity entity, Operation<Void> original) {
        CubeWorld cubes = SpatialCompat.cubes(chunk);
        if (cubes != null) cubes.addAndRegisterBlockEntity(entity);
        else original.call(chunk, entity);
    }

    @Inject(method = "updateChunks", require = 0, at = @At("HEAD"))
    private void earthplatform$refreshCubes(CallbackInfo info) {
        SpatialCompat.finish();
    }
}
