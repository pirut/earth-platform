package io.earthplatform.minecraft.cubic;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * What a vanilla filled map (see {@code CubicMapItemMixin}) reads from the Earth. A map samples a
 * column's surface through its carrier chunk, which is empty: its heightmap says there is no ground,
 * so every pixel came out as the bedrock under the world, grey. Here the surface comes from the
 * cubes, and a column whose cubes are not loaded is left unexplored, as vanilla leaves an
 * unloaded chunk, instead of drawn as bedrock.
 */
public final class CubicMaps {
    private CubicMaps() {}

    /**
     * Whether the cube holding this column's surface is loaded, so the map can read the column. A
     * column known only from the generator's baseline, with nothing loaded, is not.
     */
    public static boolean columnLoaded(CubeWorld cubes, int x, int z) {
        if (cubes.columnTops(x >> 4, z >> 4) == null) return false;
        return cubes.cubeAtBlock(x, cubes.skyTop(x, z), z) != null;
    }

    /**
     * The highest Y of the column's surface (what a world-surface heightmap holds), or one below the
     * world when that cube is not loaded, which a map draws as the bedrock it assumes there.
     */
    public static int surface(CubeWorld cubes, int x, int z) {
        int y = cubes.heightTop(Heightmap.Types.WORLD_SURFACE, x, z);
        return cubes.cubeAtBlock(x, y, z) == null ? CubeWorld.MIN_Y - 1 : y;
    }

    /**
     * A block as a map reads it going down a column. Ground in a cube that is not loaded counts as
     * stone, so the map's walk through air stops there instead of going on, block by block, to the
     * bottom of the world.
     */
    public static BlockState state(BlockState read) {
        return read == CubeWorld.UNLOADED ? Blocks.STONE.defaultBlockState() : read;
    }
}
