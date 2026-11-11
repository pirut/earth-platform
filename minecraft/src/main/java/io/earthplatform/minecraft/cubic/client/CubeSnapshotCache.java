package io.earthplatform.minecraft.cubic.client;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

/** Client-thread-owned LRU. Workers only read returned copies; live edits invalidate them. */
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
        var copy = source.copy();
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
