package io.earthplatform.minecraft.cubic.compat;

import io.earthplatform.minecraft.cubic.Cube;
import io.earthplatform.minecraft.cubic.CubeKey;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.ServerCubeWorld;
import javax.annotation.Nullable;
import java.util.function.Consumer;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * For mods that read or write a chunk's sections directly. In the cubic level a carrier chunk holds
 * no blocks, so their section lookups are answered from the cubes instead, with section indices
 * counted from the lowest cube so every height has one.
 */
public final class CubeSections {
    private static final int MIN_SECTION = CubeWorld.MIN_Y >> 4;
    public static final int COUNT = (CubeWorld.MAX_Y - CubeWorld.MIN_Y + 1) >> 4;
    private static LevelChunkSection empty;

    private CubeSections() {}

    public static int index(int sectionY) { return sectionY - MIN_SECTION; }

    public static int indexOfBlock(int blockY) { return index(blockY >> 4); }

    /** The section at a cubic index: the cube's own storage, loading it on the server if needed. */
    public static LevelChunkSection section(LevelChunk chunk, int index) { return section(chunk, index, null); }

    /** As {@link #section(LevelChunk, int)}, telling {@code touched} which cube was handed out. */
    public static LevelChunkSection section(LevelChunk chunk, int index, @Nullable Consumer<Cube> touched) {
        int sectionY = index + MIN_SECTION;
        Level level = chunk.getLevel();
        CubeWorld cubes = CubicLevel.at(level, chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ());
        if (cubes == null) {
            int own = sectionY - chunk.getMinSection();
            return own >= 0 && own < chunk.getSectionsCount() ? chunk.getSection(own) : empty(level);
        }
        int x = chunk.getPos().x, z = chunk.getPos().z;
        Cube cube = cubes.cube(CubeKey.pack(x, sectionY, z));
        // Above the highest generated block a missing cube is just air: no need to build it for a
        // ship racing along high up.
        if (cube == null && cubes instanceof ServerCubeWorld server && sectionY << 4 <= server.generator().highestGenerated() && server.isOwnerThread()) {
            cube = server.loadNow(x, sectionY, z);
        }
        if (cube == null) return empty(level);
        if (touched != null) touched.accept(cube);
        return cube.section;
    }

    private static LevelChunkSection empty(Level level) {
        LevelChunkSection section = empty;
        if (section == null) empty = section = new LevelChunkSection(level.registryAccess().registryOrThrow(Registries.BIOME));
        return section;
    }
}
