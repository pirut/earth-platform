package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.compat.PMWeatherCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Routes PM Weather's seasonal snow/ice block updates through cubic heightmaps and block storage. */
@Pseudo
@Mixin(targets = "dev.protomanly.pmweather.level.updaters.SeasonalLevelUpdater", remap = false)
abstract class PMWeatherSeasonalUpdaterMixin {
    @WrapOperation(method = "update", require = 0,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I"))
    private int earthplatform$cubicHeight(ChunkAccess chunk, Heightmap.Types type, int x, int z,
                                          Operation<Integer> original) {
        Level level = PMWeatherCompat.chunkLevel(chunk);
        if (level == null) return original.call(chunk, type, x, z);
        int wx = chunk.getPos().getMinBlockX() + (x & 15);
        int wz = chunk.getPos().getMinBlockZ() + (z & 15);
        CubeWorld cubes = CubicLevel.at(level, wx, wz);
        return cubes != null ? cubes.heightTop(type, wx, wz) : original.call(chunk, type, x, z);
    }

    @WrapOperation(method = "lambda$update$1", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;isOutsideBuildHeight(Lnet/minecraft/core/BlockPos;)Z"))
    private static boolean earthplatform$cubicInBounds(Level level, BlockPos pos, Operation<Boolean> original) {
        return CubicLevel.at(level, pos.getX(), pos.getZ()) != null
                ? !CubeWorld.inRange(pos.getY())
                : original.call(level, pos);
    }

    @WrapOperation(method = "lambda$update$1", require = 0,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private static BlockState earthplatform$cubicBlockState(ChunkAccess chunk, BlockPos pos,
                                                            Operation<BlockState> original,
                                                            @Local(argsOnly = true) Level level) {
        return CubicLevel.at(level, pos.getX(), pos.getZ()) != null
                ? level.getBlockState(pos)
                : original.call(chunk, pos);
    }
}
