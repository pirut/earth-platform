package io.earthplatform.minecraft.cubic;

import javax.annotation.Nullable;
import net.minecraft.world.level.Level;

/** Added to every Level by the cubic mixin. Non-cubic levels keep a null store and run vanilla code. */
public interface CubicLevel {
    @Nullable CubeWorld earthplatform$cubes();
    void earthplatform$setCubes(@Nullable CubeWorld cubes);

    /**
     * Marks a rectangle of chunk columns that another mod keeps in its own real chunks, such as the
     * plots where Sable stores ships. Cube routing leaves those columns to vanilla code.
     */
    void earthplatform$keepInChunks(int minX, int minZ, int maxX, int maxZ);
    boolean earthplatform$keptInChunks(int chunkX, int chunkZ);

    @Nullable static CubeWorld of(Level level) { return ((CubicLevel) level).earthplatform$cubes(); }

    /** The cube store for a block column, or null when the level isn't cubic or the column is kept in chunks. */
    @Nullable static CubeWorld at(Level level, int blockX, int blockZ) {
        CubicLevel cubic = (CubicLevel) level;
        CubeWorld cubes = cubic.earthplatform$cubes();
        return cubes == null || cubic.earthplatform$keptInChunks(blockX >> 4, blockZ >> 4) ? null : cubes;
    }
}
