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

    /** @param initialCapacity the starting size of the backing table, as for {@link LinkedHashMap} */
    LruMap(int initialCapacity) {
        super(initialCapacity, 0.75f, true);
    }

    /** Puts an entry, then drops the least recently used entries while more than {@code most} remain. */
    void putAndTrim(K key, V value, int most) {
        put(key, value);
        Iterator<Map.Entry<K, V>> iterator = entrySet().iterator();
        while (size() > most && iterator.hasNext()) { iterator.next(); iterator.remove(); }
    }
}
