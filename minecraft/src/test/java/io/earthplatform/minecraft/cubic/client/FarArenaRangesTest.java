package io.earthplatform.minecraft.cubic.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FarArenaRangesTest {
    @Test void splitsExhaustsAndReusesExactRanges() {
        FarArenaRanges ranges = new FarArenaRanges(32);
        FarArenaRanges.Allocation first = ranges.allocate(9), second = ranges.allocate(23);
        assertEquals(0, first.start());
        assertEquals(9, first.size());
        assertEquals(9, second.start());
        assertEquals(0, ranges.freeUnits());
        assertEquals(0, ranges.freeRangeCount());
        assertNull(ranges.allocate(1));
        ranges.release(first);
        assertNull(ranges.allocate(10));
        assertEquals(0, ranges.allocate(9).start());
        assertEquals(32, ranges.allocatedUnits());
        assertEquals(2, ranges.allocationCount());
    }

    @Test void choosesSmallestSufficientRangeThenLowestAddress() {
        for (boolean indexed : new boolean[] {true, false}) {
            FarArenaRanges ranges = new FarArenaRanges(100, indexed);
            FarArenaRanges.Allocation first = ranges.allocate(20);
            ranges.allocate(10);
            FarArenaRanges.Allocation second = ranges.allocate(10);
            ranges.allocate(10);
            FarArenaRanges.Allocation third = ranges.allocate(10);
            ranges.allocate(40);
            ranges.release(first);
            ranges.release(second);
            ranges.release(third);
            // First-fit takes the earlier 20-unit hole; best-fit takes the first 10-unit hole.
            assertEquals(indexed ? 30 : 0, ranges.allocate(9).start());
            // Best-fit breaks equal-size ties by address, then splits before larger holes.
            if (indexed) {
                assertEquals(50, ranges.allocate(9).start());
                assertEquals(0, ranges.allocate(19).start());
                assertEquals(3, ranges.freeUnits()); // Three one-unit remainders.
                assertNull(ranges.allocate(2));
            }
        }
    }

    @Test void coalescesBothNeighborsInEitherReleaseOrder() {
        for (boolean indexed : new boolean[] {true, false}) {
            for (boolean reverse : new boolean[] {true, false}) {
                FarArenaRanges ranges = new FarArenaRanges(40, indexed);
                FarArenaRanges.Allocation a = ranges.allocate(10), b = ranges.allocate(10);
                FarArenaRanges.Allocation c = ranges.allocate(10), d = ranges.allocate(10);
                ranges.release(reverse ? c : a);
                ranges.release(reverse ? a : c);
                ranges.release(b);
                assertEquals(1, ranges.freeRangeCount());
                FarArenaRanges.Allocation joined = ranges.allocate(30);
                assertEquals(0, joined.start());
                ranges.release(d);
                ranges.release(joined);
                assertEquals(1, ranges.freeRangeCount());
                assertEquals(40, ranges.freeUnits());
                assertEquals(0, ranges.allocate(40).start());
            }
        }
    }

    @Test void equalSizedHolesRemainDistinctThroughSplitsAndCoalescing() {
        for (boolean indexed : new boolean[] {true, false}) {
            FarArenaRanges ranges = new FarArenaRanges(1024, indexed);
            List<FarArenaRanges.Allocation> original = new ArrayList<>(), replacements = new ArrayList<>();
            for (int i = 0; i < 128; i++) original.add(ranges.allocate(8));
            // Sixty-four equal-sized free ranges must all remain in the size/address indexes.
            for (int i = 0; i < original.size(); i += 2) ranges.release(original.get(i));
            assertEquals(64, ranges.freeRangeCount());
            for (int i = 0; i < 64; i++) {
                FarArenaRanges.Allocation left = ranges.allocate(4), right = ranges.allocate(4);
                assertEquals(i * 16, left.start());
                assertEquals(i * 16 + 4, right.start());
                replacements.add(left);
                replacements.add(right);
            }
            assertEquals(0, ranges.freeRangeCount());
            assertNull(ranges.allocate(1));
            // Releasing anchors first forces every replacement to coalesce against new neighbors.
            for (int i = 1; i < original.size(); i += 2) ranges.release(original.get(i));
            for (int i = replacements.size() - 1; i >= 0; i--) ranges.release(replacements.get(i));
            assertEquals(0, ranges.allocationCount());
            assertEquals(1, ranges.freeRangeCount());
            assertEquals(1024, ranges.freeUnits());
            assertEquals(0, ranges.allocate(1024).start());
        }
    }

    @Test void rejectsInvalidSizesWithoutChangingState() {
        assertThrows(IllegalArgumentException.class, () -> new FarArenaRanges(0));
        assertThrows(IllegalArgumentException.class, () -> new FarArenaRanges(-1));
        FarArenaRanges ranges = new FarArenaRanges(40);
        assertThrows(IllegalArgumentException.class, () -> ranges.allocate(0));
        assertThrows(IllegalArgumentException.class, () -> ranges.allocate(-1));
        assertNull(ranges.allocate(Integer.MAX_VALUE));
        assertEquals(40, ranges.freeUnits());
        assertEquals(0, ranges.allocationCount());
        assertEquals(0, ranges.allocate(40).start());
    }

    @Test void rejectsDuplicateForeignAndStaleTokensBeforeMutation() {
        for (boolean indexed : new boolean[] {true, false}) {
            FarArenaRanges ranges = new FarArenaRanges(40, indexed);
            FarArenaRanges.Allocation old = ranges.allocate(20);
            FarArenaRanges other = new FarArenaRanges(40, indexed);
            FarArenaRanges.Allocation foreign = other.allocate(20);
            assertThrows(NullPointerException.class, () -> ranges.release(null));
            assertThrows(IllegalArgumentException.class, () -> ranges.release(foreign));
            assertEquals(20, ranges.freeUnits());
            assertEquals(1, ranges.allocationCount());
            ranges.release(old);
            assertThrows(IllegalArgumentException.class, () -> ranges.release(old));
            FarArenaRanges.Allocation replacement = ranges.allocate(20);
            assertEquals(old.start(), replacement.start());
            assertEquals(old.size(), replacement.size());
            assertNotSame(old, replacement);
            assertThrows(IllegalArgumentException.class, () -> ranges.release(old));
            assertEquals(20, ranges.allocatedUnits());
            assertEquals(1, ranges.allocationCount());
            assertEquals(1, ranges.freeRangeCount());
            ranges.release(replacement);
            assertEquals(0, ranges.allocate(40).start());
            // A foreign arena also retains its ownership after the failed release.
            other.release(foreign);
            assertEquals(40, other.freeUnits());
        }
    }

    @Test void handlesCapacityAtIntegerLimitWithoutOverflow() {
        FarArenaRanges ranges = new FarArenaRanges(Integer.MAX_VALUE);
        FarArenaRanges.Allocation large = ranges.allocate(Integer.MAX_VALUE - 8), end = ranges.allocate(8);
        assertEquals(Integer.MAX_VALUE - 8, end.start());
        assertEquals(Integer.MAX_VALUE, ranges.allocatedUnits());
        assertNull(ranges.allocate(1));
        ranges.release(large);
        ranges.release(end);
        assertEquals(Integer.MAX_VALUE, ranges.freeUnits());
        assertEquals(1, ranges.freeRangeCount());
        assertEquals(Integer.MAX_VALUE, ranges.allocate(Integer.MAX_VALUE).size());
    }

    @Test void randomizedAllocationAndReleaseMatchesIndependentOccupancyModel() {
        for (boolean indexed : new boolean[] {true, false}) {
            for (int seed = 0; seed < 4; seed++) {
                FarArenaRanges ranges = new FarArenaRanges(512, indexed);
                boolean[] used = new boolean[512];
                List<FarArenaRanges.Allocation> live = new ArrayList<>();
                Random random = new Random(seed);
                for (int step = 0; step < 20_000; step++) {
                    if (!live.isEmpty() && random.nextInt(100) < 48) {
                        FarArenaRanges.Allocation allocation = live.remove(random.nextInt(live.size()));
                        ranges.release(allocation);
                        for (int at = allocation.start(); at < allocation.start() + allocation.size(); at++) {
                            assertTrue(used[at]);
                            used[at] = false;
                        }
                    } else {
                        int size = random.nextInt(64) + 1, expected = fit(used, size, indexed);
                        FarArenaRanges.Allocation allocation = ranges.allocate(size);
                        if (expected < 0) assertNull(allocation);
                        else {
                            assertNotNull(allocation);
                            assertEquals(expected, allocation.start(), "seed " + seed + ", step " + step);
                            assertEquals(size, allocation.size());
                            for (int at = allocation.start(); at < allocation.start() + size; at++) {
                                assertFalse(used[at]);
                                used[at] = true;
                            }
                            live.add(allocation);
                        }
                    }
                    int occupied = 0, freeRanges = 0;
                    for (int at = 0; at < used.length; at++) {
                        if (used[at]) occupied++;
                        else if (at == 0 || used[at - 1]) freeRanges++;
                    }
                    assertEquals(occupied, ranges.allocatedUnits());
                    assertEquals(used.length - occupied, ranges.freeUnits());
                    assertEquals(freeRanges, ranges.freeRangeCount());
                    assertEquals(live.size(), ranges.allocationCount());
                }
                for (FarArenaRanges.Allocation allocation : live) ranges.release(allocation);
                assertEquals(512, ranges.freeUnits());
                assertEquals(1, ranges.freeRangeCount());
                assertEquals(0, ranges.allocate(512).start());
            }
        }
    }

    /** Independent byte-by-byte oracle: address first-fit or smallest whole hole, ties by address. */
    private static int fit(boolean[] used, int size, boolean indexed) {
        int bestStart = -1, bestSize = Integer.MAX_VALUE;
        for (int at = 0; at < used.length;) {
            if (used[at]) { at++; continue; }
            int start = at;
            while (at < used.length && !used[at]) at++;
            int available = at - start;
            if (available >= size && available < bestSize) {
                bestStart = start;
                bestSize = available;
                if (!indexed) return bestStart;
            }
        }
        return bestStart;
    }
}
