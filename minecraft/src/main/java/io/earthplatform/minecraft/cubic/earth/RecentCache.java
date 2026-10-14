package io.earthplatform.minecraft.cubic.earth;

import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * Values by key, keeping the recently used ones: entries go into a young map, and once it holds
 * {@code size} the old map is dropped and the young one becomes old. A value found in the old map
 * moves back to the young one. Clearing everything once full, as a plain map does, threw away the
 * surveys round the far terrain being made at that moment, and every one of them was surveyed again
 * from tiles long gone from memory.
 */
final class RecentCache<V> {
    private final int size;
    private volatile ConcurrentHashMap<Long, V> young = new ConcurrentHashMap<>(), old = new ConcurrentHashMap<>();

    RecentCache(int size) { this.size = size; }

    @Nullable V get(long key) {
        V value = young.get(key);
        if (value != null) return value;
        value = old.get(key);
        if (value != null) young.putIfAbsent(key, value);
        return value;
    }

    void put(long key, V value) {
        ConcurrentHashMap<Long, V> map = young;
        if (map.size() >= size) {
            synchronized (this) {
                if (young == map) {
                    old = map;
                    young = new ConcurrentHashMap<>();
                }
            }
        }
        young.putIfAbsent(key, value);
    }
}
