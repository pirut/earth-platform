package io.earthplatform.minecraft.cubic.client;

import javax.annotation.Nullable;

/**
 * Where far columns are asked for ahead of the player at elytra and jet stream speeds. A level's
 * edge only takes in a row of finer columns once the camera crosses into the next coarser column,
 * and asked for then, they came in one by one over the next seconds, each coarse column flashing
 * into its finer ones on its own; asked for ahead, the row is there when the edge gets to it and
 * fades in together (see {@link FarFade}).
 */
final class FarLead {
    /** Seconds of flight ahead: about as long as a column takes to be made, sent and meshed at the jet stream. */
    static final double SECONDS = 3.0;

    private FarLead() {}

    /** Where the player will be {@link #SECONDS} on at a speed in blocks a second, or null when too slow for it to matter. */
    @Nullable static int[] at(int bx, int bz, double blocksPerSecondX, double blocksPerSecondZ) {
        if (Math.hypot(blocksPerSecondX, blocksPerSecondZ) < FarRenderer.FAST_BLOCKS_PER_SECOND) return null;
        return new int[] {bx + (int) Math.round(blocksPerSecondX * SECONDS), bz + (int) Math.round(blocksPerSecondZ * SECONDS)};
    }
}
