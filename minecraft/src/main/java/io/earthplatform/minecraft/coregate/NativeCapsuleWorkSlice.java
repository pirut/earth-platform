package io.earthplatform.minecraft.coregate;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Packs unchanged installed capsule steps within one measured main-native owner allowance. */
public final class NativeCapsuleWorkSlice {
    public static final int CASES_PER_STEP = 128;
    public static final long SOFT_SLICE_NANOS = 3_000_000L;
    /** The complete admitted capture plus six verification passes, never a replacement corpus. */
    public static final int MAX_CASES = 8 * 6 * 256 * 7;
    public static final int MAX_STEPS = (MAX_CASES + CASES_PER_STEP - 1) / CASES_PER_STEP;

    @FunctionalInterface public interface Stepper { Progress step(int maxCases, long remainingNanos); }
    public record Progress(int callbacks, int completed, int total, boolean complete) {}
    public enum StopReason { COMPLETE, TIME_BUDGET, STEP_LIMIT, DUPLICATE_FRAME }
    public record Stats(int steps, int callbacks, int completed, int total, boolean complete,
                        long elapsedNanos, long budgetNanos, long overshootNanos, StopReason stopReason) {}
    public record Metrics(long slices, long totalNanos, long maxNanos, long maxOvershootNanos,
                          long steps, long callbacks, int maxStepsPerSlice, int maxCallbacksPerSlice,
                          long timeBudgetYields, long stepLimitYields, long duplicateFrames) {}

    private final Thread owner = Thread.currentThread();
    private final int total, maxSteps;
    private final long budget;
    private final LongSupplier clock;
    private long lastFrame = -1, slices, totalNanos, maxNanos, maxOvershoot, steps, callbacks;
    private long timeBudgetYields, stepLimitYields, duplicateFrames;
    private int completed, maxStepsPerSlice, maxCallbacksPerSlice;
    private boolean operating, failed;

    public NativeCapsuleWorkSlice(int totalCases) {
        this(totalCases, MAX_STEPS, SOFT_SLICE_NANOS, System::nanoTime);
    }
    /** Smaller scheduling limits and a deterministic clock are the only test substitutions. */
    public NativeCapsuleWorkSlice(int totalCases, int maxSteps, long budgetNanos, LongSupplier clock) {
        if (totalCases < 1 || totalCases > MAX_CASES || maxSteps < 1 || maxSteps > MAX_STEPS || budgetNanos < 1)
            throw new IllegalArgumentException("Invalid capsule owner-slice limit");
        this.total = totalCases; this.maxSteps = maxSteps; this.budget = budgetNanos;
        this.clock = Objects.requireNonNull(clock);
    }

    /** No work is performed twice for a frame, even when a caller repeats the main callback. */
    public Stats pump(long frame, BooleanSupplier current, Stepper stepper) {
        checkOwner();
        if (operating) { failed = true; throw new IllegalStateException("Reentrant capsule owner slice"); }
        if (failed) throw new IllegalStateException("Capsule owner slice failed");
        if (frame < 0 || frame < lastFrame) throw new IllegalArgumentException("Stale capsule render frame");
        Objects.requireNonNull(current); Objects.requireNonNull(stepper);
        long start = clock.getAsLong();
        int sliceSteps = 0, sliceCallbacks = 0;
        long elapsed = 0;
        Throwable sliceFailure = null;
        StopReason stop = StopReason.COMPLETE;
        operating = true;
        try {
            requireCurrent(current);
            if (frame == lastFrame) {
                duplicateFrames = increment(duplicateFrames);
                stop = StopReason.DUPLICATE_FRAME;
            } else {
                lastFrame = frame;
            }
            while (stop != StopReason.DUPLICATE_FRAME && completed < total) {
                requireCurrent(current);
                long remaining = budget - elapsedSince(start);
                if (remaining <= 0) { stop = StopReason.TIME_BUDGET; break; }
                if (sliceSteps >= maxSteps) { stop = StopReason.STEP_LIMIT; break; }
                sliceSteps++;
                var value = Objects.requireNonNull(stepper.step(CASES_PER_STEP, remaining));
                requireCurrent(current);
                if (value.callbacks() < 1 || value.callbacks() > CASES_PER_STEP
                        || value.completed() - completed != value.callbacks() || value.total() != total
                        || value.completed() > total || value.complete() != (value.completed() == total))
                    throw new IllegalStateException("Capsule step violated exact forward progress");
                completed = value.completed();
                sliceCallbacks += value.callbacks();
            }
        } catch (RuntimeException | Error failure) { failed = true; sliceFailure = failure; throw failure; }
        finally {
            try {
                elapsed = elapsedSince(start);
                slices = increment(slices); totalNanos = add(totalNanos, elapsed);
                maxNanos = Math.max(maxNanos, elapsed); maxOvershoot = Math.max(maxOvershoot, overshoot(elapsed));
                steps = add(steps, sliceSteps); callbacks = add(callbacks, sliceCallbacks);
                maxStepsPerSlice = Math.max(maxStepsPerSlice, sliceSteps);
                maxCallbacksPerSlice = Math.max(maxCallbacksPerSlice, sliceCallbacks);
            } catch (RuntimeException | Error failure) {
                failed = true;
                if (sliceFailure == null) throw failure;
                if (sliceFailure != failure) sliceFailure.addSuppressed(failure);
            } finally { operating = false; }
        }
        if (stop == StopReason.TIME_BUDGET) timeBudgetYields = increment(timeBudgetYields);
        if (stop == StopReason.STEP_LIMIT) stepLimitYields = increment(stepLimitYields);
        return new Stats(sliceSteps, sliceCallbacks, completed, total, completed == total,
                elapsed, budget, overshoot(elapsed), stop);
    }

    public Metrics metrics() {
        checkOwner();
        return new Metrics(slices, totalNanos, maxNanos, maxOvershoot, steps, callbacks,
                maxStepsPerSlice, maxCallbacksPerSlice, timeBudgetYields, stepLimitYields, duplicateFrames);
    }
    private void requireCurrent(BooleanSupplier current) {
        if (failed || !current.getAsBoolean() || failed) throw new IllegalStateException("Capsule owner slice is no longer current");
    }
    private void checkOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Capsule slice requires its owner thread");
    }
    private long elapsedSince(long start) { return Math.max(0, clock.getAsLong() - start); }
    private long overshoot(long elapsed) { return elapsed > budget ? elapsed - budget : 0; }
    private static long increment(long value) { return value == Long.MAX_VALUE ? value : value + 1; }
    private static long add(long value, long amount) { return Long.MAX_VALUE - value < amount ? Long.MAX_VALUE : value + amount; }
}
