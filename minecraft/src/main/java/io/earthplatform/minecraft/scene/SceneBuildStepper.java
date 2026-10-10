package io.earthplatform.minecraft.scene;

import java.util.Objects;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;

/** Owner-only, deterministic callback scheduler. Time is a soft limit; callback count is hard. */
final class SceneBuildStepper {
    record StepStats(int blocks, int completedBlocks, int totalBlocks, long elapsedNanos,
                     long budgetNanos, long overshootNanos, long maxBlockNanos, boolean complete) {}

    private final Thread owner = Thread.currentThread();
    private final int total;
    private final Runnable validate, before, after;
    private final IntConsumer block;
    private final LongSupplier clock;
    private int completed, steps;
    private long totalNanos, maxStepNanos, maxOvershootNanos, maxBlockNanos;
    private boolean failed, cancelled, stepping;
    private StepStats last;

    SceneBuildStepper(int total, Runnable validate, IntConsumer block, Runnable before, Runnable after,
                      LongSupplier clock) {
        if (total < 1) throw new IllegalArgumentException("At least one callback is required");
        this.total = total;
        this.validate = Objects.requireNonNull(validate);
        this.block = Objects.requireNonNull(block);
        this.before = Objects.requireNonNull(before);
        this.after = Objects.requireNonNull(after);
        this.clock = Objects.requireNonNull(clock);
        last = new StepStats(0, 0, total, 0, 0, 0, 0, false);
    }

    StepStats step(int maxBlocks, long maxNanos) {
        checkOwner();
        if (maxBlocks < 1 || maxNanos < 1) throw new IllegalArgumentException("Step limits must be positive");
        if (failed || cancelled || stepping) throw new IllegalStateException("Build is not resumable");
        if (complete()) throw new IllegalStateException("Build callbacks are already complete");
        stepping = true;
        long start = clock.getAsLong(), longest = 0;
        int initial = completed;
        Throwable failure = null;
        try {
            before.run();
            do {
                validate.run();
                if (cancelled) throw new IllegalStateException("Build was cancelled");
                long callbackStart = clock.getAsLong();
                try { block.accept(completed); }
                finally { longest = Math.max(longest, elapsed(callbackStart, clock.getAsLong())); }
                // A callback may invalidate its own resource/lease/appearance generation.
                validate.run();
                if (cancelled) throw new IllegalStateException("Build was cancelled during a callback");
                completed++;
            } while (completed < total && completed - initial < maxBlocks
                    && elapsed(start, clock.getAsLong()) < maxNanos);
        } catch (RuntimeException | Error error) { failure = error; failed = true; }
        finally {
            try { after.run(); }
            catch (RuntimeException | Error error) { failure = append(failure, error); failed = true; }
            long duration = elapsed(start, clock.getAsLong());
            long overshoot = duration > maxNanos ? duration - maxNanos : 0;
            last = new StepStats(completed - initial, completed, total, duration, maxNanos, overshoot,
                    longest, completed == total && !failed && !cancelled);
            steps++;
            totalNanos = saturatedAdd(totalNanos, duration);
            maxStepNanos = Math.max(maxStepNanos, duration);
            maxOvershootNanos = Math.max(maxOvershootNanos, overshoot);
            maxBlockNanos = Math.max(maxBlockNanos, longest);
            stepping = false;
        }
        rethrow(failure);
        return last;
    }

    void cancel() { checkOwner(); cancelled = true; }
    boolean complete() { checkOwner(); return completed == total && !failed && !cancelled; }
    int completed() { checkOwner(); return completed; }
    int total() { return total; }
    int steps() { checkOwner(); return steps; }
    long totalNanos() { checkOwner(); return totalNanos; }
    long maxStepNanos() { checkOwner(); return maxStepNanos; }
    long maxOvershootNanos() { checkOwner(); return maxOvershootNanos; }
    long maxBlockNanos() { checkOwner(); return maxBlockNanos; }
    StepStats last() { checkOwner(); return last; }
    boolean stepping() { checkOwner(); return stepping; }
    void checkOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Scene build requires its owner thread");
    }
    private static long elapsed(long start, long end) { return Math.max(0, end - start); }
    private static long saturatedAdd(long a, long b) { return Long.MAX_VALUE - a < b ? Long.MAX_VALUE : a + b; }
    static Throwable append(Throwable prior, Throwable next) {
        if (prior == null) return next;
        if (prior != next) prior.addSuppressed(next);
        return prior;
    }
    static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }
}
