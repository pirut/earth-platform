package io.earthplatform.minecraft.cubic.gen;

import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Deterministic cube source. Implementations are called from several worker threads at once and
 * must return identical cubes for the same seed, so unedited cubes never need saving.
 */
public interface CubeGenerator {
    String id();
    LevelChunkSection generate(int cubeX, int cubeY, int cubeZ);
    /** Generated ground height (top solid block) for a column. */
    int surfaceY(int x, int z);
    /** Highest generated block that blocks skylight in a column, used as the initial sky top. */
    default int skyTopY(int x, int z) { return surfaceY(x, z); }
}
