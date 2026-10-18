package io.earthplatform.minecraft.cubic.gen;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * One far-terrain column as a generator fills it: a stack of 16^3-voxel sections whose voxels are
 * 2^level blocks, each voxel holding the block that best stands for the blocks it covers.
 */
public final class FarColumn {
    /** The lowest section's Y in this level's section units; everything below counts as solid ground. */
    public int bottom;
    /** Optional immutable prototype base; sections remain the authoritative legacy/edit boundary. */
    @javax.annotation.Nullable public FarSurfacePacket packet;
    /** Sections from the bottom upward; null for one that is all air. Everything above is air. */
    public final List<LevelChunkSection> sections = new ArrayList<>();
    /**
     * For each voxel column (x + 16 z), how far its whole stack is lowered, in 1/{@link
     * io.earthplatform.minecraft.cubic.far.Far#DROP_UNIT} blocks, so its top meets the real ground
     * or water height; from 0 up to one voxel.
     */
    public final short[] drops = new short[256];
}
