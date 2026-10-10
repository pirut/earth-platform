package io.earthplatform.minecraft;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Presentation-only height heuristic, not measured land cover, climate or seabed geology.
 * Replaces existing solid materials without adding voxels or changing the sampled surface.
 */
public final class HeuristicSurface {
    public static final String VERSION = "height-heuristic-v1";
    public static final long ROCK_FROM_Y = 3000;
    public static final long SNOW_FROM_Y = 4800;
    public static final long SHALLOW_FLOOR_FROM_Y = -32;

    private HeuristicSurface() {}

    /** IDs are the geography module's MaterialPalette.DEFAULT, not Minecraft registry IDs. */
    public static BlockState map(int materialId, long surfaceY) {
        return switch (materialId) {
            case 0 -> Blocks.AIR.defaultBlockState();
            case 1 -> Blocks.STONE.defaultBlockState();
            case 2 -> (surfaceY >= ROCK_FROM_Y ? Blocks.STONE : Blocks.DIRT).defaultBlockState();
            case 3 -> (surfaceY >= SNOW_FROM_Y ? Blocks.SNOW_BLOCK
                    : surfaceY >= ROCK_FROM_Y ? Blocks.STONE : Blocks.GRASS_BLOCK).defaultBlockState();
            case 4 -> Blocks.WATER.defaultBlockState();
            case 5 -> (surfaceY >= SHALLOW_FLOOR_FROM_Y ? Blocks.SAND : Blocks.GRAVEL).defaultBlockState();
            default -> throw new IllegalArgumentException("Unmapped proof material: " + materialId);
        };
    }
}
