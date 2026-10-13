package io.earthplatform.minecraft.cubic.compat;

import com.mojang.datafixers.util.Pair;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import io.earthplatform.minecraft.cubic.gen.CubeGenerator;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * Biome searches in the Earth world. The level's chunk generator is the carrier's flat plains, so
 * /locate biome and biome compasses that ask its biome source only ever found plains. These ask the
 * Earth generator instead, which knows the biome anywhere without generating cubes.
 */
public final class EarthBiomes {
    private static final int REACH = 256;

    private EarthBiomes() {}

    @Nullable
    public static CubeGenerator generator(Level level) {
        return CubicLevel.of(level) instanceof ServerCubeWorld cubes ? cubes.generator() : null;
    }

    /** Heights a search samples: the surface first, then around where the search started. */
    public static int[] heights(int originY, int step) {
        int y = Mth.clamp(originY, CubeWorld.MIN_Y, CubeWorld.MAX_Y);
        return IntStream.concat(IntStream.of(CubeWorld.MAX_Y),
                Mth.outFromOrigin(y, Math.max(CubeWorld.MIN_Y, y - REACH), Math.min(CubeWorld.MAX_Y, y + REACH), step)).toArray();
    }

    /** Vanilla's 3D biome search (a spiral out from the origin), against the Earth generator. */
    @Nullable
    public static Pair<BlockPos, Holder<Biome>> closest(CubeGenerator generator, Predicate<Holder<Biome>> filter, BlockPos origin,
            int radius, int horizontalStep, int verticalStep) {
        int[] heights = heights(origin.getY(), verticalStep);
        for (BlockPos.MutableBlockPos step : BlockPos.spiralAround(BlockPos.ZERO, Math.floorDiv(radius, horizontalStep), Direction.EAST, Direction.SOUTH)) {
            int x = origin.getX() + step.getX() * horizontalStep, z = origin.getZ() + step.getZ() * horizontalStep;
            for (int y : heights) {
                Holder<Biome> biome = generator.biome(x, y, z);
                if (biome != null && filter.test(biome)) {
                    return Pair.of(new BlockPos(x, y == CubeWorld.MAX_Y ? generator.surfaceY(x, z) + 1 : y, z), biome);
                }
            }
        }
        return null;
    }
}
