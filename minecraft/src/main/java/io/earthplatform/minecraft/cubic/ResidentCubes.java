package io.earthplatform.minecraft.cubic;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongPredicate;
import net.minecraft.core.BlockPos;

/**
 * Server thread. Cubes kept loaded for something waiting on them, though no player is near: an
 * arrival's destination, with nobody there yet. The unload pass keeps only cubes near players, so
 * on a cold first join or a trip to an empty place the cubes in first went again while the last
 * were still being made, or while the structures there were being laid out. Each holder keeps its
 * own set, released when it is done (arrived, gave up, left, or the world closed).
 */
public final class ResidentCubes {
    /** Cubes kept about a spot: the 3x3 columns round its cube, from one below to three above, where an arrival may stand. */
    static final int BELOW = 1, ABOVE = 3;

    private final Map<Object, long[]> held = new HashMap<>();
    private final Long2IntOpenHashMap cubes = new Long2IntOpenHashMap(), columns = new Long2IntOpenHashMap();

    /** The cubes about a block an arrival there may read. */
    public static long[] around(BlockPos at) {
        int cx = at.getX() >> 4, cy = at.getY() >> 4, cz = at.getZ() >> 4;
        long[] keys = new long[9 * (BELOW + ABOVE + 1)];
        int i = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
            for (int dy = -BELOW; dy <= ABOVE; dy++) keys[i++] = CubeKey.pack(cx + dx, cy + dy, cz + dz);
        return keys;
    }

    /** Keeps these cubes for {@code holder}, in place of what it kept before. */
    public void keep(Object holder, long[] keys) {
        long[] had = held.get(holder);
        if (had != null && Arrays.equals(had, keys)) return;
        release(holder);
        held.put(holder, keys.clone());
        for (long key : keys) {
            cubes.addTo(key, 1);
            columns.addTo(CubeKey.column(CubeKey.x(key), CubeKey.z(key)), 1);
        }
    }

    /** Lets go of what {@code holder} kept, if anything. */
    public void release(Object holder) {
        long[] had = held.remove(holder);
        if (had == null) return;
        for (long key : had) {
            if (cubes.addTo(key, -1) <= 1) cubes.remove(key);
            long column = CubeKey.column(CubeKey.x(key), CubeKey.z(key));
            if (columns.addTo(column, -1) <= 1) columns.remove(column);
        }
    }

    /** Runs the landing of what {@code holder} waited for, then lets go of its cubes, however the landing ends (an exception too). */
    public void landing(Object holder, Runnable land) {
        try { land.run(); }
        finally { release(holder); }
    }

    public boolean holds(long cubeKey) { return cubes.containsKey(cubeKey); }

    public boolean holdsColumn(long column) { return columns.containsKey(column); }

    public int holders() { return held.size(); }

    public void clear() {
        held.clear();
        cubes.clear();
        columns.clear();
    }

    /** Of the loaded cubes, those the unload pass drops: not near a player ({@code watched}) and not held. */
    static LongArrayList unloadable(Iterable<Long> loaded, LongPredicate watched, ResidentCubes resident) {
        LongArrayList drop = new LongArrayList();
        for (long key : loaded) if (!watched.test(key) && !resident.holds(key)) drop.add(key);
        return drop;
    }

    /** For tests and reports: the cubes kept, by holder. */
    List<Object> holderList() { return List.copyOf(held.keySet()); }
}
