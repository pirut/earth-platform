package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.earthplatform.minecraft.cubic.Cube;
import io.earthplatform.minecraft.cubic.CubeKey;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cold Sweat's WorldHelper reads heightmaps, chunk sections, skylight access, raycasts and water
 * freeze/melt bounds directly from the carrier chunk and its [0, 16) build height. In cubic worlds,
 * route those queries to the cubic store and full vertical range so ground height, roofs, rain,
 * thermal block sources, freezing and melting work at every Earth elevation.
 */
@Pseudo
@Mixin(targets = "com.momosoftworks.coldsweat.util.world.WorldHelper", remap = false)
abstract class ColdSweatWorldHelperMixin {
    @Inject(method = "getHeight(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/levelgen/Heightmap$Types;)I",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void earthplatform$height(BlockPos pos, Level level, Heightmap.Types heightmap,
                                             CallbackInfoReturnable<Integer> result) {
        CubeWorld cubes = CubicLevel.at(level, pos.getX(), pos.getZ());
        if (cubes != null) {
            result.setReturnValue(cubes.heightTop(heightmap, pos.getX(), pos.getZ()));
        }
    }

    @Inject(method = "getChunkSection(Lnet/minecraft/world/level/chunk/ChunkAccess;I)Lnet/minecraft/world/level/chunk/LevelChunkSection;",
            at = @At("HEAD"), cancellable = true, require = 0)
    private static void earthplatform$chunkSection(ChunkAccess chunk, int y,
                                                   CallbackInfoReturnable<LevelChunkSection> result) {
        if (chunk instanceof LevelChunk carrier) {
            CubeWorld cubes = CubicLevel.at(carrier.getLevel(), carrier.getPos().getMinBlockX(), carrier.getPos().getMinBlockZ());
            if (cubes != null) {
                Cube cube = cubes.cube(CubeKey.pack(carrier.getPos().x, y >> 4, carrier.getPos().z));
                if (cube != null) result.setReturnValue(cube.section);
            }
        }
    }

    @WrapOperation(method = "canSeeSky(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;I)Z",
            require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMaxBuildHeight()I"))
    private static int earthplatform$canSeeSkyMax(Level level, Operation<Integer> original,
                                                  @Local(argsOnly = true) BlockPos pos) {
        return CubicLevel.of(level) != null ? Math.min(CubeWorld.MAX_Y + 1, pos.getY() + 256) : original.call(level);
    }

    @WrapOperation(method = "isRainingAt(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z",
            require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getMaxBuildHeight()I"))
    private static int earthplatform$rainScanDistance(Level level, Operation<Integer> original) {
        return CubicLevel.of(level) != null ? 256 : original.call(level);
    }

    @WrapOperation(method = {
            "canSeeSky(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;I)Z",
            "isInWater(Lnet/minecraft/world/entity/Entity;)Z",
            "forBlocksInRay(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/chunk/ChunkAccess;Lit/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap;Ljava/util/function/BiConsumer;I)V"},
            require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private static BlockState earthplatform$chunkBlockState(ChunkAccess chunk, BlockPos pos,
                                                            Operation<BlockState> original) {
        if (chunk instanceof LevelChunk carrier) {
            CubeWorld cubes = CubicLevel.at(carrier.getLevel(), pos.getX(), pos.getZ());
            if (cubes != null) return cubes.getBlockState(pos);
        }
        return original.call(chunk, pos);
    }

    @WrapOperation(method = {
            "shouldFreeze(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Z)Z",
            "shouldMelt(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Z)Z"},
            require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelAccessor;getMinBuildHeight()I"))
    private static int earthplatform$freezeMin(LevelAccessor level, Operation<Integer> original) {
        return level instanceof Level l && CubicLevel.of(l) != null ? CubeWorld.MIN_Y : original.call(level);
    }

    @WrapOperation(method = {
            "shouldFreeze(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Z)Z",
            "shouldMelt(Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Z)Z"},
            require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelAccessor;getMaxBuildHeight()I"))
    private static int earthplatform$freezeMax(LevelAccessor level, Operation<Integer> original) {
        return level instanceof Level l && CubicLevel.of(l) != null ? CubeWorld.MAX_Y + 1 : original.call(level);
    }
}
