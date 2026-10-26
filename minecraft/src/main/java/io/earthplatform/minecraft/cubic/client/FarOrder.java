package io.earthplatform.minecraft.cubic.client;

import it.unimi.dsi.fastutil.doubles.DoubleArrays;
import it.unimi.dsi.fastutil.ints.IntComparator;
import it.unimi.dsi.fastutil.ints.IntHeapPriorityQueue;

/**
 * Stable primitive sorting keeps full distance precision and the old order of equal-distance water.
 * {@link #first} picks the first few of a long list without ordering all of it.
 */
final class FarOrder {
    private double[] distances = new double[0];
    private int[] indices = new int[0];
    private int size;
    /** The element each value is for (see {@link #sortFrom}), and the elements as the last such sort left them, under its epoch. */
    private int[] elements = new int[0], last = new int[0];
    private int lastSize, lastEpoch = -1;
    /** By element: where its value is in this sort (or -1 once placed again), valid where {@link #marks} holds {@link #mark}. */
    private int[] places = new int[0], marks = new int[0];
    private int mark;
    /** The values the last sort had too, and the rest: places in the order added. */
    private int[] kept = new int[0], fresh = new int[0];

    void clear(int capacity) {
        if (distances.length < capacity) {
            distances = new double[capacity];
            indices = new int[capacity];
            elements = new int[capacity];
        }
        size = 0;
    }

    void add(double distance) {
        distances[size] = distance;
        indices[size] = size++;
    }

    /** Adds a value for an element (see {@link #sortFrom}), each element once. */
    void add(double distance, int element) {
        elements[size] = element;
        add(distance);
    }

    void sort() { DoubleArrays.radixSortIndirect(indices, distances, 0, size, true); }

    /**
     * Sorts as {@link #sort} does, by value and among equal ones in the order added, starting from
     * the order the last sort under the same {@code epoch} left the same elements in (elements are
     * numbered below {@code elementCount}, and keep their numbers within an epoch). Those come back
     * in that order and are set right by insertion; the rest are sorted on their own and merged in.
     * The order asked for has no ties, so the result is the one {@link #sort} gives; between frames
     * of a moving camera the values barely change order, and this does a fraction of a whole sort's
     * work. It does a whole sort when the epoch changes, or when too much has moved.
     */
    void sortFrom(int epoch, int elementCount) {
        if (epoch != lastEpoch || !reorder(elementCount)) sort();
        if (last.length < size) last = new int[Math.max(size, last.length * 2)];
        for (int i = 0; i < size; i++) last[i] = elements[indices[i]];
        lastSize = size;
        lastEpoch = epoch;
    }

    /** {@link #sortFrom} from the last order, or false (leaving the order unfinished) when a whole sort would be quicker. */
    private boolean reorder(int elementCount) {
        if (places.length < elementCount) {
            places = new int[Math.max(elementCount, places.length * 2)];
            marks = new int[places.length];
            mark = 0;
        }
        if (kept.length < size) {
            kept = new int[Math.max(size, kept.length * 2)];
            fresh = new int[kept.length];
        }
        if (++mark == 0) {
            java.util.Arrays.fill(marks, 0);
            mark = 1;
        }
        for (int i = 0; i < size; i++) {
            places[elements[i]] = i;
            marks[elements[i]] = mark;
        }
        // The values the last sort had too, in its order.
        int keptCount = 0;
        for (int j = 0; j < lastSize; j++) {
            int element = last[j];
            if (element >= elementCount || marks[element] != mark || places[element] < 0) continue;
            kept[keptCount++] = places[element];
            places[element] = -1;
        }
        // Set right by insertion, giving up past a few moves per value.
        long moves = 0, budget = 4L * size + 64;
        for (int i = 1; i < keptCount; i++) {
            int at = kept[i], j = i - 1;
            while (j >= 0 && before(at, kept[j])) {
                kept[j + 1] = kept[j];
                j--;
                if (++moves > budget) return false;
            }
            kept[j + 1] = at;
        }
        // The rest, which came into view since, sorted on their own (in the order added among equals).
        int freshCount = 0;
        for (int i = 0; i < size; i++) if (places[elements[i]] >= 0) fresh[freshCount++] = i;
        if (freshCount > 1) DoubleArrays.radixSortIndirect(fresh, distances, 0, freshCount, true);
        // Merged.
        for (int i = 0, a = 0, b = 0; i < size; i++) {
            indices[i] = b >= freshCount || a < keptCount && before(kept[a], fresh[b]) ? kept[a++] : fresh[b++];
        }
        return true;
    }

    /** Whether the value added {@code a}th comes before the one added {@code b}th: by value, then the order added. */
    private boolean before(int a, int b) {
        int byValue = Double.compare(distances[a], distances[b]);
        return byValue < 0 || byValue == 0 && a < b;
    }

    int index(int i) { return indices[i]; }

    /** The value added {@code i}th. */
    double value(int i) { return distances[i]; }

    /** The indices of the {@code wanted} smallest values, smallest first, in their list order among equals. */
    static int[] first(long[] values, int wanted) {
        int count = values.length, kept = Math.min(wanted, count);
        if (kept <= 0) return new int[0];
        // A max-heap of the smallest seen so far; a later equal value never displaces an earlier one.
        IntComparator byValue = (a, b) -> values[a] != values[b] ? Long.compare(values[a], values[b]) : Integer.compare(a, b);
        IntHeapPriorityQueue heap = new IntHeapPriorityQueue(kept, byValue.reversed());
        for (int i = 0; i < count; i++) {
            if (heap.size() < kept) heap.enqueue(i);
            else if (byValue.compare(i, heap.firstInt()) < 0) {
                heap.dequeueInt();
                heap.enqueue(i);
            }
        }
        int[] first = new int[heap.size()];
        for (int i = first.length - 1; i >= 0; i--) first[i] = heap.dequeueInt();
        return first;
    }
}
