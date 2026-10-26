package io.earthplatform.minecraft.cubic.client;

import io.earthplatform.minecraft.cubic.far.Far;
import java.util.Arrays;
import java.util.function.LongFunction;
import java.util.function.ObjIntConsumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import javax.annotation.Nullable;

/**
 * Held far columns by place, so the pick and the plan find a column, or that none is held there,
 * without a hash lookup each: a level's columns go in a 256 by 256 table by the low bits of their
 * coordinates, which the columns held around a player never fill twice. Where two held columns of
 * a level share a place (far apart, as after a long trip, until the old ones go) the place is
 * marked crowded and the map answers for it. Flying, a sixth of the render thread went on hash
 * lookups, most of them for columns not held.
 *
 * <p>Beside each place a tag says whether a held column there is meshed, with the rest of its
 * coordinates to tell it from another place's column, so whether far terrain can be drawn at a
 * level somewhere (see {@link #meshed}), and which column is held there (see {@link #get}), are
 * answered from these tables alone, never reaching the columns themselves, spread over the heap.
 * So is when each column was last wanted (see {@link #want}), kept beside its tag rather than in
 * the column, except for columns in crowded places. Render thread only.
 */
final class FarPlaces<T> {
    private static final int BITS = 8, MASK = (1 << BITS) - 1;
    private static final Object CROWDED = new Object();
    /**
     * A tag: the coordinates above the place's bits (22 each, all a key holds), whether a column is
     * held there and meshed, or that the place is crowded.
     */
    private static final long WHERE = (1L << 44) - 1, MESHED = 1L << 44, HELD = 1L << 45, CROWDED_TAG = 1L << 46;

    private final Object[][] places = new Object[Far.MAX_LEVEL + 1][];
    private final long[][] tags = new long[Far.MAX_LEVEL + 1][];
    /** By place, the tick the column held there was last wanted; for a crowded place its columns keep their own. */
    private final int[][] wanted = new int[Far.MAX_LEVEL + 1][];
    private final ToLongFunction<T> keyOf;
    private final LongFunction<T> map;
    private final Predicate<T> meshedOf;
    private final ToIntFunction<T> wantedOf;
    private final ObjIntConsumer<T> wantedTo;
    /** Whether a column left a crowded place, which may have room again (see {@link #crowdedLeft}). */
    private boolean left;

    /**
     * Places for columns whose keys {@code keyOf} gives, looking crowded places up in {@code map},
     * and whose meshes are up when {@code meshedOf} says so. A column's own last-wanted tick, which
     * {@code wantedOf} reads and {@code wantedTo} writes, is taken in when it is placed, and is the
     * one kept while its place is crowded.
     */
    FarPlaces(ToLongFunction<T> keyOf, LongFunction<T> map, Predicate<T> meshedOf, ToIntFunction<T> wantedOf, ObjIntConsumer<T> wantedTo) {
        this.keyOf = keyOf;
        this.map = map;
        this.meshedOf = meshedOf;
        this.wantedOf = wantedOf;
        this.wantedTo = wantedTo;
    }

    /** Neighbors along x lie side by side, as the plan goes over a level's columns row by row. */
    private static int place(int x, int z) { return (z & MASK) << BITS | x & MASK; }

    /** A held column's tag at a level's column x, z, without whether it is meshed. */
    private static long where(int x, int z) { return HELD | (x >> BITS & 0x3FFFFF) | (long) (z >> BITS & 0x3FFFFF) << 22; }

    /** Whether a meshed column is held at a level's column x, z. */
    boolean meshed(int level, int x, int z) {
        long[] at = tags[level];
        if (at == null) return false;
        long tag = at[place(x, z)];
        if (tag == 0) return false;
        if (tag == CROWDED_TAG) {
            T column = map.apply(Far.key(level, x, z));
            return column != null && meshedOf.test(column);
        }
        return (tag & (WHERE | HELD)) == where(x, z) && (tag & MESHED) != 0;
    }

    /** Notes that a held column's mesh is up. */
    void meshed(T column) {
        long key = keyOf.applyAsLong(column);
        int level = Far.level(key), index = place(Far.x(key), Far.z(key));
        Object[] at = places[level];
        if (at != null && at[index] == column) tags[level][index] |= MESHED;
    }

    /**
     * The held column at a level's column x, z, or null. The place's tag tells whether it is this
     * column, so a place holding another column is answered without reaching that column for its key.
     */
    @SuppressWarnings("unchecked")
    @Nullable T get(int level, int x, int z) {
        long[] at = tags[level];
        if (at == null) return null;
        int index = place(x, z);
        long tag = at[index];
        if (tag == 0) return null;
        if (tag == CROWDED_TAG) return map.apply(Far.key(level, x, z));
        return (tag & (WHERE | HELD)) == where(x, z) ? (T) places[level][index] : null;
    }

    @Nullable T get(long key) { return get(Far.level(key), Far.x(key), Far.z(key)); }

    /** Whether a column is held at a level's column x, z, as {@link #get} would answer, without reaching it. */
    boolean has(int level, int x, int z) {
        long[] at = tags[level];
        if (at == null) return false;
        long tag = at[place(x, z)];
        if (tag == 0) return false;
        if (tag == CROWDED_TAG) return map.apply(Far.key(level, x, z)) != null;
        return (tag & (WHERE | HELD)) == where(x, z);
    }

    /**
     * Notes that the column held at a level's column x, z, if any, is wanted at {@code tick};
     * returns whether one is held there. The plan does this for every column it wants, twice a
     * second: writing the tick into each column, spread over the heap, took most of its time.
     */
    boolean want(int level, int x, int z, int tick) {
        long[] at = tags[level];
        if (at == null) return false;
        int index = place(x, z);
        long tag = at[index];
        if (tag == 0) return false;
        if (tag == CROWDED_TAG) {
            T column = map.apply(Far.key(level, x, z));
            if (column == null) return false;
            wantedTo.accept(column, tick);
            return true;
        }
        if ((tag & (WHERE | HELD)) != where(x, z)) return false;
        wanted[level][index] = tick;
        return true;
    }

    /** The tick a held column was last wanted (see {@link #want}). */
    int lastWanted(T column) {
        long key = keyOf.applyAsLong(column);
        int level = Far.level(key), index = place(Far.x(key), Far.z(key));
        Object[] at = places[level];
        return at != null && at[index] == column ? wanted[level][index] : wantedOf.applyAsInt(column);
    }

    /** Takes a column just put in the map, in place of the one it replaces, if any. */
    @SuppressWarnings("unchecked")
    void add(T column) {
        long key = keyOf.applyAsLong(column);
        int level = Far.level(key);
        Object[] at = places[level];
        if (at == null) {
            at = places[level] = new Object[1 << 2 * BITS];
            tags[level] = new long[1 << 2 * BITS];
            wanted[level] = new int[1 << 2 * BITS];
        }
        int x = Far.x(key), z = Far.z(key), index = place(x, z);
        Object there = at[index];
        if (there == null || there != CROWDED && keyOf.applyAsLong((T) there) == key) {
            at[index] = column;
            tags[level][index] = where(x, z) | (meshedOf.test(column) ? MESHED : 0);
            wanted[level][index] = wantedOf.applyAsInt(column);
        } else {
            // The column that had the place keeps its own tick from now on, as the map answers for both.
            if (there != CROWDED) wantedTo.accept((T) there, wanted[level][index]);
            at[index] = CROWDED;
            tags[level][index] = CROWDED_TAG;
        }
    }

    /** Lets go of a column just taken out of the map. */
    void remove(T column) {
        long key = keyOf.applyAsLong(column);
        Object[] at = places[Far.level(key)];
        if (at == null) return;
        int index = place(Far.x(key), Far.z(key));
        if (at[index] == column) {
            at[index] = null;
            tags[Far.level(key)][index] = 0;
        } else if (at[index] == CROWDED) left = true;
    }

    /** Whether a column has left a crowded place since the last {@link #placeAll}, which may now be looked up directly again. */
    boolean crowdedLeft() { return left; }

    /** Places every held column afresh. */
    @SuppressWarnings("unchecked")
    void placeAll(Iterable<T> columns) {
        // The ticks kept here go back to their columns first, to be taken in again as they are placed.
        for (int level = 0; level < places.length; level++) {
            Object[] at = places[level];
            if (at == null) continue;
            for (int index = 0; index < at.length; index++) {
                Object there = at[index];
                if (there != null && there != CROWDED) wantedTo.accept((T) there, wanted[level][index]);
            }
        }
        clear();
        for (T column : columns) add(column);
    }

    void clear() {
        for (Object[] at : places) if (at != null) Arrays.fill(at, null);
        for (long[] at : tags) if (at != null) Arrays.fill(at, 0);
        left = false;
    }
}
