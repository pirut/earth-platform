package io.earthplatform.minecraft.scene;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure scheduler tests: no game bootstrap, native context, or wall-clock timing. */
class SceneBuildStepperTest {
    private static final Runnable NOTHING = () -> {};

    @Test void visitsAll4096BlocksExactlyOnceInOrderAcrossDifferentSlices() {
        var now = new AtomicLong();
        var seen = new ArrayList<Integer>();
        var validations = new AtomicInteger();
        var enters = new AtomicInteger();
        var exits = new AtomicInteger();
        var stepper = new SceneBuildStepper(4096, validations::incrementAndGet, index -> {
            seen.add(index);
            now.addAndGet(1 + index % 7);
        }, enters::incrementAndGet, exits::incrementAndGet, now::get);
        int[] limits = {1, 17, 256, 9, 4096};
        long[] budgets = {1, 37, 1000, 11, 503};
        int steps = 0;
        long elapsed = 0;
        long maxStep = 0;
        long maxOvershoot = 0;
        long maxBlock = 0;
        while (!stepper.complete()) {
            int completedBefore = stepper.completed();
            int limit = limits[steps % limits.length];
            long budget = budgets[steps % budgets.length];
            var stats = stepper.step(limit, budget);
            assertTrue(stats.blocks() >= 1 && stats.blocks() <= limit);
            assertEquals(completedBefore + stats.blocks(), stats.completedBlocks());
            assertEquals(4096, stats.totalBlocks());
            assertEquals(budget, stats.budgetNanos());
            assertEquals(Math.max(0, stats.elapsedNanos() - budget), stats.overshootNanos());
            assertTrue(stats.overshootNanos() <= stats.maxBlockNanos(),
                    "The soft budget may be exceeded by only the last indivisible callback");
            assertEquals(stats.completedBlocks() == 4096, stats.complete());
            assertSame(stats, stepper.last());
            elapsed += stats.elapsedNanos();
            maxStep = Math.max(maxStep, stats.elapsedNanos());
            maxOvershoot = Math.max(maxOvershoot, stats.overshootNanos());
            maxBlock = Math.max(maxBlock, stats.maxBlockNanos());
            steps++;
        }
        assertTrue(steps > 1);
        assertEquals(4096, seen.size());
        for (int index = 0; index < 4096; index++) assertEquals(index, seen.get(index));
        assertEquals(8192, validations.get());
        assertEquals(steps, enters.get());
        assertEquals(steps, exits.get());
        assertEquals(steps, stepper.steps());
        assertEquals(elapsed, stepper.totalNanos());
        assertEquals(maxStep, stepper.maxStepNanos());
        assertEquals(maxOvershoot, stepper.maxOvershootNanos());
        assertEquals(maxBlock, stepper.maxBlockNanos());
        assertEquals(4096, stepper.completed());
        assertThrows(IllegalStateException.class, () -> stepper.step(1, 1));
        assertEquals(steps, exits.get(), "Completed work must not enter another slice");
    }

    @Test void stopsAtTheHardBlockLimitEvenWhenTheClockDoesNotAdvance() {
        var seen = new ArrayList<Integer>();
        var stepper = simple(10, seen::add, () -> 0);
        assertEquals(3, stepper.step(3, Long.MAX_VALUE).blocks());
        assertEquals(List.of(0, 1, 2), seen);
        assertEquals(3, stepper.step(3, Long.MAX_VALUE).blocks());
        assertEquals(4, stepper.step(20, Long.MAX_VALUE).blocks());
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), seen);
        assertTrue(stepper.complete());
    }

    @Test void stopsExactlyAtTheBudgetWithoutStartingAnotherBlock() {
        var now = new AtomicLong();
        var stepper = simple(10, index -> now.addAndGet(5), now::get);
        var stats = stepper.step(10, 10);
        assertEquals(2, stats.blocks());
        assertEquals(10, stats.elapsedNanos());
        assertEquals(0, stats.overshootNanos());
        assertEquals(5, stats.maxBlockNanos());
        assertFalse(stats.complete());
    }

    @Test void softBudgetAllowsOnlyOneUninterruptibleCallbackToOvershoot() {
        var now = new AtomicLong();
        long[] durations = {4, 4, 70, 1};
        var seen = new ArrayList<Integer>();
        var stepper = simple(4, index -> {
            seen.add(index);
            now.addAndGet(durations[index]);
        }, now::get);
        var stats = stepper.step(4, 10);
        assertEquals(List.of(0, 1, 2), seen);
        assertEquals(3, stats.blocks());
        assertEquals(78, stats.elapsedNanos());
        assertEquals(68, stats.overshootNanos());
        assertEquals(70, stats.maxBlockNanos());
        assertTrue(stats.overshootNanos() <= stats.maxBlockNanos());
        assertEquals(1, stepper.step(4, 10).blocks());
    }

    @Test void firstCallbackRunsEvenIfBeforeHookAlreadySpentTheBudget() {
        var now = new AtomicLong();
        var seen = new ArrayList<Integer>();
        var stepper = new SceneBuildStepper(2, NOTHING, index -> {
            seen.add(index);
            now.addAndGet(7);
        }, () -> now.addAndGet(100), () -> now.addAndGet(3), now::get);
        var stats = stepper.step(2, 10);
        assertEquals(List.of(0), seen);
        assertEquals(1, stats.blocks());
        assertEquals(110, stats.elapsedNanos());
        assertEquals(100, stats.overshootNanos());
        assertEquals(7, stats.maxBlockNanos(), "Hook time is not callback time");
    }

    @Test void reportsPerSliceAndCumulativeDurationIncludingHooks() {
        var now = new AtomicLong();
        long[] durations = {2, 11, 4};
        var stepper = new SceneBuildStepper(3, NOTHING,
                index -> now.addAndGet(durations[index]),
                () -> now.addAndGet(3), () -> now.addAndGet(5), now::get);
        var first = stepper.step(2, 20);
        assertEquals(new SceneBuildStepper.StepStats(2, 2, 3, 21, 20, 1, 11, false), first);
        var second = stepper.step(2, 10);
        assertEquals(new SceneBuildStepper.StepStats(1, 3, 3, 12, 10, 2, 4, true), second);
        assertEquals(2, stepper.steps());
        assertEquals(33, stepper.totalNanos());
        assertEquals(21, stepper.maxStepNanos());
        assertEquals(2, stepper.maxOvershootNanos());
        assertEquals(11, stepper.maxBlockNanos());
    }

    @Test void validatesBeforeAndAfterEachCallbackIncludingTheFinalCallback() {
        var events = new ArrayList<String>();
        var stepper = new SceneBuildStepper(2, () -> events.add("validate"),
                index -> events.add("block:" + index), () -> events.add("before"),
                () -> events.add("after"), () -> 0);
        stepper.step(2, 1);
        assertEquals(List.of("before", "validate", "block:0", "validate",
                "validate", "block:1", "validate", "after"), events);
    }

    @Test void validationFailureBeforeFirstCallbackStillCleansUpAndCannotResume() {
        var failure = new IllegalStateException("invalid initial lease");
        var events = new ArrayList<String>();
        var stepper = new SceneBuildStepper(2, () -> { throw failure; },
                index -> events.add("block"), () -> events.add("before"),
                () -> events.add("after"), () -> 0);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> stepper.step(2, 1)));
        assertEquals(List.of("before", "after"), events);
        assertEquals(0, stepper.completed());
        assertFailedAndCannotResume(stepper);
        assertEquals(List.of("before", "after"), events);
    }

    @Test void validationFailureAfterFinalCallbackDoesNotPublishCompletion() {
        var failure = new IllegalStateException("final callback invalidated generation");
        var valid = new AtomicInteger(1);
        var cleanup = new AtomicInteger();
        var callbacks = new AtomicInteger();
        var stepper = new SceneBuildStepper(1, () -> {
            if (valid.get() == 0) throw failure;
        }, index -> { callbacks.incrementAndGet(); valid.set(0); }, NOTHING,
                cleanup::incrementAndGet, () -> 0);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> stepper.step(1, 1)));
        assertEquals(1, callbacks.get());
        assertEquals(1, cleanup.get());
        assertEquals(0, stepper.completed());
        assertEquals(0, stepper.last().blocks());
        assertFailedAndCannotResume(stepper);
        assertEquals(1, callbacks.get());
    }

    @Test void validationFailureBetweenSlicesRetainsOnlyEarlierCompletedBlocks() {
        var validations = new AtomicInteger();
        var cleanup = new AtomicInteger();
        var callbacks = new AtomicInteger();
        var stepper = new SceneBuildStepper(3, () -> {
            if (validations.incrementAndGet() == 3) throw new IllegalStateException("lease expired");
        }, index -> callbacks.incrementAndGet(), NOTHING, cleanup::incrementAndGet, () -> 0);
        stepper.step(1, 1);
        assertThrows(IllegalStateException.class, () -> stepper.step(2, 1));
        assertEquals(1, callbacks.get());
        assertEquals(1, stepper.completed());
        assertEquals(2, cleanup.get());
        assertEquals(2, stepper.steps());
        assertFailedAndCannotResume(stepper);
    }

    @Test void beforeHookFailureStillRunsAfterHookAndCannotResume() {
        var failure = new AssertionError("before failed");
        var events = new ArrayList<String>();
        var stepper = new SceneBuildStepper(1, () -> events.add("validate"),
                index -> events.add("block"), () -> { events.add("before"); throw failure; },
                () -> events.add("after"), () -> 0);
        assertSame(failure, assertThrows(AssertionError.class, () -> stepper.step(1, 1)));
        assertEquals(List.of("before", "after"), events);
        assertFailedAndCannotResume(stepper);
    }

    @Test void afterHookFailurePreventsSuccessfulCompletionAndCannotResume() {
        var failure = new IllegalStateException("after failed");
        var callbacks = new AtomicInteger();
        var cleanup = new AtomicInteger();
        var stepper = new SceneBuildStepper(1, NOTHING, index -> callbacks.incrementAndGet(), NOTHING,
                () -> { cleanup.incrementAndGet(); throw failure; }, () -> 0);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> stepper.step(1, 1)));
        assertEquals(1, callbacks.get());
        assertEquals(1, cleanup.get());
        assertEquals(1, stepper.completed());
        assertFailedAndCannotResume(stepper);
        assertEquals(1, cleanup.get());
    }

    @Test void blockAndAfterFailuresPreservePrimaryFailureAndMeasureFailedBlock() {
        var failure = new AssertionError("block failed");
        var cleanupFailure = new IllegalStateException("cleanup failed");
        var now = new AtomicLong();
        var stepper = new SceneBuildStepper(1, NOTHING, index -> {
            now.addAndGet(13);
            throw failure;
        }, () -> now.addAndGet(2), () -> { now.addAndGet(7); throw cleanupFailure; }, now::get);
        assertSame(failure, assertThrows(AssertionError.class, () -> stepper.step(1, 10)));
        assertArrayEquals(new Throwable[]{cleanupFailure}, failure.getSuppressed());
        assertEquals(new SceneBuildStepper.StepStats(0, 0, 1, 22, 10, 12, 13, false), stepper.last());
        assertEquals(22, stepper.totalNanos());
        assertEquals(22, stepper.maxStepNanos());
        assertEquals(12, stepper.maxOvershootNanos());
        assertEquals(13, stepper.maxBlockNanos());
        assertFailedAndCannotResume(stepper);
    }

    @Test void beforeAndAfterFailuresPreserveBothFailuresAfterCleanup() {
        var failure = new IllegalStateException("before failed");
        var cleanupFailure = new AssertionError("after failed");
        var cleanup = new AtomicInteger();
        var stepper = new SceneBuildStepper(1, NOTHING, index -> fail("Must not enter block"),
                () -> { throw failure; }, () -> { cleanup.incrementAndGet(); throw cleanupFailure; }, () -> 0);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> stepper.step(1, 1)));
        assertEquals(1, cleanup.get());
        assertArrayEquals(new Throwable[]{cleanupFailure}, failure.getSuppressed());
        assertFailedAndCannotResume(stepper);
    }

    @Test void repeatedSameThrowableDuringCleanupDoesNotSelfSuppress() {
        var failure = new IllegalStateException("shared failure");
        var stepper = new SceneBuildStepper(1, NOTHING, index -> { throw failure; }, NOTHING,
                () -> { throw failure; }, () -> 0);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> stepper.step(1, 1)));
        assertEquals(0, failure.getSuppressed().length);
        assertFailedAndCannotResume(stepper);
    }

    @Test void cancellationBeforeStartingDoesNotEnterAnyHookOrCallback() {
        var calls = new AtomicInteger();
        var stepper = new SceneBuildStepper(1, calls::incrementAndGet,
                index -> calls.incrementAndGet(), calls::incrementAndGet,
                calls::incrementAndGet, () -> { calls.incrementAndGet(); return 0; });
        stepper.cancel();
        stepper.cancel();
        assertThrows(IllegalStateException.class, () -> stepper.step(1, 1));
        assertEquals(0, calls.get());
        assertEquals(0, stepper.steps());
        assertFalse(stepper.complete());
    }

    @Test void cancellationBetweenSlicesDoesNotReenterWork() {
        var callbacks = new AtomicInteger();
        var cleanup = new AtomicInteger();
        var stepper = new SceneBuildStepper(2, NOTHING, index -> callbacks.incrementAndGet(),
                NOTHING, cleanup::incrementAndGet, () -> 0);
        stepper.step(1, 1);
        stepper.cancel();
        assertThrows(IllegalStateException.class, () -> stepper.step(1, 1));
        assertEquals(1, callbacks.get());
        assertEquals(1, cleanup.get());
        assertEquals(1, stepper.completed());
        assertFalse(stepper.complete());
    }

    @Test void cancellationDuringFinalCallbackStillCleansUpAndCannotComplete() {
        var current = new AtomicReference<SceneBuildStepper>();
        var cleanup = new AtomicInteger();
        var callbacks = new AtomicInteger();
        var stepper = new SceneBuildStepper(1, NOTHING, index -> {
            callbacks.incrementAndGet();
            current.get().cancel();
        }, NOTHING, cleanup::incrementAndGet, () -> 0);
        current.set(stepper);
        assertThrows(IllegalStateException.class, () -> stepper.step(1, 1));
        assertEquals(1, callbacks.get());
        assertEquals(1, cleanup.get());
        assertEquals(0, stepper.completed());
        assertFailedAndCannotResume(stepper);
    }

    @Test void cancellationDuringValidationPreventsEnteringBlockAndStillCleansUp() {
        var current = new AtomicReference<SceneBuildStepper>();
        var cleanup = new AtomicInteger();
        var stepper = new SceneBuildStepper(1, () -> current.get().cancel(),
                index -> fail("Cancelled work must not enter block"), NOTHING,
                cleanup::incrementAndGet, () -> 0);
        current.set(stepper);
        assertThrows(IllegalStateException.class, () -> stepper.step(1, 1));
        assertEquals(1, cleanup.get());
        assertFailedAndCannotResume(stepper);
    }

    @Test void reentrantStepCannotInvokeMoreCallbacksAndOuterFailureStillCleansUp() {
        var current = new AtomicReference<SceneBuildStepper>();
        var callbacks = new AtomicInteger();
        var cleanup = new AtomicInteger();
        var stepper = new SceneBuildStepper(2, NOTHING, index -> {
            callbacks.incrementAndGet();
            assertTrue(current.get().stepping());
            current.get().step(1, 1);
        }, NOTHING, cleanup::incrementAndGet, () -> 0);
        current.set(stepper);
        assertThrows(IllegalStateException.class, () -> stepper.step(2, 1));
        assertEquals(1, callbacks.get());
        assertEquals(1, cleanup.get());
        assertFailedAndCannotResume(stepper);
    }

    @Test void wrongThreadIsRejectedBeforeHooksCallbacksClockOrArgumentValidation() throws Exception {
        var calls = new AtomicInteger();
        var stepper = new SceneBuildStepper(1, calls::incrementAndGet,
                index -> calls.incrementAndGet(), calls::incrementAndGet,
                calls::incrementAndGet, () -> { calls.incrementAndGet(); return 0; });
        assertOwnerFailure(() -> stepper.step(1, 1));
        assertOwnerFailure(() -> stepper.step(0, 0));
        assertOwnerFailure(stepper::cancel);
        assertOwnerFailure(stepper::complete);
        assertOwnerFailure(stepper::completed);
        assertOwnerFailure(stepper::steps);
        assertOwnerFailure(stepper::totalNanos);
        assertOwnerFailure(stepper::maxStepNanos);
        assertOwnerFailure(stepper::maxOvershootNanos);
        assertOwnerFailure(stepper::maxBlockNanos);
        assertOwnerFailure(stepper::last);
        assertOwnerFailure(stepper::stepping);
        assertOwnerFailure(stepper::checkOwner);
        assertEquals(0, calls.get());
        assertEquals(0, stepper.steps());
        assertTrue(stepper.step(1, 1).complete(), "Rejected thread must not poison owner state");
    }

    @Test void invalidLimitsDoNotStartOrPoisonTheBuild() {
        var calls = new AtomicInteger();
        var stepper = simple(1, index -> calls.incrementAndGet(), () -> 0);
        for (int blocks : new int[]{0, -1, Integer.MIN_VALUE})
            assertThrows(IllegalArgumentException.class, () -> stepper.step(blocks, 1));
        for (long budget : new long[]{0, -1, Long.MIN_VALUE})
            assertThrows(IllegalArgumentException.class, () -> stepper.step(1, budget));
        assertEquals(0, calls.get());
        assertEquals(0, stepper.steps());
        assertTrue(stepper.step(1, 1).complete());
    }

    @Test void validatesConstructorAndStartsWithEmptyStatistics() {
        assertThrows(IllegalArgumentException.class, () -> simple(0, index -> {}, () -> 0));
        assertThrows(IllegalArgumentException.class, () -> simple(-1, index -> {}, () -> 0));
        assertThrows(NullPointerException.class,
                () -> new SceneBuildStepper(1, null, index -> {}, NOTHING, NOTHING, () -> 0));
        assertThrows(NullPointerException.class,
                () -> new SceneBuildStepper(1, NOTHING, null, NOTHING, NOTHING, () -> 0));
        assertThrows(NullPointerException.class,
                () -> new SceneBuildStepper(1, NOTHING, index -> {}, null, NOTHING, () -> 0));
        assertThrows(NullPointerException.class,
                () -> new SceneBuildStepper(1, NOTHING, index -> {}, NOTHING, null, () -> 0));
        assertThrows(NullPointerException.class,
                () -> new SceneBuildStepper(1, NOTHING, index -> {}, NOTHING, NOTHING, null));
        var stepper = simple(3, index -> {}, () -> 0);
        assertEquals(3, stepper.total());
        assertEquals(new SceneBuildStepper.StepStats(0, 0, 3, 0, 0, 0, 0, false), stepper.last());
        assertEquals(0, stepper.steps());
        assertEquals(0, stepper.totalNanos());
        assertEquals(0, stepper.maxStepNanos());
        assertEquals(0, stepper.maxOvershootNanos());
        assertEquals(0, stepper.maxBlockNanos());
        assertFalse(stepper.stepping());
        assertFalse(stepper.complete());
    }

    @Test void elapsedNanosHandlesMonotonicClockWraparound() {
        var now = new AtomicLong(Long.MAX_VALUE - 4);
        var stepper = simple(1, index -> now.addAndGet(10), now::get);
        var stats = stepper.step(1, 5);
        assertEquals(10, stats.elapsedNanos());
        assertEquals(10, stats.maxBlockNanos());
        assertEquals(5, stats.overshootNanos());
    }

    @Test void cumulativeDurationSaturatesInsteadOfOverflowing() {
        var now = new AtomicLong();
        long duration = Long.MAX_VALUE / 2 + 1;
        var stepper = simple(2, index -> now.addAndGet(duration), now::get);
        assertEquals(duration, stepper.step(1, Long.MAX_VALUE).elapsedNanos());
        assertEquals(duration, stepper.step(1, Long.MAX_VALUE).elapsedNanos());
        assertEquals(Long.MAX_VALUE, stepper.totalNanos());
        assertEquals(duration, stepper.maxStepNanos());
    }

    private static SceneBuildStepper simple(int total, IntConsumer block, LongSupplier clock) {
        return new SceneBuildStepper(total, NOTHING, block, NOTHING, NOTHING, clock);
    }

    private static void assertFailedAndCannotResume(SceneBuildStepper stepper) {
        assertFalse(stepper.complete());
        assertFalse(stepper.last().complete());
        assertFalse(stepper.stepping());
        int steps = stepper.steps();
        var last = stepper.last();
        assertThrows(IllegalStateException.class, () -> stepper.step(1, 1));
        assertEquals(steps, stepper.steps());
        assertSame(last, stepper.last());
    }

    private static void assertOwnerFailure(Runnable action) throws InterruptedException {
        var thrown = new AtomicReference<Throwable>();
        Thread worker = Thread.ofPlatform().daemon().start(() -> {
            try { action.run(); } catch (Throwable failure) { thrown.set(failure); }
        });
        worker.join(5000);
        assertFalse(worker.isAlive(), "Thread-affinity check did not return");
        var failure = assertInstanceOf(IllegalStateException.class, thrown.get());
        assertTrue(failure.getMessage().contains("owner thread"));
    }
}
