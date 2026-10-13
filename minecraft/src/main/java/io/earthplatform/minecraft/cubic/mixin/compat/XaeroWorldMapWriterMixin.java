package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.client.ClientCubeWorld;
import io.earthplatform.minecraft.cubic.compat.CubicMaps;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Xaero's World Map, like the minimap, reads the empty carrier chunk; in the cubic world it reads the
 * client cubes. A chunk whose ground cube has not arrived (or has unloaded because the player flew
 * high) reads as not loaded, so the saved map keeps what it had.
 */
@Pseudo
@Mixin(targets = "xaero.map.MapWriter", remap = false)
abstract class XaeroWorldMapWriterMixin {
    @WrapOperation(method = "writeChunk", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinBuildHeight()I"))
    private int earthplatform$bottom(Level level, Operation<Integer> original) {
        ClientCubeWorld cubes = CubicMaps.cubes(level);
        return cubes != null ? CubicMaps.bottom(cubes) : original.call(level);
    }

    @WrapOperation(method = "writeChunk", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMaxBuildHeight()I"))
    private int earthplatform$top(Level level, Operation<Integer> original) {
        return CubicMaps.cubes(level) != null ? CubicMaps.top() : original.call(level);
    }

    @WrapOperation(method = "writeChunk", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I"))
    private int earthplatform$height(LevelChunk chunk, Heightmap.Types type, int x, int z, Operation<Integer> original) {
        ClientCubeWorld cubes = CubicMaps.cubes(chunk);
        return cubes != null ? CubicMaps.height(cubes, chunk, x, z) : original.call(chunk, type, x, z);
    }

    @WrapOperation(method = "loadPixel", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState earthplatform$state(LevelChunk chunk, BlockPos pos, Operation<BlockState> original) {
        ClientCubeWorld cubes = CubicMaps.cubes(chunk);
        return cubes != null ? CubicMaps.state(cubes, chunk, pos) : original.call(chunk, pos);
    }

    @WrapOperation(method = {"writeChunk", "loadPixel"}, require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;"))
    private LevelChunk earthplatform$chunk(Level level, int x, int z, Operation<LevelChunk> original) {
        ClientCubeWorld cubes = CubicMaps.cubes(level);
        if (cubes == null) return original.call(level, x, z);
        LevelChunk standIn = CubicMaps.mapChunk(cubes, x, z);
        return standIn != null ? standIn : original.call(level, x, z);
    }

    @WrapOperation(method = {"writeChunk", "loadPixel"}, require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;"))
    private ChunkAccess earthplatform$chunkAt(Level level, int x, int z, ChunkStatus status, boolean load, Operation<ChunkAccess> original) {
        ClientCubeWorld cubes = CubicMaps.cubes(level);
        if (cubes == null) return original.call(level, x, z, status, load);
        return CubicMaps.mapChunk(cubes, x, z);
    }
}
