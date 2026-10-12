package io.earthplatform.minecraft.coregate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Exact scheduling/corpus-prefix tests; installed model bytes are deliberately not simulated. */
class NativeCapsuleSliceIndependentTest {
    @ParameterizedTest @ValueSource(ints = {1, 127, 128, 129, 12288, 86016})
    void fastClockPacksEveryOriginalCaseInOrderWithoutSkippingOrDuplicating(int total) {
        var f = new Capture(total, 672, 100);
        var result = f.pump(1);
        assertTrue(result.complete()); assertEquals("COMPLETE", result.stopReason().name());
        assertEquals(total, result.callbacks()); assertEquals(total, result.completed());
        assertEquals((total + 127) / 128, result.steps());
        assertEquals(total, f.cases.size());
        for (int i = 0; i < total; i++) assertEquals(i, f.cases.get(i));
        assertTrue(f.caps.stream().allMatch(cap -> cap == 128));
        assertEquals(result.steps(), f.slice.metrics().steps());
        assertEquals(total, f.slice.metrics().callbacks());
    }

    @Test void finalIndivisibleStepMayOvershootButReturnedAndCumulativeEvidenceRemainHonest() {
        var f = new Capture(2, 672, 10); f.stepCost = 27;
        var result = f.pump(1);
        assertTrue(result.complete()); assertEquals(1, result.steps());
        assertEquals(27, result.elapsedNanos()); assertEquals(17, result.overshootNanos());
        assertEquals(27, f.slice.metrics().maxNanos()); assertEquals(17, f.slice.metrics().maxOvershootNanos());
    }

    @Test void onlyMeasuredRemainderIsGrantedAndNoFollowingSubstepStartsAfterOvershoot() {
        var f = new Capture(1024, 672, 10);
        f.costs = List.of(2L, 5L, 11L);
        var result = f.pump(1);
        assertEquals(List.of(10L, 8L, 3L), f.budgets);
        assertEquals(3, result.steps()); assertEquals(384, result.completed());
        assertEquals(18, result.elapsedNanos()); assertEquals(8, result.overshootNanos());
        assertEquals("TIME_BUDGET", result.stopReason().name());
        assertEquals(1, f.slice.metrics().timeBudgetYields());
        int calls = f.budgets.size();
        var duplicate = f.pump(1);
        assertEquals("DUPLICATE_FRAME", duplicate.stopReason().name());
        assertEquals(calls, f.budgets.size()); assertEquals(0, duplicate.steps());
    }

    @Test void exactLimitPreventsEvenOneExtraInstalledStep() {
        var f = new Capture(256, 672, 10); f.stepCost = 10;
        var result = f.pump(0);
        assertEquals(1, result.steps()); assertEquals(128, result.completed());
        assertEquals(0, result.overshootNanos()); assertFalse(result.complete());
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 7, 671, 672})
    void stoppedClockRemainsFiniteEvenWhenEveryStepCompletesOnlyOneCase(int cap) {
        var f = new Capture(86016, cap, 100); f.casesPerCall = 1;
        var result = f.pump(1);
        assertEquals(cap, result.steps()); assertEquals(cap, result.callbacks());
        assertEquals(cap, f.cases.size()); assertEquals("STEP_LIMIT", result.stopReason().name());
        assertEquals(1, f.slice.metrics().stepLimitYields());
        assertFalse(result.complete());
    }

    @Test void expiredAllowanceBeforeFirstStepStartsNoNativeCallback() {
        long[] clock = {0};
        var slice = new NativeCapsuleWorkSlice(128, 10, 10, () -> clock[0]);
        var result = slice.pump(1, () -> { clock[0] = 10; return true; }, (limit, budget) -> { fail("No callback after currentness check consumes budget"); return null; });
        assertEquals(0, result.steps()); assertEquals(0, result.completed());
        assertEquals("TIME_BUDGET", result.stopReason().name());
        assertEquals(10, result.elapsedNanos());
    }

    @Test void duplicateFrameStillRejectsRevokedCurrentnessAndCannotReviveOnAnotherFrame() {
        var f = new Capture(256, 1, 100);
        f.pump(1); f.current = false;
        assertThrows(IllegalStateException.class, () -> f.pump(1));
        f.current = true;
        assertThrows(IllegalStateException.class, () -> f.pump(2));
        assertEquals(128, f.cases.size());
    }

    @Test void revocationBetweenInstalledSubstepsRejectsJustReturnedProgressAndStopsImmediately() {
        var f = new Capture(512, 672, 100);
        f.afterStep = () -> f.current = false;
        assertThrows(IllegalStateException.class, () -> f.pump(1));
        assertEquals(128, f.cases.size());
        assertEquals(0, f.slice.metrics().callbacks(), "Only returned, fence-validated prefixes become completed evidence");
        assertEquals(1, f.slice.metrics().steps());
        f.current = true; f.afterStep = () -> {};
        assertThrows(IllegalStateException.class, () -> f.pump(2));
    }

    @Test void reentrantCallbackThatCatchesItsOwnErrorCannotResumeTheOuterSlice() {
        var f = new Capture(512, 672, 100);
        f.afterStep = () -> assertThrows(IllegalStateException.class, () -> f.pump(1));
        assertThrows(IllegalStateException.class, () -> f.pump(1));
        assertEquals(128, f.cases.size()); assertEquals(0, f.slice.metrics().callbacks());
    }

    @Test void allMalformedOrNonmonotonicPrefixesFailClosedBeforeTheNextStep() {
        var bad = List.of(
                new NativeCapsuleWorkSlice.Progress(0, 0, 256, false),
                new NativeCapsuleWorkSlice.Progress(-1, -1, 256, false),
                new NativeCapsuleWorkSlice.Progress(129, 129, 256, false),
                new NativeCapsuleWorkSlice.Progress(128, 127, 256, false),
                new NativeCapsuleWorkSlice.Progress(128, 128, 255, false),
                new NativeCapsuleWorkSlice.Progress(128, 128, 256, true),
                new NativeCapsuleWorkSlice.Progress(128, 384, 256, false),
                new NativeCapsuleWorkSlice.Progress(128, Integer.MIN_VALUE, 256, false));
        for (var value : bad) {
            var clock = new Clock(); var slice = new NativeCapsuleWorkSlice(256, 672, 10, clock);
            int[] calls = {0};
            assertThrows(IllegalStateException.class, () -> slice.pump(1, () -> true, (limit, budget) -> { calls[0]++; return value; }));
            assertEquals(1, calls[0]); assertEquals(0, slice.metrics().callbacks());
            assertThrows(IllegalStateException.class, () -> slice.pump(2, () -> true, (limit, budget) -> { fail("Malformed prefix cannot be retried"); return null; }));
        }
        var f = new Capture(512, 672, 100);
        int[] calls = {0};
        assertThrows(IllegalStateException.class, () -> f.slice.pump(1, () -> true, (limit, budget) -> {
            calls[0]++; return new NativeCapsuleWorkSlice.Progress(128, 128, 512, false);
        }));
        assertEquals(2, calls[0]); assertEquals(128, f.slice.metrics().callbacks());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void throwingInstalledStepRecordsItsWallCostWithoutClaimingCompletedCases(boolean error) {
        var f = new Capture(256, 672, 10);
        Throwable sentinel = error ? new AssertionError("installed callback") : new IllegalStateException("installed callback");
        assertSame(sentinel, assertThrows(Throwable.class, () -> f.slice.pump(1, () -> true, (limit, remaining) -> {
            f.clock.now += 33;
            RollingAdaptiveSliceIndependentTest.raise(sentinel); return null;
        })));
        assertEquals(33, f.slice.metrics().maxNanos()); assertEquals(23, f.slice.metrics().maxOvershootNanos());
        assertEquals(1, f.slice.metrics().steps()); assertEquals(0, f.slice.metrics().callbacks());
        assertThrows(IllegalStateException.class, () -> f.pump(2));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void primaryInstalledFailureSurvivesAFailingFinalTimingClock(boolean error) {
        var f = new Capture(256, 672, 10);
        Throwable primary = error ? new AssertionError("primary native callback") : new IllegalStateException("primary native callback");
        assertSame(primary, assertThrows(Throwable.class, () -> f.slice.pump(1, () -> true, (limit, remaining) -> {
            f.clock.failed = true;
            RollingAdaptiveSliceIndependentTest.raise(primary); return null;
        })));
        assertTrue(java.util.Arrays.stream(primary.getSuppressed()).anyMatch(e -> e.getMessage().contains("clock unavailable")));
        f.clock.failed = false;
        assertThrows(IllegalStateException.class, () -> f.pump(2));
    }

    @Test void longFrameGapNeverAccumulatesMoreThanOneFreshBudget() {
        var f = new Capture(512, 672, 10); f.stepCost = 10;
        f.pump(1); f.clock.now += 100_000_000_000L;
        var next = f.pump(1_000_000);
        assertEquals(1, next.steps()); assertEquals(256, next.completed()); assertEquals(10, next.budgetNanos());
        assertEquals(List.of(10L, 10L), f.budgets);
        assertThrows(IllegalArgumentException.class, () -> f.pump(999_999));
        assertEquals(2, f.budgets.size());
    }

    @Test void signedOriginAndClockWrapDoNotAlterTheObservedRemainingBudget() {
        for (long origin : new long[]{Long.MIN_VALUE + 12, -20, Long.MAX_VALUE - 4}) {
            var f = new Capture(512, 672, 10); f.clock.now = origin; f.stepCost = 7;
            var result = f.pump(1);
            assertEquals(List.of(10L, 3L), f.budgets);
            assertEquals(14, result.elapsedNanos()); assertEquals(4, result.overshootNanos());
        }
    }

    @Test void clockFailureAfterInstalledStepPoisonsSchedulingInsteadOfSilentlyRetrying() {
        var f = new Capture(256, 672, 10);
        f.afterStep = () -> f.clock.failed = true;
        assertThrows(IllegalStateException.class, () -> f.pump(1));
        f.clock.failed = false;
        assertThrows(IllegalStateException.class, () -> f.pump(2));
        assertEquals(128, f.cases.size());
    }

    @Test void constructingThreadAndProductionCapsRemainHard() throws InterruptedException {
        assertEquals(86016, NativeCapsuleWorkSlice.MAX_CASES); assertEquals(672, NativeCapsuleWorkSlice.MAX_STEPS);
        assertEquals(128, NativeCapsuleWorkSlice.CASES_PER_STEP); assertEquals(3_000_000, NativeCapsuleWorkSlice.SOFT_SLICE_NANOS);
        for (int total : new int[]{0, -1, 86017}) assertThrows(IllegalArgumentException.class, () -> new NativeCapsuleWorkSlice(total));
        for (int max : new int[]{0, -1, 673}) assertThrows(IllegalArgumentException.class, () -> new NativeCapsuleWorkSlice(128, max, 10, () -> 0));
        var f = new Capture(128, 672, 10); var failure = new AtomicReference<Throwable>();
        var thread = new Thread(() -> { try { f.pump(1); } catch (Throwable e) { failure.set(e); } });
        thread.start(); thread.join();
        assertInstanceOf(IllegalStateException.class, failure.get()); assertTrue(f.cases.isEmpty());
        assertTrue(f.pump(1).complete());
    }

    static final class Clock implements LongSupplier {
        long now; boolean failed;
        public long getAsLong() { if (failed) throw new IllegalStateException("clock unavailable"); return now; }
    }
    static final class Capture {
        final Clock clock = new Clock(); final NativeCapsuleWorkSlice slice; final int total;
        final List<Integer> cases = new ArrayList<>(), caps = new ArrayList<>(); final List<Long> budgets = new ArrayList<>();
        List<Long> costs = List.of(); long stepCost; int casesPerCall = 128; boolean current = true;
        Runnable afterStep = () -> {};
        Capture(int total, int maxSteps, long budget) { this.total = total; slice = new NativeCapsuleWorkSlice(total, maxSteps, budget, clock); }
        NativeCapsuleWorkSlice.Stats pump(long frame) {
            return slice.pump(frame, () -> current, (limit, remaining) -> {
                int call = caps.size(); caps.add(limit); budgets.add(remaining);
                int count = Math.min(Math.min(casesPerCall, limit), total - cases.size());
                for (int i = 0; i < count; i++) cases.add(cases.size());
                clock.now += costs.isEmpty() ? stepCost : costs.get(Math.min(call, costs.size() - 1));
                afterStep.run();
                return new NativeCapsuleWorkSlice.Progress(count, cases.size(), total, cases.size() == total);
            });
        }
    }
}
