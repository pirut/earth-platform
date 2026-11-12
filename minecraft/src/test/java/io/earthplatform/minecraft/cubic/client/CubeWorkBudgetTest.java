package io.earthplatform.minecraft.cubic.client;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CubeWorkBudgetTest {
    private static final long FRAME_60 = 16_666_667L;

    @Test void expensiveJobsStopOnceTheFirstHasCrossedTheDeadline() {
        CubeWorkBudget budget = new CubeWorkBudget(100, 2_000_000, Integer.MAX_VALUE);
        assertTrue(budget.take(100));
        assertFalse(budget.take(2_000_100));
        assertFalse(budget.take(3_000_100));
    }

    @Test void cheapJobsGoOnUntilTheDeadlineOrTheMaximum() {
        CubeWorkBudget uploads = new CubeWorkBudget(0, 2_000_000, Integer.MAX_VALUE);
        for (int i = 0; i < 1000; i++) assertTrue(uploads.take(i * 1000L));
        assertFalse(uploads.take(2_000_000));
        CubeWorkBudget snapshots = new CubeWorkBudget(0, 1_000_000, 8);
        for (int i = 0; i < 8; i++) assertTrue(snapshots.take(i));
        assertFalse(snapshots.take(8));
    }

    @Test void aLateFirstJobStillStarts() {
        CubeWorkBudget budget = new CubeWorkBudget(0, 1_000_000, 8);
        assertTrue(budget.take(10_000_000));
        assertFalse(budget.take(10_000_001));
    }

    @Test void theClockWrappingRoundDoesNotBreakTheDeadline() {
        long start = Long.MAX_VALUE - 100;
        CubeWorkBudget budget = new CubeWorkBudget(start, 1000, 8);
        assertTrue(budget.take(start));
        assertTrue(budget.take(start + 500));
        assertFalse(budget.take(start + 1000));
    }

    @Test void oneLongFrameIsNotASlowFrameRate() {
        assertEquals(FRAME_60, CubeWorkBudget.pace(1_000_000_000L, FRAME_60));
        assertEquals(FRAME_60, CubeWorkBudget.pace(FRAME_60, 1_000_000_000L));
        assertEquals(1_000_000_000L, CubeWorkBudget.pace(1_000_000_000L, 1_000_000_000L));
        for (int workers = 1; workers <= 3; workers++) {
            long pace = CubeWorkBudget.pace(1_000_000_000L, FRAME_60);
            assertEquals(CubeWorkBudget.MIN_IN_FLIGHT, CubeWorkBudget.inFlight(pace, workers));
        }
        long stall = CubeWorkBudget.pace(1_000_000_000L, FRAME_60);
        assertTrue(CubeWorkBudget.uploadNanos(stall) <= 2_100_000L);
        assertTrue(CubeWorkBudget.snapshotNanos(stall) <= 1_100_000L);
    }

    @Test void aSteadySlowFrameRateStillKeepsTheWorkersBusy() {
        // At one frame a second three workers mesh 600 cubes between frames; 48 in flight built 48 a second.
        assertEquals(CubeWorkBudget.MAX_IN_FLIGHT, CubeWorkBudget.inFlight(1_000_000_000L, 3));
        assertEquals(200, CubeWorkBudget.inFlight(1_000_000_000L, 1));
        assertEquals(CubeWorkBudget.MIN_IN_FLIGHT, CubeWorkBudget.inFlight(0, 3));
        assertEquals(125_000_000L, CubeWorkBudget.uploadNanos(1_000_000_000L));
    }
}
