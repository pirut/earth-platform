package io.earthplatform.core;

import java.util.Arrays;
import java.util.Map;

/** Immutable generated block-state IDs. State 0 conventionally denotes air. */
public final class ImmutableCube {
    private final int uniform;
    private final int[] blocks;
    private ImmutableCube(int uniform, int[] ownedBlocks) { this.uniform = uniform; this.blocks = ownedBlocks; }
    public static ImmutableCube uniform(int state) { checkState(state); return new ImmutableCube(state, null); }
    public static ImmutableCube copyOf(int[] blocks) {
        if (blocks.length != CubePos.VOLUME) throw new IllegalArgumentException("Expected 4096 blocks");
        int[] copy = blocks.clone();
        boolean same = true;
        for (int state : copy) { checkState(state); same &= state == copy[0]; }
        return same ? uniform(copy[0]) : new ImmutableCube(0, copy);
    }
    public static void checkState(int state) {
        if (state < 0) throw new IllegalArgumentException("Block state must be nonnegative");
    }
    public int get(int index) { CubePos.checkIndex(index); return blocks == null ? uniform : blocks[index]; }
    public int get(int x, int y, int z) { return get(CubePos.index(x, y, z)); }
    public boolean isUniform() { return blocks == null; }
    /** Payload only, excluding VM-specific object/array overhead. */
    public int payloadBytes() { return blocks == null ? Integer.BYTES : blocks.length * Integer.BYTES; }
    public int[] toArray() {
        if (blocks != null) return blocks.clone();
        int[] copy = new int[CubePos.VOLUME]; Arrays.fill(copy, uniform); return copy;
    }
    /** Apply an overlay without changing this generated base. */
    public ImmutableCube withEdits(Map<Integer, Integer> edits) {
        if (edits.isEmpty()) return this;
        int[] copy = toArray();
        edits.forEach((index, state) -> { CubePos.checkIndex(index); checkState(state); copy[index] = state; });
        return copyOf(copy);
    }
    @Override public boolean equals(Object other) {
        if (!(other instanceof ImmutableCube c)) return false;
        if (blocks == null && c.blocks == null) return uniform == c.uniform;
        return Arrays.equals(toArray(), c.toArray());
    }
    @Override public int hashCode() { return Arrays.hashCode(toArray()); }
}
