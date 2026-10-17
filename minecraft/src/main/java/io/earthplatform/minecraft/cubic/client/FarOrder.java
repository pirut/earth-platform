package io.earthplatform.minecraft.cubic.client;

import it.unimi.dsi.fastutil.doubles.DoubleArrays;

/** Stable primitive sorting keeps full distance precision and the old order of equal-distance water. */
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
}
