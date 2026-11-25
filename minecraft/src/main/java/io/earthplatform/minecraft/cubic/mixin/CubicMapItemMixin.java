package io.earthplatform.minecraft.cubic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.CubicMaps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A filled map in the Earth shows the ground, not bedrock. Vanilla colors each pixel from the
 * carrier chunk's heightmap and blocks, which are empty, so the whole map was grey stone; here those
 * reads go to the cubes (see {@link CubicMaps}), and the walk down a column may go below the carrier's
 * floor at Y 0.
 */
@Mixin(MapItem.class)
abstract class CubicMapItemMixin {
    /** A column whose cubes are not loaded reads as an empty chunk: vanilla skips it and leaves its pixel unexplored. */
    @WrapOperation(method = "update", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;"))
    private LevelChunk earthplatform$onlyLoadedColumns(Level level, int chunkX, int chunkZ, Operation<LevelChunk> original) {
        CubeWorld cubes = CubicLevel.at(level, chunkX << 4, chunkZ << 4);
        if (cubes == null || CubicMaps.columnLoaded(cubes, (chunkX << 4) + 8, (chunkZ << 4) + 8)) return original.call(level, chunkX, chunkZ);
        return new EmptyLevelChunk(level, new ChunkPos(chunkX, chunkZ),
                level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS));
    }

    @WrapOperation(method = "update", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/LevelChunk;getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I"))
    private int earthplatform$cubeSurface(LevelChunk chunk, Heightmap.Types type, int x, int z, Operation<Integer> original,
                                          @Local(argsOnly = true) Level level) {
        CubeWorld cubes = CubicLevel.at(level, x, z);
        return cubes == null ? original.call(chunk, type, x, z) : CubicMaps.surface(cubes, x, z);
    }

    @WrapOperation(method = "update", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/LevelChunk;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState earthplatform$cubeBlock(LevelChunk chunk, BlockPos pos, Operation<BlockState> original,
                                               @Local(argsOnly = true) Level level) {
        CubeWorld cubes = CubicLevel.at(level, pos.getX(), pos.getZ());
        return cubes == null ? original.call(chunk, pos) : CubicMaps.state(cubes.getBlockState(pos));
    }

    /** The carrier ends at Y 0, but the ground and the sea floor go down to the world's bottom. */
    @WrapOperation(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMinBuildHeight()I"))
    private int earthplatform$cubicBottom(Level level, Operation<Integer> original) {
        return CubicLevel.of(level) != null ? CubeWorld.MIN_Y : original.call(level);
    }
}
