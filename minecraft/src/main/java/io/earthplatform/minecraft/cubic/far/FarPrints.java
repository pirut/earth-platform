package io.earthplatform.minecraft.cubic.far;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Fingerprints of the far columns last made, so a column remade after a save that came out the
 * same is not sent and remeshed again. The made columns themselves are kept for only a few
 * thousand, while a player holds tens of thousands: every autosave over a build used to push and
 * remesh its columns at all levels again, which looked like the far terrain reloading. A print is
 * 16 bytes, so all of a player's columns fit in a few megabytes.
 */
final class FarPrints {
    /** Most prints kept; past it they are forgotten, and remade columns are sent as before. */
    static final int MOST = 1 << 18;
    private static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /** The print of each column as last made. */
    private final Long2LongOpenHashMap made = new Long2LongOpenHashMap();
    /** The print of each edited column as players held it, until it is remade. */
    private final Long2LongOpenHashMap before = new Long2LongOpenHashMap();

    /** A column was made. */
    synchronized void made(long key, long print) {
        if (made.size() >= MOST && !made.containsKey(key)) made.clear();
        made.put(key, print);
    }

    /** A column's saved cubes changed: remember how it looked, to tell whether its remake differs. */
    synchronized void edited(long key) {
        if (made.containsKey(key) && !before.containsKey(key) && before.size() < MOST) before.put(key, made.get(key));
    }

    /** An edited column was remade, its print just recorded: whether it came out as players already have it. */
    synchronized boolean remadeSame(long key) {
        if (!before.containsKey(key)) return false;
        long was = before.remove(key);
        return made.containsKey(key) && made.get(key) == was;
    }

    /** An edited column no one holds is not remade. */
    synchronized void dropped(long key) { before.remove(key); }

    /** A 64-bit fingerprint of a column's contents. */
    static long of(int bottom, List<byte[]> sections, short[] drops) {
        long h = mix(0x9E3779B97F4A7C15L ^ bottom);
        h = mix(h ^ sections.size());
        for (byte[] section : sections) {
            h = mix(h ^ section.length);
            int i = 0;
            for (; i + 8 <= section.length; i += 8) h = mix(h ^ (long) LONGS.get(section, i));
            for (; i < section.length; i++) h = mix(h ^ (section[i] & 0xFF) ^ ((long) i << 32));
        }
        h = mix(h ^ drops.length);
        for (short drop : drops) h = mix(h ^ drop);
        return h;
    }

    /** SplitMix64's finalizer: a bijection, so different inputs at one step stay different. */
    private static long mix(long x) {
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        return x ^ (x >>> 31);
    }
}
