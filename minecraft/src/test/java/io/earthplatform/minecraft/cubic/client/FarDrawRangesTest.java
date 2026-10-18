package io.earthplatform.minecraft.cubic.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FarDrawRangesTest {
    /** Original FarGpu.Arena.queue algorithm, with lists in place of its native buffers. */
    private static final class Reference {
        final boolean indexed, split;
        final int limit;
        final List<Integer> firsts = new ArrayList<>(), counts = new ArrayList<>();

        Reference(boolean indexed, boolean split, int limit) {
            this.indexed = indexed;
            this.split = split;
            this.limit = limit;
        }

        void queue(int first, int count) {
            int draws = firsts.size();
            if (!split && draws > 0 && firsts.get(draws - 1) + counts.get(draws - 1) == first
                    && (!indexed || counts.get(draws - 1) + count <= limit)) {
                counts.set(draws - 1, counts.get(draws - 1) + count);
                return;
            }
            while (indexed && count > limit) {
                queue(first, limit);
                first += limit;
                count -= limit;
            }
            firsts.add(first);
            counts.add(count);
        }

        void clear() { firsts.clear(); counts.clear(); }
    }

    private static void matches(Reference expected, FarDrawRanges actual) {
        assertEquals(expected.firsts.size(), actual.size());
        for (int i = 0; i < actual.size(); i++) {
            assertEquals(expected.firsts.get(i).intValue(), actual.first(i), "first of range " + i);
            assertEquals(expected.counts.get(i).intValue(), actual.count(i), "count of range " + i);
        }
    }

    @Test void adjacentFacesCoalesceWithoutJoiningGapsOrReordering() {
        FarDrawRanges ranges = new FarDrawRanges(false, false, 8);
        ranges.queue(2, 3);
        ranges.queue(5, 4);
        ranges.queue(10, 2);
        ranges.queue(1, 1);
        assertEquals(3, ranges.size());
        assertEquals(2, ranges.first(0));
        assertEquals(7, ranges.count(0));
        assertEquals(10, ranges.first(1));
        assertEquals(2, ranges.count(1));
        assertEquals(1, ranges.first(2));
    }

    @Test void indexedRangesRespectTheExactLimitAndSplitLongRanges() {
        FarDrawRanges ranges = new FarDrawRanges(true, false, 8);
        ranges.queue(0, 3);
        ranges.queue(3, 5);
        ranges.queue(8, 17);
        assertEquals(4, ranges.size());
        assertEquals(List.of(0, 8, 16, 24), List.of(ranges.first(0), ranges.first(1), ranges.first(2), ranges.first(3)));
        assertEquals(List.of(8, 8, 8, 1), List.of(ranges.count(0), ranges.count(1), ranges.count(2), ranges.count(3)));
    }

    @Test void splitFlagDisablesCoalescingButRetainsIndexedSplitting() {
        FarDrawRanges ranges = new FarDrawRanges(true, true, 8);
        ranges.queue(0, 3);
        ranges.queue(3, 5);
        ranges.queue(8, 9);
        assertEquals(4, ranges.size());
        assertEquals(List.of(3, 5, 8, 1), List.of(ranges.count(0), ranges.count(1), ranges.count(2), ranges.count(3)));

        FarDrawRanges arrays = new FarDrawRanges(false, true, 8);
        arrays.queue(0, 20);
        arrays.queue(20, 2);
        assertEquals(2, arrays.size());
        assertEquals(20, arrays.count(0));
    }

    @Test void zeroRangesMatchTheReferenceAndClearDoesNotExposeRetainedEntries() {
        FarDrawRanges ranges = new FarDrawRanges(true, false, 8);
        Reference reference = new Reference(true, false, 8);
        for (int[] range : new int[][] {{4, 0}, {4, 2}, {8, 0}, {8, 0}}) {
            ranges.queue(range[0], range[1]);
            reference.queue(range[0], range[1]);
        }
        matches(reference, ranges);
        ranges.clear();
        assertEquals(0, ranges.size());
        assertThrows(IndexOutOfBoundsException.class, () -> ranges.first(0));
        assertThrows(IndexOutOfBoundsException.class, () -> ranges.count(0));
        ranges.queue(100, 2);
        assertEquals(1, ranges.size());
        assertEquals(100, ranges.first(0));
        assertEquals(2, ranges.count(0));
    }

    @Test void randomizedQueuesMatchEveryReferenceRangeAcrossGrowthAndReuse() {
        Random random = new Random(20261018L);
        for (boolean indexed : new boolean[] {false, true}) {
            for (boolean split : new boolean[] {false, true}) {
                for (int limit : new int[] {1, 2, 7, 65_536}) {
                    FarDrawRanges ranges = new FarDrawRanges(indexed, split, limit);
                    Reference reference = new Reference(indexed, split, limit);
                    int previousEnd = 0;
                    for (int step = 0; step < 1_000; step++) {
                        if (step % 137 == 0) {
                            matches(reference, ranges);
                            ranges.clear();
                            reference.clear();
                        }
                        int first = random.nextBoolean() ? previousEnd : random.nextInt(1_000_000);
                        int count = random.nextInt(limit * 3 + 2);
                        ranges.queue(first, count);
                        reference.queue(first, count);
                        previousEnd = first + count;
                        if (step % 37 == 0) matches(reference, ranges);
                    }
                    matches(reference, ranges);
                }
            }
        }
    }

    @Test void rejectsInvalidRangesAndIndicesWithoutChangingTheQueue() {
        assertThrows(IllegalArgumentException.class, () -> new FarDrawRanges(true, false, 0));
        FarDrawRanges ranges = new FarDrawRanges(true, false, 8);
        ranges.queue(0, 1);
        assertThrows(IllegalArgumentException.class, () -> ranges.queue(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> ranges.queue(0, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> ranges.first(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> ranges.count(1));
        assertEquals(1, ranges.size());
        assertEquals(1, ranges.count(0));
    }
}
