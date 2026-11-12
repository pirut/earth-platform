package io.earthplatform.minecraft.cubic.client;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * Whether a cube is solid stone or deepslate sealed in by more of it on all six faces, below its column's sky. Such a
 * cube shows no face and holds no light, so it needs no mesh. Proved from the blocks, never guessed from the height.
 */
final class CubeInterior {
    /** The cube and its six face neighbours, by snapshot index. */
    private static final int[] REQUIRED = {13, 4, 22, 10, 16, 12, 14};

    private CubeInterior() {}

    static boolean enclosedStone(PalettedContainer<BlockState>[] states, int[][] tops, int cy) {
        if (states == null || states.length != 27) return false;
        // Light seeds the sky above each column's top, so the cube's own column must have tops, all above the cube.
        if (tops == null || tops.length != 9 || tops[4] == null || tops[4].length != 256) return false;
        for (int top : tops[4]) if (top < (cy << 4) + 15) return false;
        for (int index : REQUIRED) {
            PalettedContainer<BlockState> section = states[index];
            if (section == null || section.maybeHas(state -> !stone(state))) return false;
        }
        return true;
    }

    private static boolean stone(BlockState state) { return state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE); }
}
