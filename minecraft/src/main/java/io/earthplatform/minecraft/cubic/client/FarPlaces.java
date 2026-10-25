package io.earthplatform.minecraft.cubic.client;

import io.earthplatform.minecraft.cubic.far.Far;
import java.util.Arrays;
import java.util.function.LongFunction;
import java.util.function.Predicate;
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
 * level somewhere (see {@link #meshed}) is answered from these tables alone, never reaching the
 * columns themselves, spread over the heap. Render thread only.
 */
final class FarPlaces<T> {
    private static final int BITS = 8, MASK = (1 << BITS) - 1;
    private static final Object CROWDED = new Object();
    /** A tag: the coordinates above the place's bits (14 each), whether a column is held there and meshed, or that the place is crowded. */
    private static final int WHERE = (1 << 28) - 1, MESHED = 1 << 28, HELD = 1 << 29, CROWDED_TAG = 1 << 30;

    private final Object[][] places = new Object[Far.MAX_LEVEL + 1][];
    private final int[][] tags = new int[Far.MAX_LEVEL + 1][];
    private final ToLongFunction<T> keyOf;
    private final LongFunction<T> map;
    private final Predicate<T> meshedOf;
    /** Whether a column left a crowded place, which may have room again (see {@link #crowdedLeft}). */
    private boolean left;

    /**
     * Places for columns whose keys {@code keyOf} gives, looking crowded places up in {@code map},
     * and whose meshes are up when {@code meshedOf} says so.
     */
    FarPlaces(ToLongFunction<T> keyOf, LongFunction<T> map, Predicate<T> meshedOf) {
        this.keyOf = keyOf;
        this.map = map;
        this.meshedOf = meshedOf;
    }

    private static int place(int x, int z) { return (x & MASK) << BITS | z & MASK; }

    /** A held column's tag at a level's column x, z, without whether it is meshed. */
    private static int where(int x, int z) { return HELD | (x >> BITS & 0x3FFF) | (z >> BITS & 0x3FFF) << 14; }

    /** Whether a meshed column is held at a level's column x, z. */
    boolean meshed(int level, int x, int z) {
        int[] at = tags[level];
        if (at == null) return false;
        int tag = at[place(x, z)];
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

    /** The held column at a level's column x, z, or null. */
    @SuppressWarnings("unchecked")
    @Nullable T get(int level, int x, int z) {
        Object[] at = places[level];
        if (at == null) return null;
        Object there = at[place(x, z)];
        if (there == null) return null;
        long key = Far.key(level, x, z);
        if (there == CROWDED) return map.apply(key);
        return keyOf.applyAsLong((T) there) == key ? (T) there : null;
    }

    @Nullable T get(long key) { return get(Far.level(key), Far.x(key), Far.z(key)); }

    /** Takes a column just put in the map, in place of the one it replaces, if any. */
    @SuppressWarnings("unchecked")
    void add(T column) {
        long key = keyOf.applyAsLong(column);
        int level = Far.level(key);
        Object[] at = places[level];
        if (at == null) {
            at = places[level] = new Object[1 << 2 * BITS];
            tags[level] = new int[1 << 2 * BITS];
        }
        int x = Far.x(key), z = Far.z(key), index = place(x, z);
        Object there = at[index];
        if (there == null || there != CROWDED && keyOf.applyAsLong((T) there) == key) {
            at[index] = column;
            tags[level][index] = where(x, z) | (meshedOf.test(column) ? MESHED : 0);
        } else {
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
    void placeAll(Iterable<T> columns) {
        clear();
        for (T column : columns) add(column);
    }

    void clear() {
        for (Object[] at : places) if (at != null) Arrays.fill(at, null);
        for (int[] at : tags) if (at != null) Arrays.fill(at, 0);
        left = false;
    }
}
