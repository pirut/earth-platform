package io.earthplatform.minecraft.cubic;

import javax.annotation.Nullable;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a vanilla filled map (see {@code CubicMapItemMixin}) reads from the Earth. A map samples a
 * column's surface through its carrier chunk, which is empty: its heightmap says there is no ground,
 * so every pixel came out as the bedrock under the world, grey. Here the surface comes from the
 * cubes, and a pixel with a column whose cubes are not loaded is left unexplored, as vanilla leaves
 * an unloaded chunk, instead of drawn as bedrock.
 *
 * <p>Columns the cubes do not own (the plots where Sable keeps ships) are read from their real
 * chunks, with vanilla's floor, so a map never walks the thousands of blocks below an empty one.
 */
public final class CubicMaps {
    private CubicMaps() {}

    /**
     * How far down a map follows water, as vanilla's own world is tall. Water deeper than this reads
     * the same (the map's darkest blue starts at a depth of about nine), and an ocean of the Earth is
     * eleven kilometres deep.
     */
    public static final int WATER_SCAN_LIMIT = 384;

    /** How many cubes above a column's sky top a map looks for the glass or leaves that cover it. */
    public static final int CANOPY_CUBES = 4;

    /**
     * Whether the cube holding this column's surface is loaded, so the map can read the column. A
     * column known only from the generator's baseline, with nothing loaded, is not.
     */
    public static boolean columnLoaded(CubeWorld cubes, int x, int z) {
        int[] column = cubes.columnTops(x >> 4, z >> 4);
        if (column == null) return false;
        return cubes.cubeAtBlock(x, column[(z & 15) << 4 | (x & 15)], z) != null;
    }

    /**
     * Whether a map pixel, the {@code size} by {@code size} blocks from {@code (x0, z0)}, can be
     * drawn: every column of it that the cubes own has its surface cube loaded. One column that is
     * not loaded would otherwise be drawn as bedrock at the bottom of the world and pull the pixel's
     * average and its shading toward it, so the pixel is left unexplored for now, as vanilla leaves
     * a pixel of an unloaded chunk. Allocates nothing.
     */
    public static boolean pixelLoaded(CubicLevel level, int x0, int z0, int size) {
        CubeWorld cubes = level.earthplatform$cubes();
        if (cubes == null) return true;
        for (int x = x0; x < x0 + size; x++) {
            for (int z = z0; z < z0 + size; z++) {
                if (level.earthplatform$keptInChunks(x >> 4, z >> 4)) continue;
                if (!columnLoaded(cubes, x, z)) return false;
            }
        }
        return true;
    }

    /**
     * The highest Y of the column's surface as a map sees it, what a world-surface heightmap holds:
     * the highest block that is not air. That can lie above the sky top, since stained glass, leaves
     * and the like let skylight through; the loaded cubes above the sky top are searched for them,
     * stopping at the first cube that is not loaded. One below the world when the sky top's own cube
     * is not loaded, which a map draws as the bedrock it assumes there.
     */
    public static int surface(CubeWorld cubes, int x, int z) {
        int top = cubes.skyTop(x, z);
        if (cubes.cubeAtBlock(x, top, z) == null) return CubeWorld.MIN_Y - 1;
        int found = top, topCube = top >> 4;
        for (int cubeY = topCube; cubeY <= topCube + CANOPY_CUBES && (cubeY << 4) <= CubeWorld.MAX_Y; cubeY++) {
            Cube cube = cubes.cubeAtBlock(x, cubeY << 4, z);
            if (cube == null) break;
            if (cube.isEmpty()) continue;
            int low = Math.max(top + 1, cubeY << 4);
            for (int y = Math.min((cubeY << 4) + 15, CubeWorld.MAX_Y); y >= low; y--) {
                if (!cube.get(x, y, z).isAir()) { found = Math.max(found, y); break; }
            }
        }
        return found;
    }

    /**
     * A block as a map reads it going down a column to the first with a color. Ground in a cube that
     * is not loaded counts as stone, so that walk stops there instead of going on, block by block,
     * to the bottom of the world.
     */
    public static BlockState state(BlockState read) {
        return read == CubeWorld.UNLOADED ? Blocks.STONE.defaultBlockState() : read;
    }

    /**
     * A block as a map reads it going down through water to measure its depth. A cube that is not
     * loaded below the water is not the floor of it, so it counts as more water: the sea reads deep
     * until the cube arrives, not shallow.
     */
    public static BlockState waterState(BlockState read) {
        return read == CubeWorld.UNLOADED ? Blocks.WATER.defaultBlockState() : read;
    }

    /**
     * Where a map's walk down a column stops: the bottom of the world for a column the cubes own,
     * which ends at the first cube not loaded anyway, and {@code vanillaFloor}, the level's own, for
     * one that is read from a real chunk (a Sable plot). Reading such a column down to the cubes'
     * floor would be 16 000 lookups of air for each sample.
     */
    public static int floor(@Nullable CubeWorld cubes, int vanillaFloor) {
        return cubes == null ? vanillaFloor : CubeWorld.MIN_Y;
    }

    /** Where a map's walk down through water that begins at {@code waterTop} stops: {@link #floor}, but no more than {@link #WATER_SCAN_LIMIT} down. */
    public static int waterFloor(@Nullable CubeWorld cubes, int vanillaFloor, int waterTop) {
        return cubes == null ? vanillaFloor : Math.max(CubeWorld.MIN_Y, waterTop - WATER_SCAN_LIMIT);
    }
}
