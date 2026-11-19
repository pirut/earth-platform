package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Supply Earth surface heights for Create Sky's cloud-shadow map. */
@Pseudo
@Mixin(targets = "com.createskys.client.SurfaceMap", remap = false)
abstract class CreateSkySurfaceMapMixin {
    @WrapOperation(method = "scan", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientChunkCache;getChunk(IIZ)Lnet/minecraft/world/level/chunk/LevelChunk;"), require = 0)
    private LevelChunk earthplatform$sampleCubicColumn(ClientChunkCache chunks, int x, int z, boolean allowEmpty,
                                                       Operation<LevelChunk> original, @Local(argsOnly = true) ClientLevel level) {
        // The carrier is just a token to keep the sampler going when this cubic column has no vanilla chunk.
        return original.call(chunks, x, z, allowEmpty || CubicLevel.at(level, x << 4, z << 4) != null);
    }

    @ModifyExpressionValue(method = "scan", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getMinBuildHeight()I"), require = 0)
    private int earthplatform$surfaceFloor(int vanilla, @Local(argsOnly = true) ClientLevel level,
                                          @Local(argsOnly = true, ordinal = 0) int chunkX,
                                          @Local(argsOnly = true, ordinal = 1) int chunkZ) {
        return CubicLevel.at(level, chunkX << 4, chunkZ << 4) == null ? vanilla : CubeWorld.MIN_Y - 1;
    }

    @WrapOperation(method = "scan", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/LevelChunk;getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I"), require = 0)
    private int earthplatform$surfaceHeight(LevelChunk chunk, Heightmap.Types type, int localX, int localZ,
                                            Operation<Integer> original, @Local(argsOnly = true) ClientLevel level,
                                            @Local(argsOnly = true, ordinal = 0) int chunkX,
                                            @Local(argsOnly = true, ordinal = 1) int chunkZ) {
        int x = (chunkX << 4) + localX, z = (chunkZ << 4) + localZ;
        CubeWorld cubes = CubicLevel.at(level, x, z);
        return cubes == null ? original.call(chunk, type, localX, localZ)
                : cubes.heightTop(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    @WrapOperation(method = "scan", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/LevelChunk;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"), require = 0)
    private BlockState earthplatform$surfaceBlock(LevelChunk chunk, BlockPos pos, Operation<BlockState> original,
                                                 @Local(argsOnly = true) ClientLevel level,
                                                 @Local(argsOnly = true, ordinal = 0) int chunkX,
                                                 @Local(argsOnly = true, ordinal = 1) int chunkZ) {
        int x = (chunkX << 4) + pos.getX(), z = (chunkZ << 4) + pos.getZ();
        CubeWorld cubes = CubicLevel.at(level, x, z);
        // This lookup only checks whether to keep scanning. The cubic heightmap already skips leaves,
        // and may be using a known terrain height above unloaded cubes. Accept its answer in either case.
        return cubes == null ? original.call(chunk, pos) : Blocks.STONE.defaultBlockState();
    }
}
