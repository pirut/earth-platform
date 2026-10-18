package io.earthplatform.minecraft.cubic.client;

import java.util.Arrays;
import java.util.Objects;

/**
 * Opaque face ranges, independent of GL storage so a settled camera pick can keep its commands.
 * Preserves the arena's queue order, adjacent coalescing and indexed draw-size boundaries. Separate
 * instances are needed for each arena, fade and near/full pass; clearing retains their storage.
 */
final class FarDrawRanges {
    private final boolean indexed, splitRanges;
    private final int indexQuads;
    private int[] firsts = new int[32], counts = new int[32];
    private int size;

    FarDrawRanges(boolean indexed, boolean splitRanges, int indexQuads) {
        if (indexQuads <= 0) throw new IllegalArgumentException("Index quad limit must be positive");
        this.indexed = indexed;
        this.splitRanges = splitRanges;
        this.indexQuads = indexQuads;
    }

    /** Queues the same face ranges as {@link FarGpu}'s original arena queue. */
    void queue(int first, int count) {
        if (first < 0 || count < 0) throw new IllegalArgumentException("Face ranges must not be negative");
        if (!splitRanges && size > 0 && firsts[size - 1] + counts[size - 1] == first
                && (!indexed || counts[size - 1] + count <= indexQuads)) {
            counts[size - 1] += count;
            return;
        }
        // Keep the original operation order: after full indexed pieces, the remainder is appended
        // directly rather than offered for coalescing with the newly appended piece again.
        while (indexed && count > indexQuads) {
            queue(first, indexQuads);
            first += indexQuads;
            count -= indexQuads;
        }
        if (size == firsts.length) {
            firsts = Arrays.copyOf(firsts, size * 2);
            counts = Arrays.copyOf(counts, size * 2);
        }
        firsts[size] = first;
        counts[size++] = count;
    }

    void clear() { size = 0; }
    int size() { return size; }
    int first(int index) { return firsts[Objects.checkIndex(index, size)]; }
    int count(int index) { return counts[Objects.checkIndex(index, size)]; }
}
