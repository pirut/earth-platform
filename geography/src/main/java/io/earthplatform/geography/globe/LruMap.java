package io.earthplatform.geography.globe;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A map kept in least-recently-used order, trimmed by its caller: {@link #putAndTrim} adds an entry
 * and then drops the least recently used ones until no more than a given number remain. The
 * globe's sources keep their decoded tiles, blocks and rows in these.
 *
 * <p>Not synchronized: each source locks its own map around every use, as it would a plain
 * {@link LinkedHashMap}.
 */
final class LruMap<K, V> extends LinkedHashMap<K, V> {
    private static final long serialVersionUID = 1L;
    /** The cap last passed to {@link #putAndTrim}, so a memory shortage can trim without waiting for the next put. */
    private int cap;
    /** Keeps this map registered with {@link MemoryPressure} for as long as the map itself is alive. */
    @SuppressWarnings("unused")
    private final MemoryPressure.Hook pressure = MemoryPressure.keep(this::trimNow);

    /** @param initialCapacity the starting size of the backing table, as for {@link LinkedHashMap} */
    LruMap(int initialCapacity) {
        super(initialCapacity, 0.75f, true);
    }

    /**
     * Puts an entry, then drops the least recently used entries while more than {@code most} remain,
     * or a quarter of that while memory is short (see {@link MemoryPressure#scaled}).
     */
    void putAndTrim(K key, V value, int most) {
        cap = most;
        put(key, value);
        trimTo(MemoryPressure.scaled(most));
    }

    /** Drops down to the short cap. Called on the watcher thread as well as under the caller's lock. */
    synchronized void trimNow() {
        if (cap > 0) trimTo(MemoryPressure.scaled(cap));
    }

    private void trimTo(int kept) {
        Iterator<Map.Entry<K, V>> iterator = entrySet().iterator();
        while (size() > kept && iterator.hasNext()) { iterator.next(); iterator.remove(); }
    }
}
