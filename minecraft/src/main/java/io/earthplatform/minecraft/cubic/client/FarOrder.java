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

    void clear(int capacity) {
        if (distances.length < capacity) {
            distances = new double[capacity];
            indices = new int[capacity];
        }
        size = 0;
    }

    void add(double distance) {
        distances[size] = distance;
        indices[size] = size++;
    }

    void sort() { DoubleArrays.radixSortIndirect(indices, distances, 0, size, true); }

    int index(int i) { return indices[i]; }

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
