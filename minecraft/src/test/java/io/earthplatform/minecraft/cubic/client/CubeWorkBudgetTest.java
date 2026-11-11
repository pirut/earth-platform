package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CubeWorkBudgetTest {
    @Test void expensiveUploadsStopAfterTheFirstCompletedJobCrossesTheDeadline() {
        CubeWorkBudget budget = new CubeWorkBudget(100, 1_500_000, 8);
        assertTrue(budget.take(100));
        assertFalse(budget.take(2_000_100));
        assertFalse(budget.take(3_000_100));
    }

    @Test void cheapJobsCannotDrainAnUnlimitedBacklog() {
        CubeWorkBudget budget = new CubeWorkBudget(0, 1_500_000, 8);
        for (int i = 0; i < 8; i++) assertTrue(budget.take(i * 10));
        assertFalse(budget.take(80));
    }

    @Test void delayedFirstJobStillMakesProgressAndSnapshotsHaveTheirOwnBudget() {
        CubeWorkBudget budget = new CubeWorkBudget(0, 1_000_000, 8);
        assertTrue(budget.take(10_000_000));
        assertFalse(budget.take(10_000_001));
        CubeWorkBudget nextFrame = new CubeWorkBudget(20_000_000, 1_000_000, 8);
        assertTrue(nextFrame.take(20_000_000));
    }

    @Test void longFramesAndPausesDoNotExpandTheSnapshotQueue() {
        for (int workers = 1; workers <= 3; workers++) {
            for (long frame : new long[] {0, 4_000_000, 16_000_000, 100_000_000, 1_000_000_000, Long.MAX_VALUE}) {
                int count = CubeWorkBudget.inFlight(frame, workers);
                assertTrue(count >= workers * 2 && count <= workers * 8);
            }
            assertEquals(CubeWorkBudget.inFlight(50_000_000, workers), CubeWorkBudget.inFlight(1_000_000_000, workers));
        }
    }

    @Test void monotonicClockWrapDoesNotBreakTheDeadline() {
        long start = Long.MAX_VALUE - 100;
        CubeWorkBudget budget = new CubeWorkBudget(start, 1000, 8);
        assertTrue(budget.take(start));
        assertTrue(budget.take(start + 500));
        assertFalse(budget.take(start + 1000));
    }
}
