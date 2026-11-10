package io.earthplatform.minecraft.cubic.client;

import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * The free space of one buffer shared by many meshes ({@link CubeRegions}): runs of free slots in order of place,
 * given out first fit and joined up again when handed back. Nothing ever moves, so nothing is copied on the GPU.
 */
final class CubeRanges {
    private final IntArrayList starts = new IntArrayList(), lengths = new IntArrayList();
    private final int capacity;
    private int used;

    CubeRanges(int capacity) {
        this.capacity = capacity;
        starts.add(0);
        lengths.add(capacity);
    }

    int capacity() { return capacity; }
    int used() { return used; }

    /** The first slot of a free run of {@code count}, now taken, or -1 when no run is that long. */
    int take(int count) {
        if (count <= 0) throw new IllegalArgumentException("count " + count);
        for (int i = 0; i < starts.size(); i++) {
            int length = lengths.getInt(i);
            if (length < count) continue;
            int start = starts.getInt(i);
            if (length == count) {
                starts.removeInt(i);
                lengths.removeInt(i);
            } else {
                starts.set(i, start + count);
                lengths.set(i, length - count);
            }
            used += count;
            return start;
        }
        return -1;
    }

    /** Hands back a run {@link #take} gave. */
    void give(int start, int count) {
        // The first free run after it.
        int low = 0, high = starts.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (starts.getInt(middle) < start) low = middle + 1; else high = middle;
        }
        int at = low;
        boolean before = at > 0 && starts.getInt(at - 1) + lengths.getInt(at - 1) == start;
        boolean after = at < starts.size() && starts.getInt(at) == start + count;
        if (at > 0 && starts.getInt(at - 1) + lengths.getInt(at - 1) > start || at < starts.size() && starts.getInt(at) < start + count) {
            throw new IllegalStateException("Run " + start + "+" + count + " handed back twice");
        }
        if (before && after) {
            lengths.set(at - 1, lengths.getInt(at - 1) + count + lengths.getInt(at));
            starts.removeInt(at);
            lengths.removeInt(at);
        } else if (before) {
            lengths.set(at - 1, lengths.getInt(at - 1) + count);
        } else if (after) {
            starts.set(at, start);
            lengths.set(at, lengths.getInt(at) + count);
        } else {
            starts.add(at, start);
            lengths.add(at, count);
        }
        used -= count;
    }

    /** How many separate free runs there are. */
    int runs() { return starts.size(); }
}
