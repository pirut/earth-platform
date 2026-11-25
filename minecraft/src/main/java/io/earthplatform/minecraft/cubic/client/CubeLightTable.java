package io.earthplatform.minecraft.cubic.client;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * Each cube's light from its last compile, by cube key. The owner (the render thread) reads and writes its own
 * map; any other thread, such as Flywheel's workers relighting a machine, reads a concurrent copy the owner keeps
 * in step. A light array is never changed after it is put, so a reader sees a whole old array or a whole new one.
 */
final class CubeLightTable {
    private final Thread owner;
    private final Long2ObjectOpenHashMap<byte[]> owned = new Long2ObjectOpenHashMap<>();
    private final ConcurrentHashMap<Long, byte[]> shared = new ConcurrentHashMap<>();

    CubeLightTable(Thread owner) {
        this.owner = owner;
    }

    /** Owner only. Returns the array it replaced. */
    @Nullable byte[] put(long key, byte[] light) {
        shared.put(key, light);
        return owned.put(key, light);
    }

    /** Owner only. Whether there was light to remove. */
    boolean remove(long key) {
        shared.remove(key);
        return owned.remove(key) != null;
    }

    /** Owner only. */
    boolean containsKey(long key) {
        return owned.containsKey(key);
    }

    /** Owner only. */
    void clear() {
        owned.clear();
        shared.clear();
    }

    /** From any thread. */
    @Nullable byte[] get(long key) {
        return Thread.currentThread() == owner ? owned.get(key) : shared.get(key);
    }
}
