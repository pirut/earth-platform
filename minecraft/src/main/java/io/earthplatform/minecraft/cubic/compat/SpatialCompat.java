package io.earthplatform.minecraft.cubic.compat;

import io.earthplatform.minecraft.cubic.Cube;
import io.earthplatform.minecraft.cubic.CubeKey;
import io.earthplatform.minecraft.cubic.CubeWorld;
import io.earthplatform.minecraft.cubic.CubicLevel;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * AE2 spatial storage in the cubic level. AE2 swaps a region with a spatial cell by editing chunk
 * sections and chunk block entity maps directly. In Earth those reads and writes go to the cubes,
 * and every cube it handed out is folded back in afterwards (sky tops, light, the save, clients).
 */
public final class SpatialCompat {
    /** Cubes handed to AE2 during the current swap, with their store. Server thread only. */
    private static final Map<Cube, CubeWorld> TOUCHED = new IdentityHashMap<>();

    private SpatialCompat() {}

    @Nullable public static CubeWorld cubes(LevelChunk chunk) {
        return CubicLevel.at(chunk.getLevel(), chunk.getPos().getMinBlockX(), chunk.getPos().getMinBlockZ());
    }

    /** The cube's section, or a throwaway empty one where no cube could be had, since AE2 writes into it. */
    public static LevelChunkSection section(LevelChunk chunk, int index) {
        CubeWorld cubes = cubes(chunk);
        boolean[] real = {false};
        LevelChunkSection section = CubeSections.section(chunk, index, cube -> { TOUCHED.put(cube, cubes); real[0] = true; });
        return real[0] || cubes == null ? section : new LevelChunkSection(chunk.getLevel().registryAccess().registryOrThrow(Registries.BIOME));
    }

    /** The block entities of a chunk column's loaded cubes, as AE2 reads a chunk's own map. */
    public static Map<BlockPos, BlockEntity> blockEntities(CubeWorld cubes, LevelChunk chunk) {
        Map<BlockPos, BlockEntity> found = new HashMap<>();
        int x = chunk.getPos().x, z = chunk.getPos().z;
        for (int y = CubeWorld.MIN_Y >> 4; y <= CubeWorld.MAX_Y >> 4; y++) {
            Cube cube = cubes.cube(CubeKey.pack(x, y, z));
            if (cube != null) found.putAll(cube.blockEntities());
        }
        return found;
    }

    /** After a swap: tell each cube store about the cubes AE2 rewrote. */
    public static void finish() {
        if (TOUCHED.isEmpty()) return;
        Map<Cube, CubeWorld> done = new IdentityHashMap<>(TOUCHED);
        TOUCHED.clear();
        done.forEach((cube, cubes) -> cubes.rewritten(cube));
    }
}
