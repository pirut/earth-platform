package io.earthplatform.minecraft.cubic.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.CubicMaps;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A filled map in the Earth shows the ground, not bedrock. Vanilla colors each pixel from the
 * carrier chunk's heightmap and blocks, which are empty, so the whole map was grey stone; here those
 * reads go to the cubes (see {@link CubicMaps}), and the walk down a column may go below the carrier's
 * floor at Y 0.
 *
 * <p>{@code MapItem.update} runs on the server thread, one pixel and one column at a time. The wraps
 * below hand each other the pixel and column now being read in plain fields, because the call sites
 * that need them (a floor, a depth cap) do not see them as arguments. The call sites, which the
 * {@code require} and {@code expect} counts state, so that a vanilla change which loses one fails when
 * the mixin is applied (and {@code CubicMapItemMixinTest} counts them in the bytecode): per pixel,
 * two {@code blockToSectionCoord}, one {@code getChunk}, one {@code isEmpty}; per column, one
 * {@code getHeight}, two {@code getBlockState} (the walk down to the surface block, then down through
 * its water) and four {@code getMinBuildHeight} (three on the first walk, one on the water's).
 */
@Mixin(MapItem.class)
abstract class CubicMapItemMixin {
    /** The pixel now being read: its first block. */
    @Unique private int earthplatform$pixelX, earthplatform$pixelZ;
    /** The column now being read, and the Y of the block its walk stopped on, where its water begins. */
    @Unique private int earthplatform$columnX, earthplatform$columnZ, earthplatform$waterTop;

    @WrapOperation(method = "update", require = 1, expect = 1, at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/core/SectionPos;blockToSectionCoord(I)I"))
    private int earthplatform$notePixelX(int blockX, Operation<Integer> original) {
        earthplatform$pixelX = blockX;
        return original.call(blockX);
    }

    @WrapOperation(method = "update", require = 1, expect = 1, at = @At(value = "INVOKE", ordinal = 1,
            target = "Lnet/minecraft/core/SectionPos;blockToSectionCoord(I)I"))
    private int earthplatform$notePixelZ(int blockZ, Operation<Integer> original) {
        earthplatform$pixelZ = blockZ;
        return original.call(blockZ);
    }

    /**
     * A pixel with a column whose cubes are not loaded is not read: vanilla skips a pixel whose chunk
     * is empty and leaves it unexplored, and the chunk is left null here for {@link #earthplatform$skipped}
     * to say so, with nothing allocated and no carrier chunk loaded for it. The pixel is the unit,
     * not its chunk's center: a chunk with some of its columns loaded draws those pixels, edges too.
     */
    @WrapOperation(method = "update", require = 1, expect = 1, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;"))
    private LevelChunk earthplatform$onlyLoadedPixels(Level level, int chunkX, int chunkZ, Operation<LevelChunk> original,
                                                      @Local(argsOnly = true) MapItemSavedData data) {
        if (CubicMaps.pixelLoaded((CubicLevel) level, earthplatform$pixelX, earthplatform$pixelZ, 1 << data.scale)) {
            return original.call(level, chunkX, chunkZ);
        }
        return null;
    }

    @WrapOperation(method = "update", require = 1, expect = 1, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/LevelChunk;isEmpty()Z"))
    private boolean earthplatform$skipped(LevelChunk chunk, Operation<Boolean> original) {
        return chunk == null || original.call(chunk);
    }

    @WrapOperation(method = "update", require = 1, expect = 1, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/LevelChunk;getHeight(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I"))
    private int earthplatform$cubeSurface(LevelChunk chunk, Heightmap.Types type, int x, int z, Operation<Integer> original,
                                          @Local(argsOnly = true) Level level) {
        earthplatform$columnX = x;
        earthplatform$columnZ = z;
        CubeWorld cubes = CubicLevel.at(level, x, z);
        return cubes == null ? original.call(chunk, type, x, z) : CubicMaps.surface(cubes, x, z);
    }

    /** The walk down from the surface to the first block with a color. */
    @WrapOperation(method = "update", require = 1, expect = 1, at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/world/level/chunk/LevelChunk;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState earthplatform$cubeBlock(LevelChunk chunk, BlockPos pos, Operation<BlockState> original,
                                               @Local(argsOnly = true) Level level) {
        CubeWorld cubes = CubicLevel.at(level, pos.getX(), pos.getZ());
        earthplatform$waterTop = pos.getY();
        return cubes == null ? original.call(chunk, pos) : CubicMaps.state(cubes.getBlockState(pos));
    }

    /** The walk down through the water under that block. */
    @WrapOperation(method = "update", require = 1, expect = 1, at = @At(value = "INVOKE", ordinal = 1,
            target = "Lnet/minecraft/world/level/chunk/LevelChunk;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState earthplatform$cubeWater(LevelChunk chunk, BlockPos pos, Operation<BlockState> original,
                                               @Local(argsOnly = true) Level level) {
        CubeWorld cubes = CubicLevel.at(level, pos.getX(), pos.getZ());
        return cubes == null ? original.call(chunk, pos) : CubicMaps.waterState(cubes.getBlockState(pos));
    }

    /** The carrier ends at Y 0, but the ground and the sea floor go down to the world's bottom, for the columns the cubes own. */
    @WrapOperation(method = "update", require = 3, expect = 3, at = {
            @At(value = "INVOKE", ordinal = 0, target = "Lnet/minecraft/world/level/Level;getMinBuildHeight()I"),
            @At(value = "INVOKE", ordinal = 1, target = "Lnet/minecraft/world/level/Level;getMinBuildHeight()I"),
            @At(value = "INVOKE", ordinal = 2, target = "Lnet/minecraft/world/level/Level;getMinBuildHeight()I")})
    private int earthplatform$cubicBottom(Level level, Operation<Integer> original) {
        return CubicMaps.floor(CubicLevel.at(level, earthplatform$columnX, earthplatform$columnZ), original.call(level));
    }

    /** The water's own walk stops short of the bottom of a sea of eleven kilometres. */
    @WrapOperation(method = "update", require = 1, expect = 1, at = @At(value = "INVOKE", ordinal = 3,
            target = "Lnet/minecraft/world/level/Level;getMinBuildHeight()I"))
    private int earthplatform$waterBottom(Level level, Operation<Integer> original) {
        return CubicMaps.waterFloor(CubicLevel.at(level, earthplatform$columnX, earthplatform$columnZ), original.call(level), earthplatform$waterTop);
    }
}
