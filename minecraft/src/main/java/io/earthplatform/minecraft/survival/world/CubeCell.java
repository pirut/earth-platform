package io.earthplatform.minecraft.survival.world;

import io.earthplatform.core.CubePos;
import java.util.Objects;

/** Lossless signed cubic address. Never route authoritative cells through BlockPos.asLong(). */
public record CubeCell(CubePos cube, int localIndex) implements Comparable<CubeCell> {
    public CubeCell {
        Objects.requireNonNull(cube, "cube");
        CubePos.checkIndex(localIndex);
    }
    public static CubeCell of(long x, long y, long z) {
        return new CubeCell(CubePos.fromBlock(x, y, z), CubePos.indexOfBlock(x, y, z));
    }
    public long x() { return cube.minBlockX() + (localIndex & 15); }
    public long y() { return cube.minBlockY() + ((localIndex >>> 8) & 15); }
    public long z() { return cube.minBlockZ() + ((localIndex >>> 4) & 15); }
    @Override public int compareTo(CubeCell other) {
        int order = cube.compareTo(other.cube);
        return order == 0 ? Integer.compare(localIndex, other.localIndex) : order;
    }
}
