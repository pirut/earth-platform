package io.earthplatform.minecraft.cubic.client;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

/** A deliberately narrow proof of an invisible, unlit section; never a guess from height. */
final class CubeInterior {
    private static final int[] REQUIRED = {13, 4, 22, 10, 16, 12, 14};
    private CubeInterior() {}

    static boolean enclosedStone(PalettedContainer<BlockState>[] states, int[][] tops, int cy) {
        if (states == null || states.length != 27) return false;
        // Preserve the current lighting oracle even while sky tops are missing/stale.
        // It directly seeds sky above the reported top before checking opacity.
        if (tops == null || tops.length != 9 || tops[4] == null || tops[4].length != 256) return false;
        for (int top : tops[4]) if (top < (cy << 4) + 15) return false;
        for (int index : REQUIRED) {
            var section = states[index];
            if (section == null || section.maybeHas(state -> !stone(state))) return false;
        }
        return true;
    }

    private static boolean stone(BlockState state) {
        return state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE);
    }
}
