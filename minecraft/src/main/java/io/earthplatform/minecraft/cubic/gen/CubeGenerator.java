package io.earthplatform.minecraft.cubic.gen;

import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Deterministic cube source. Implementations are called from several worker threads at once and
 * must return identical cubes for the same seed, so unedited cubes never need saving.
 */
public interface CubeGenerator {
    String id();
    LevelChunkSection generate(int cubeX, int cubeY, int cubeZ);
    /**
     * The same cube, adding what it starts with beyond blocks: block entity tags (spawners, loot
     * chests) to {@code blockEntities} and entity tags (chest minecarts) to {@code entities}, both
     * at absolute positions inside the cube.
     */
    default LevelChunkSection generate(int cubeX, int cubeY, int cubeZ, List<CompoundTag> blockEntities, List<CompoundTag> entities) {
        return generate(cubeX, cubeY, cubeZ);
    }
    /** The nearest generated structure of a kind (a structure id) within {@code radius} chunks, or null. Slow; call off-thread. */
    default @javax.annotation.Nullable net.minecraft.core.BlockPos locate(java.util.function.Predicate<net.minecraft.world.level.levelgen.structure.Structure> structure, int x, int z, int radius) { return null; }
    /** Starts of this world's structures reaching a chunk column, at real heights. */
    default List<net.minecraft.world.level.levelgen.structure.StructureStart> structureStarts(int chunkX, int chunkZ) { return List.of(); }
    /** One structure's start in a chunk at real heights, or null. */
    default @javax.annotation.Nullable net.minecraft.world.level.levelgen.structure.StructureStart structureStart(net.minecraft.world.level.levelgen.structure.Structure structure, int chunkX, int chunkZ) { return null; }
    /**
     * The nearest biome passing a test, searched as vanilla's /locate biome does: columns every {@code step} blocks
     * spiralling out to {@code radius}, the ground's biome first, then the caves under it. Null where there is none.
     */
    default @javax.annotation.Nullable com.mojang.datafixers.util.Pair<net.minecraft.core.BlockPos, net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> findBiome(
            java.util.function.Predicate<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> biomes, net.minecraft.core.BlockPos from, int radius, int step) { return null; }
    /** What structures reach a block, for checking. */
    default String structuresAt(int x, int y, int z) { return ""; }
    /** The biome generated at a block, worked out without generating its cube, or null if unknown. */
    default @javax.annotation.Nullable net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> biome(int x, int y, int z) { return null; }
    /** Called once the server level the cubes are for exists, before any cube generates there. */
    default void attach(ServerLevel level) {}
    /** Generated ground height (top solid block) for a column. */
    int surfaceY(int x, int z);
    /**
     * Lowest generated ground of a 16 by 16 cube column, for telling which cubes lie buried under it. Slow the first
     * time for a column; call off-thread.
     */
    default int lowestGround(int cubeX, int cubeZ) {
        int low = Integer.MAX_VALUE;
        for (int i = 0; i < 256; i++) low = Math.min(low, surfaceY((cubeX << 4) + (i & 15), (cubeZ << 4) + (i >> 4)));
        return low;
    }
    /** Top generated water block of a column, or {@link Integer#MIN_VALUE} where it is dry. */
    default int waterY(int x, int z) { return Integer.MIN_VALUE; }
    /** Highest generated block that blocks skylight in a column, used as the initial sky top. */
    default int skyTopY(int x, int z) { return surfaceY(x, z); }
    /**
     * Fills a far-terrain column at a level: 16^3-voxel sections whose voxels are 2^level blocks,
     * covering every height the column's surface (and anything on it) can reach. Called from
     * worker threads.
     */
    void farColumn(int level, int columnX, int columnZ, FarColumn out);

    /** Top block of the sea: players arrive on ground above it. */
    int seaLevel();
    /** Ground too cold or high for a new arrival to start on. */
    default boolean barren(int x, int z, int y) { return false; }
    /** Block X and Z around which new players arrive. */
    default int[] arrival() { return new int[]{0, 0}; }
    /** Where a block column is in the generator's own terms (such as latitude and longitude), or empty. */
    default String place(int x, int z) { return ""; }
    /** Credits for the data the world is made from, shown to players entering it, or empty. */
    default String credits() { return ""; }
    /** Loads whatever generating near a point will need, so the server thread never waits on it. Blocks; call off-thread. */
    default void prepare(int x, int z) {}
    /** Loads only what the cubes about a point need, before {@link #prepare} loads the rest. Blocks; call off-thread. */
    default void prepareNear(int x, int z) { prepare(x, z); }
    /** Loads what {@link #prepare} does and what the far levels read as far as they reach, for a player arriving. Blocks; call off-thread. */
    default void prepareFar(int x, int z) { prepare(x, z); }
}
