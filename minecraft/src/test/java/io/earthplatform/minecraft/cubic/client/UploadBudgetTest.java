package io.earthplatform.minecraft.cubic.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UploadBudgetTest {
    @Test void stopsAtDeadlineWithoutAMinimumItemCount() {
        UploadBudget budget = new UploadBudget(10, 3, 100);
        assertTrue(budget.tryAcquire(10, 10));
        assertTrue(budget.tryAcquire(12, 10));
        assertFalse(budget.tryAcquire(13, 10));
        assertEquals(2, budget.uploads());
        assertEquals(20, budget.bytes());
    }

    @Test void includesExactByteLimitButDoesNotConsumeRejectedItems() {
        UploadBudget budget = new UploadBudget(0, 100, 100);
        assertTrue(budget.tryAcquire(0, 40));
        assertFalse(budget.tryAcquire(1, 61));
        assertEquals(1, budget.uploads());
        assertEquals(40, budget.bytes());
        assertTrue(budget.tryAcquire(2, 60));
        assertFalse(budget.tryAcquire(3, 1));
        assertEquals(100, budget.bytes());
    }

    @Test void allowsFirstItemEvenIfAlreadyLateOrOversized() {
        UploadBudget budget = new UploadBudget(0, 3, 100);
        assertTrue(budget.tryAcquire(20, 101));
        assertFalse(budget.tryAcquire(20, 0));
        assertEquals(1, budget.uploads());
        assertEquals(101, budget.bytes());
    }

    @Test void anIndivisibleUploadCanOverrunButStopsTheNextStart() {
        UploadBudget budget = new UploadBudget(0, 3, 100);
        assertTrue(budget.tryAcquire(0, 10));
        // The caller returns from a slow driver call after the deadline; work is not preempted.
        assertFalse(budget.tryAcquire(12, 10));
        assertEquals(1, budget.uploads());
    }

    @Test void supportsNanoTimeWrapAndStopsOnAnUnexpectedClockReversal() {
        UploadBudget wrap = new UploadBudget(Long.MAX_VALUE - 1, 4, 100);
        assertTrue(wrap.tryAcquire(Long.MAX_VALUE - 1, 1));
        assertTrue(wrap.tryAcquire(Long.MIN_VALUE, 1)); // two nanoseconds elapsed
        assertFalse(wrap.tryAcquire(Long.MIN_VALUE + 2, 1)); // four nanoseconds elapsed

        UploadBudget reversed = new UploadBudget(10, 4, 100);
        assertTrue(reversed.tryAcquire(9, 1)); // at least one item still progresses
        assertFalse(reversed.tryAcquire(9, 1));
    }

    @Test void byteAccountingCannotOverflowAndInputsAreCheckedBeforeMutation() {
        UploadBudget budget = new UploadBudget(0, 10, Long.MAX_VALUE);
        assertTrue(budget.tryAcquire(0, Long.MAX_VALUE));
        assertFalse(budget.tryAcquire(1, 1));
        assertThrows(IllegalArgumentException.class, () -> budget.tryAcquire(1, -1));
        assertEquals(1, budget.uploads());
        assertEquals(Long.MAX_VALUE, budget.bytes());
        assertThrows(IllegalArgumentException.class, () -> new UploadBudget(0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new UploadBudget(0, 1, 0));
    }

    @Test void fifoWorkloadMakesProgressWithoutStarvingAnOversizedHead() {
        var queue = new ArrayDeque<>(List.of(40L, 40L, 200L, 40L));
        List<List<Long>> frames = new ArrayList<>();
        while (!queue.isEmpty()) {
            UploadBudget budget = new UploadBudget(0, 3, 100);
            List<Long> frame = new ArrayList<>();
            long now = 0;
            while (!queue.isEmpty() && budget.tryAcquire(now, queue.peek())) {
                frame.add(queue.remove());
                now++; // deterministic one-unit uploads
            }
            assertFalse(frame.isEmpty());
            frames.add(frame);
            assertTrue(frames.size() <= 4);
        }
        assertEquals(List.of(List.of(40L, 40L), List.of(200L), List.of(40L)), frames);
    }

    @Test void stricterTimePolicyTradesConvergenceForSmallerUploadSlices() {
        // Model eight equal, non-preemptible uploads of two units each. The old minimum-eight
        // policy starts all eight despite its three-unit deadline. Strict starts two per frame.
        int remaining = 8, frames = 0, largestSlice = 0;
        while (remaining > 0) {
            UploadBudget budget = new UploadBudget(0, 3, 100);
            int now = 0;
            while (remaining > 0 && budget.tryAcquire(now, 1)) {
                remaining--;
                now += 2;
            }
            largestSlice = Math.max(largestSlice, now);
            frames++;
        }
        int baselineSlice = 0;
        for (int count = 0; count < 8 || baselineSlice < 3; count++) baselineSlice += 2;
        assertEquals(4, frames);
        assertEquals(4, largestSlice); // deadline plus the last indivisible upload
        assertEquals(16, baselineSlice);
        assertTrue(largestSlice < baselineSlice);
    }
}
