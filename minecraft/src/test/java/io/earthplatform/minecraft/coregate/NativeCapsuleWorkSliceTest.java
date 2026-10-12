package io.earthplatform.minecraft.coregate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Pure scheduling tests. Actual model comparison and capture policies are unchanged. */
class NativeCapsuleWorkSliceTest {
    @Test void allExhaustiveCasesRemainOrderedAndNoneAreSkippedOrRepeated() {
        var f = new Fixture(NativeCapsuleWorkSlice.MAX_CASES, 672, 3_000_000);
        f.nanosPerCase = 1000;
        var visited = new ArrayList<Integer>();
        f.visited = visited;
        int frames = 0;
        while (f.completed < f.total) {
            var result = f.pump(++frames);
            assertTrue(result.steps() <= NativeCapsuleWorkSlice.MAX_STEPS);
            assertTrue(result.callbacks() <= NativeCapsuleWorkSlice.MAX_CASES);
            assertTrue(result.elapsedNanos() <= result.budgetNanos());
        }
        assertEquals(29, frames, "Measured-time packing uses the 3ms instead of 128 cases per frame");
        assertEquals(86_016, visited.size());
        for (int i = 0; i < visited.size(); i++) assertEquals(i, visited.get(i));
        assertEquals(86_016, f.owner.metrics().callbacks());
        assertTrue(f.limits.stream().allMatch(limit -> limit == 128));
    }

    @Test void cheapStepsPackAndPartialFinalStepRetainsExactCompletion() {
        var f = new Fixture(258, 672, 1000);
        var result = f.pump(1);
        assertEquals(3, result.steps());
        assertEquals(258, result.callbacks());
        assertEquals(List.of(128, 128, 128), f.limits);
        assertEquals(List.of(1000L, 872L, 744L), f.budgets);
        assertEquals(258, result.elapsedNanos());
        assertEquals(NativeCapsuleWorkSlice.StopReason.COMPLETE, result.stopReason());
        assertTrue(result.complete());
    }

    @Test void exactBudgetIsSharedAndNoFollowingStepReceivesAFreshAllowance() {
        var f = new Fixture(1000, 672, 150);
        var result = f.pump(1);
        assertEquals(List.of(150L, 22L), f.budgets);
        assertEquals(2, result.steps());
        assertEquals(150, result.callbacks());
        assertEquals(150, result.elapsedNanos());
        assertEquals(NativeCapsuleWorkSlice.StopReason.TIME_BUDGET, result.stopReason());
        assertEquals(1, f.owner.metrics().timeBudgetYields());
        assertFalse(result.complete());
    }

    @Test void indivisibleCallbackOvershootIsMeasuredAndStartsNoFollowingStep() {
        var f = new Fixture(400, 672, 10);
        f.nanosPerCase = 27;
        var result = f.pump(1);
        assertEquals(1, result.steps());
        assertEquals(1, result.callbacks());
        assertEquals(27, result.elapsedNanos());
        assertEquals(17, result.overshootNanos());
        assertEquals(17, f.owner.metrics().maxOvershootNanos());
        assertEquals(NativeCapsuleWorkSlice.StopReason.TIME_BUDGET, result.stopReason());
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 96, 672})
    void stoppedClockRemainsBoundedByFiniteDerivedStepLimit(int steps) {
        var f = new Fixture(86_016, steps, 10);
        f.nanosPerCase = 0;
        f.perStepLimit = 1;
        var result = f.pump(1);
        assertEquals(steps, result.steps());
        assertEquals(steps, result.callbacks());
        assertEquals(0, result.elapsedNanos());
        assertEquals(NativeCapsuleWorkSlice.StopReason.STEP_LIMIT, result.stopReason());
        assertEquals(1, f.owner.metrics().stepLimitYields());
    }

    @Test void duplicateFrameCannotSpendAnotherAllowanceAndLaterFrameCanContinue() {
        var f = new Fixture(1000, 1, 1000);
        assertEquals(128, f.pump(91).callbacks());
        var duplicate = f.pump(91);
        assertEquals(NativeCapsuleWorkSlice.StopReason.DUPLICATE_FRAME, duplicate.stopReason());
        assertEquals(0, duplicate.steps());
        assertEquals(0, duplicate.callbacks());
        assertEquals(128, duplicate.completed());
        assertEquals(1, f.limits.size());
        assertEquals(1, f.owner.metrics().duplicateFrames());
        assertThrows(IllegalArgumentException.class, () -> f.pump(90));
        assertEquals(128, f.pump(92).callbacks());
    }

    @Test void invalidationAfterStepPreventsAnotherBatchAndCannotBeResurrected() {
        var f = new Fixture(1000, 672, 1000);
        f.afterStep = () -> f.current = false;
        assertThrows(IllegalStateException.class, () -> f.pump(1));
        assertEquals(128, f.completed, "Actual work happened before its fence was revoked");
        assertEquals(1, f.limits.size());
        assertEquals(0, f.owner.metrics().callbacks(), "Revoked results are not accepted progress");
        f.current = true;
        assertThrows(IllegalStateException.class, () -> f.pump(2));
    }

    @Test void failureReportsElapsedAndPermanentlyStopsThisOwner() {
        var f = new Fixture(1000, 672, 10);
        var failure = new AssertionError("actual installed callback failure");
        assertSame(failure, assertThrows(AssertionError.class, () -> f.owner.pump(1, () -> true, (limit, remaining) -> {
            f.clock.now += 42;
            throw failure;
        })));
        assertEquals(42, f.owner.metrics().maxNanos());
        assertEquals(32, f.owner.metrics().maxOvershootNanos());
        assertEquals(1, f.owner.metrics().steps());
        assertThrows(IllegalStateException.class, () -> f.pump(2));
    }

    @Test void noProgressAndImpossibleStatisticsFailBeforeAnyNextStep() {
        for (var invalid : List.of(new NativeCapsuleWorkSlice.Progress(0, 0, 1000, false),
                new NativeCapsuleWorkSlice.Progress(129, 129, 1000, false),
                new NativeCapsuleWorkSlice.Progress(1, 2, 1000, false),
                new NativeCapsuleWorkSlice.Progress(1, 1, 999, false),
                new NativeCapsuleWorkSlice.Progress(1, 1, 1000, true))) {
            var owner = new NativeCapsuleWorkSlice(1000, 672, 1000, () -> 0);
            int[] calls = {0};
            assertThrows(IllegalStateException.class, () -> owner.pump(1, () -> true, (limit, remaining) -> {
                calls[0]++;
                return invalid;
            }));
            assertEquals(1, calls[0]);
            assertThrows(IllegalStateException.class, () -> owner.pump(2, () -> true, (limit, remaining) -> invalid));
        }
    }

    @Test void reentrantFailureCaughtByTheCallbackStillPoisonsOuterWork() {
        var f = new Fixture(1000, 672, 1000);
        f.afterStep = () -> assertThrows(IllegalStateException.class, () -> f.pump(1));
        assertThrows(IllegalStateException.class, () -> f.pump(1));
        assertEquals(1, f.limits.size());
    }

    @Test void wrongThreadCannotMutateOrPoisonTheOwner() throws InterruptedException {
        var f = new Fixture(1000, 672, 1000);
        var failure = new AtomicReference<Throwable>();
        var thread = new Thread(() -> { try { f.pump(1); } catch (Throwable value) { failure.set(value); } });
        thread.start(); thread.join();
        assertInstanceOf(IllegalStateException.class, failure.get());
        assertEquals(0, f.completed);
        assertTrue(f.pump(1).complete());
    }

    @Test void completeOwnerDoesNoExtraInstalledComparison() {
        var f = new Fixture(128, 672, 1000);
        assertTrue(f.pump(1).complete());
        assertEquals(0, f.pump(2).steps());
        assertEquals(1, f.limits.size());
        assertEquals(128, f.owner.metrics().callbacks());
    }

    @Test void constructingAnExpandedCorpusOrSchedulingCapIsRejected() {
        for (int total : new int[]{0, -1, 86_017}) assertThrows(IllegalArgumentException.class, () -> new NativeCapsuleWorkSlice(total));
        for (int steps : new int[]{0, -1, 673}) assertThrows(IllegalArgumentException.class,
                () -> new NativeCapsuleWorkSlice(1, steps, 1, () -> 0));
        assertThrows(IllegalArgumentException.class, () -> new NativeCapsuleWorkSlice(1, 1, 0, () -> 0));
        assertEquals(672, NativeCapsuleWorkSlice.MAX_STEPS);
    }

    private static final class Clock implements LongSupplier { long now; public long getAsLong() { return now; } }
    private static final class Fixture {
        final Clock clock = new Clock();
        final NativeCapsuleWorkSlice owner;
        final int total;
        final List<Integer> limits = new ArrayList<>();
        final List<Long> budgets = new ArrayList<>();
        List<Integer> visited;
        int completed, perStepLimit = 128;
        long nanosPerCase = 1;
        boolean current = true;
        Runnable afterStep = () -> {};
        Fixture(int total, int steps, long budget) { this.total = total; owner = new NativeCapsuleWorkSlice(total, steps, budget, clock); }
        NativeCapsuleWorkSlice.Stats pump(long frame) {
            return owner.pump(frame, () -> current, (limit, remaining) -> {
                limits.add(limit); budgets.add(remaining);
                int count = 0; long start = clock.now;
                do {
                    if (visited != null) visited.add(completed);
                    count++; completed++; clock.now += nanosPerCase;
                } while (count < Math.min(limit, perStepLimit) && completed < total && clock.now - start < remaining);
                afterStep.run();
                return new NativeCapsuleWorkSlice.Progress(count, completed, total, completed == total);
            });
        }
    }
}
