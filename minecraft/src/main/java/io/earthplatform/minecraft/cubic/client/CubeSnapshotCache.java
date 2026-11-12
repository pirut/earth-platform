package io.earthplatform.minecraft.cubic.client;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * The copies of cubes' blocks that mesh snapshots hand to the workers, kept so the up to 27 snapshots a cube is in
 * copy it once, the least recently used going first. Used on the render thread only; the workers only read the copies.
 * A copy is good while its cube's storage is the same object and the renderer has not been told of an edit to it: the
 * renderer {@link #invalidate}s a cube whenever its blocks change in place.
 */
final class CubeSnapshotCache {
    private record Entry(PalettedContainer<BlockState> source, PalettedContainer<BlockState> copy) {}
    private final Long2ObjectLinkedOpenHashMap<Entry> entries = new Long2ObjectLinkedOpenHashMap<>();
    private final int maximum;
    private long hits, copies;

    CubeSnapshotCache(int maximum) {
        if (maximum < 1) throw new IllegalArgumentException("Snapshot capacity must be positive");
        this.maximum = maximum;
    }

    PalettedContainer<BlockState> get(long key, PalettedContainer<BlockState> source) {
        Entry cached = entries.getAndMoveToLast(key);
        if (cached != null && cached.source == source) { hits++; return cached.copy; }
        PalettedContainer<BlockState> copy = source.copy();
        copies++;
        entries.putAndMoveToLast(key, new Entry(source, copy));
        if (entries.size() > maximum) entries.removeFirst();
        return copy;
    }

    void invalidate(long key) { entries.remove(key); }
    void clear() { entries.clear(); }

    record Stats(int retained, int maximum, long hits, long copies) {}
    Stats stats() { return new Stats(entries.size(), maximum, hits, copies); }
}
