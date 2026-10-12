package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Owner-thread, frame-sliced native mesh work with a fixed, bounded ownership journal.
 * The production owner slice packs legal phases while measured time remains. Each phase
 * keeps its current-generation fence and fixed ownership slot; pump() remains a one-operation
 * seam for compatibility and exact outcome comparison. No operation is preemptible.
 *
 * <p>The 2 ms default is a soft slice budget, not a frame deadline: a single model callback,
 * begin, finish/upload, publication or cleanup can exceed it. No native work is asynchronous.
 * The caller must reserve the entire native-generation budget before starting a batch.
 */
public final class RollingMeshWorkQueue<B, M> implements AutoCloseable {
    public static final int MAX_TARGETS = 28;
    public static final int MAX_CALLBACKS = 4096;
    public static final long SOFT_SLICE_NANOS = 2_000_000L;
    public static final int MAX_OPERATIONS_PER_SLICE = 128;

    /**
     * All methods run synchronously on the constructing thread. begin must expose no owned
     * native resource until its handle returns. step must honor the hard callback count and
     * soft time budget, and fence the native resource/dependency generation before and after
     * each model callback (as SceneMesh.Build does). finish retains all partial allocations in
     * the build if it throws; a successful return transfers those allocations to the mesh.
     * Both close methods must retain their own failed cleanup resources for a retry.
     */
    public interface Driver<B, M> {
        B begin(CubePos position);
        StepStats step(B build, int maxCallbacks, long softNanos);
        M finish(B build);
        void closeBuild(B build);
        void closeMesh(M mesh);
    }

    /** Mirrors SceneMesh.StepStats without depending on native classes. */
    public record StepStats(int blocks, int completedBlocks, int totalBlocks, long elapsedNanos,
                            long budgetNanos, long overshootNanos, long maxBlockNanos, boolean complete) {}

    public enum Phase { IDLE, BEGIN, STEP, FINISH, BUILD_CLOSE, PUBLICATION, WAITING_PUBLICATION }
    public enum StopReason { DRAINED, TIME_BUDGET, CALLBACK_LIMIT, OPERATION_LIMIT }
    public record SliceStats(Phase lastPhase, int operations, int callbacks, long elapsedNanos,
                             long budgetNanos, long overshootNanos, StopReason stopReason) {}
    public record OwnerSliceMetrics(Timing timing, int maxOperations, int maxCallbacks,
                                    long maxSoftOvershootNanos, long timeBudgetYields,
                                    long callbackLimitYields, long operationLimitYields) {}

    /** All timings are cumulative measured values; durations and counters saturate at Long.MAX_VALUE. */
    public record Timing(long calls, long totalNanos, long maxNanos) {}

    /**
     * Fixed-size diagnostics, independent of batch count and total callbacks. Callback counts
     * and per-model timing describe validated returned StepStats; a throwing step cannot return
     * these statistics, but its wall-clock slice duration and overshoot are still measured.
     */
    public record Metrics(long batches, long completedMeshes, long publishedMeshes,
                          long reportedCallbacks, int maxCallbacksPerSlice, long maxCallbackNanos,
                          long maxSoftOvershootNanos, int peakQueuedTargets, int peakActiveBuilds,
                          int peakCompletedMeshes, Timing begin, Timing slice, Timing finishUpload,
                          Timing publication, Timing buildClose, Timing meshClose, OwnerSliceMetrics ownerSlice) {}

    private final Thread owner = Thread.currentThread();
    private final Driver<B, M> driver;
    private final CubePos[] targets;
    private final int maxCallbacks, maxOperations;
    private final long softNanos;
    private final LongSupplier clock;
    private final Timer beginTime = new Timer(), sliceTime = new Timer(), finishTime = new Timer();
    private final Timer publishTime = new Timer(), buildCloseTime = new Timer(), meshCloseTime = new Timer();
    private final Timer ownerSliceTime = new Timer();
    private int operationCallbacks, maxOwnerSliceOperations, maxOwnerSliceCallbacks;
    private long maxOwnerSliceOvershoot, timeBudgetYields, callbackLimitYields, operationLimitYields;
    private int head, tail;
    private CubePos activePosition, completedPosition;
    private B activeBuild;
    private M completedMesh;
    private boolean activeComplete, poisoned, closed, operating, hasBatch;
    private BooleanSupplier stillCurrent;
    private long publication, resourceGeneration;
    private int completedCallbacks, totalCallbacks = -1;
    private long batches, completedMeshes, publishedMeshes, reportedCallbacks;
    private int maxCallbacksPerSlice, peakQueuedTargets, peakActiveBuilds, peakCompletedMeshes;
    private long maxCallbackNanos, maxSoftOvershootNanos;

    public RollingMeshWorkQueue(Driver<B, M> driver) {
        this(driver, MAX_TARGETS, MAX_CALLBACKS, SOFT_SLICE_NANOS, System::nanoTime);
    }

    /** Smaller limits and a deterministic clock permit pure failure-injection tests. */
    public RollingMeshWorkQueue(Driver<B, M> driver, int targetLimit, int maxCallbacks,
                                long softNanos, LongSupplier clock) {
        this(driver, targetLimit, maxCallbacks, softNanos, MAX_OPERATIONS_PER_SLICE, clock);
    }

    public RollingMeshWorkQueue(Driver<B, M> driver, int targetLimit, int maxCallbacks,
                                long softNanos, int maxOperations, LongSupplier clock) {
        if (targetLimit < 1 || targetLimit > MAX_TARGETS)
            throw new IllegalArgumentException("Target limit must be between 1 and 28");
        if (maxCallbacks < 1 || maxCallbacks > MAX_CALLBACKS || softNanos < 1)
            throw new IllegalArgumentException("Invalid callback or soft-time limit");
        if (maxOperations < 1 || maxOperations > MAX_OPERATIONS_PER_SLICE)
            throw new IllegalArgumentException("Invalid owner-slice operation limit");
        this.maxOperations = maxOperations;
        this.driver = Objects.requireNonNull(driver);
        this.targets = new CubePos[targetLimit];
        this.maxCallbacks = maxCallbacks;
        this.softNanos = softNanos;
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Starts only when fully drained. Publications strictly increase, including empty batches;
     * resource generations cannot decrease. The predicate must compare the captured publication,
     * resource generation, and session identity with the caller's current state, and must be
     * side-effect free. These rules prevent an old batch from becoming current again through ABA.
     */
    public void start(List<CubePos> positions, long publication, long resourceGeneration,
                      BooleanSupplier stillCurrent) {
        checkNormal();
        if (!drained()) throw new IllegalStateException("Previous mesh batch still owns work or resources");
        Objects.requireNonNull(positions);
        Objects.requireNonNull(stillCurrent);
        int size = positions.size();
        if (size > targets.length) throw new IllegalArgumentException("Too many cube targets");
        if (publication < 1 || resourceGeneration < 1
                || hasBatch && (publication <= this.publication || resourceGeneration < this.resourceGeneration))
            throw new IllegalArgumentException("Replayed publication or stale resource generation");
        // Validate the bounded input completely before changing the active batch.
        var copy = new CubePos[size];
        for (int i = 0; i < copy.length; i++) {
            copy[i] = Objects.requireNonNull(positions.get(i), "Null cube target");
            for (int j = 0; j < i; j++) if (copy[i].equals(copy[j]))
                throw new IllegalArgumentException("Duplicate cube target");
        }
        operating = true;
        try {
            this.publication = publication;
            this.resourceGeneration = resourceGeneration;
            this.stillCurrent = stillCurrent;
            hasBatch = true;
            requireCurrent();
            head = 0;
            tail = copy.length;
            System.arraycopy(copy, 0, targets, 0, copy.length);
            batches = increment(batches);
            peakQueuedTargets = Math.max(peakQueuedTargets, tail);
            requireCurrent();
        } catch (RuntimeException | Error failure) { poisoned = true; throw failure; }
        finally { operating = false; }
    }

    /** Performs one legacy operation; finish includes build cleanup. Does not consume a mesh. */
    public Phase pump() {
        checkNormal();
        operating = true;
        try {
            if (!drained()) requireCurrent();
            return advance(maxCallbacks, softNanos, false);
        }
        catch (RuntimeException | Error failure) { poisoned = true; throw failure; }
        finally { operating = false; }
    }

    /**
     * Call at most once per authoritative render frame. Every additional begin, callback step,
     * finish/upload, successful-build cleanup and publication gets a fresh elapsed-time check.
     * Callback steps receive only the remaining time and aggregate callback allowance. A finish
     * that exhausts the budget retains BOTH owners until cleanup can run in a later slice.
     * Indivisible operations may overshoot: timings include those overshoots, also on failure.
     */
    public SliceStats pumpSlice(BiConsumer<CubePos, M> consumer) {
        checkNormal();
        Objects.requireNonNull(consumer);
        long start = clock.getAsLong();
        operating = true;
        int operations = 0, callbacks = 0;
        Phase last = Phase.IDLE;
        StopReason stop = StopReason.DRAINED;
        long elapsed = 0;
        Throwable sliceFailure = null;
        try {
            while (!drained()) {
                requireCurrent();
                long remaining = softNanos - elapsedSince(start);
                if (remaining <= 0) { stop = StopReason.TIME_BUDGET; break; }
                if (operations >= maxOperations) { stop = StopReason.OPERATION_LIMIT; break; }
                // Finishing, retiring and publishing a completed build require no model callbacks.
                if (callbacks >= maxCallbacks && completedMesh == null && !activeComplete) {
                    stop = StopReason.CALLBACK_LIMIT; break;
                }
                operations++;
                if (completedMesh != null && activeBuild == null) {
                    publishOwned(consumer, false);
                    last = Phase.PUBLICATION;
                } else {
                    last = advance(maxCallbacks - callbacks, remaining, true);
                    callbacks += operationCallbacks;
                }
            }
        } catch (RuntimeException | Error failure) { poisoned = true; sliceFailure = failure; throw failure; }
        finally {
            try {
                elapsed = ownerSliceTime.record(start);
                maxOwnerSliceOperations = Math.max(maxOwnerSliceOperations, operations);
                maxOwnerSliceCallbacks = Math.max(maxOwnerSliceCallbacks, callbacks);
                maxOwnerSliceOvershoot = Math.max(maxOwnerSliceOvershoot, overshoot(elapsed, softNanos));
            } catch (RuntimeException | Error failure) {
                poisoned = true;
                if (sliceFailure == null) throw failure;
                append(sliceFailure, failure);
            } finally { operating = false; }
        }
        switch (stop) {
            case TIME_BUDGET -> timeBudgetYields = increment(timeBudgetYields);
            case CALLBACK_LIMIT -> callbackLimitYields = increment(callbackLimitYields);
            case OPERATION_LIMIT -> operationLimitYields = increment(operationLimitYields);
            default -> { }
        }
        return new SliceStats(last, operations, callbacks, elapsed, softNanos, overshoot(elapsed, softNanos), stop);
    }

    private Phase advance(int callbackLimit, long budgetNanos, boolean deferredCleanup) {
        operationCallbacks = 0;
        if (drained()) return Phase.IDLE;
        if (completedMesh != null) {
            if (activeBuild == null) return Phase.WAITING_PUBLICATION;
            closeActiveBuild();
            requireCurrent();
            return Phase.BUILD_CLOSE;
        }
        if (activeBuild == null) {
            CubePos next = targets[head];
            long start = clock.getAsLong();
            Throwable failure = null;
            try {
                // Retain before any fallible post-check, clock read or metrics work.
                activeBuild = Objects.requireNonNull(driver.begin(next), "Driver returned no build owner");
                activePosition = next;
                targets[head++] = null;
                peakActiveBuilds = 1;
                activeComplete = false;
                completedCallbacks = 0;
                totalCallbacks = -1;
            } catch (RuntimeException | Error error) { failure = error; throw error; }
            finally { recordPhase(beginTime, start, failure); }
            requireCurrent();
            return Phase.BEGIN;
        }
        if (!activeComplete) {
            long start = clock.getAsLong();
            StepStats stats;
            Throwable failure = null;
            try { stats = driver.step(activeBuild, callbackLimit, budgetNanos); }
            catch (RuntimeException | Error error) { failure = error; throw error; }
            finally {
                long elapsed = recordPhase(sliceTime, start, failure);
                maxSoftOvershootNanos = Math.max(maxSoftOvershootNanos, overshoot(elapsed, budgetNanos));
            }
            requireCurrent();
            acceptStats(Objects.requireNonNull(stats, "Driver returned no step statistics"), callbackLimit, budgetNanos);
            return Phase.STEP;
        }
        long start = clock.getAsLong();
        Throwable failure = null;
        try {
            // finish transfers GPU ownership at return: retain its result immediately.
            completedMesh = Objects.requireNonNull(driver.finish(activeBuild), "Driver returned no mesh owner");
            completedPosition = activePosition;
            completedMeshes = increment(completedMeshes);
            peakCompletedMeshes = 1;
        } catch (RuntimeException | Error error) { failure = error; throw error; }
        finally { recordPhase(finishTime, start, failure); }
        requireCurrent();
        if (!deferredCleanup) {
            closeActiveBuild();
            requireCurrent();
        }
        return Phase.FINISH;
    }

    /**
     * Transfers the retained slot only after the consumer and the post-publication fence succeed.
     * A throwing consumer must not destroy the mesh; this queue retains cleanup ownership. If a
     * consumer records the owner before throwing or invalidating the fence, its own cleanup must
     * tolerate the queue also retaining that owner (SceneMesh.close is success-idempotent).
     */
    public boolean publishCompleted(BiConsumer<CubePos, M> consumer) {
        checkNormal();
        Objects.requireNonNull(consumer);
        operating = true;
        try { return publishOwned(consumer, true); }
        catch (RuntimeException | Error failure) { poisoned = true; throw failure; }
        finally { operating = false; }
    }

    private boolean publishOwned(BiConsumer<CubePos, M> consumer, boolean checkCurrent) {
        // Adaptive finish can defer build retirement; do not transfer its mesh prematurely.
        if (completedMesh == null || activeBuild != null) return false;
        if (checkCurrent) requireCurrent();
        long start = clock.getAsLong();
        Throwable failure = null;
        try { consumer.accept(completedPosition, completedMesh); }
        catch (RuntimeException | Error error) { failure = error; throw error; }
        finally { recordPhase(publishTime, start, failure); }
        requireCurrent();
        completedMesh = null;
        completedPosition = null;
        publishedMeshes = increment(publishedMeshes);
        return true;
    }

    private void acceptStats(StepStats value, int callbackLimit, long budgetNanos) {
        if (value.blocks() < 0 || value.blocks() > callbackLimit || value.completedBlocks() < 0
                || value.totalBlocks() < 0 || value.completedBlocks() > value.totalBlocks()
                || value.completedBlocks() - completedCallbacks != value.blocks()
                || totalCallbacks != -1 && value.totalBlocks() != totalCallbacks
                || value.complete() != (value.completedBlocks() == value.totalBlocks())
                || value.blocks() == 0 && !value.complete()
                || value.elapsedNanos() < 0 || value.budgetNanos() != budgetNanos
                || value.overshootNanos() != overshoot(value.elapsedNanos(), value.budgetNanos())
                || value.maxBlockNanos() < 0 || value.maxBlockNanos() > value.elapsedNanos())
            throw new IllegalStateException("Driver violated its bounded callback/statistics contract");
        operationCallbacks = value.blocks();
        completedCallbacks = value.completedBlocks();
        totalCallbacks = value.totalBlocks();
        activeComplete = value.complete();
        reportedCallbacks = add(reportedCallbacks, value.blocks());
        maxCallbacksPerSlice = Math.max(maxCallbacksPerSlice, value.blocks());
        maxCallbackNanos = Math.max(maxCallbackNanos, value.maxBlockNanos());
        maxSoftOvershootNanos = Math.max(maxSoftOvershootNanos, value.overshootNanos());
    }

    /**
     * Permanently stops normal work. Cleanup ignores an expired publication fence by design,
     * attempts BOTH retained owners even when one fails, and discards only successful closes.
     * Retry close on the owner thread until isDrained is true. No reset can hide failed owners.
     */
    @Override public void close() {
        checkOwner();
        closed = true;
        poisoned = true;
        // Reentrant cancellation may fence work but must not free an active native writer or
        // mutate the in-progress queue cursor before a just-created owner can be retained.
        if (operating) throw new IllegalStateException("Retry cleanup after the active callback returns");
        for (int i = head; i < tail; i++) targets[i] = null;
        head = tail = 0;
        operating = true;
        Throwable failure = null;
        try {
            try { closeActiveBuild(); }
            catch (RuntimeException | Error error) { failure = error; }
            try {
                if (completedMesh != null) {
                    long start = clock.getAsLong();
                    try {
                        driver.closeMesh(completedMesh);
                        completedMesh = null;
                        completedPosition = null;
                    } finally { meshCloseTime.record(start); }
                }
            } catch (RuntimeException | Error error) { failure = append(failure, error); }
        } finally { operating = false; }
        rethrow(failure);
    }

    private void closeActiveBuild() {
        if (activeBuild == null) return;
        long start = clock.getAsLong();
        Throwable failure = null;
        try {
            driver.closeBuild(activeBuild);
            activeBuild = null;
            activePosition = null;
            activeComplete = false;
        } catch (RuntimeException | Error error) { failure = error; throw error; }
        finally { recordPhase(buildCloseTime, start, failure); }
    }

    private void requireCurrent() {
        checkOwner();
        if (closed || poisoned || stillCurrent == null || !stillCurrent.getAsBoolean() || closed || poisoned)
            throw new IllegalStateException("Mesh publication/resource generation is no longer current");
    }

    private void checkNormal() {
        checkOwner();
        if (operating) { poisoned = true; throw new IllegalStateException("Reentrant mesh queue operation"); }
        if (closed || poisoned) throw new IllegalStateException("Mesh queue is closed or poisoned; cleanup is required");
    }

    private void checkOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Mesh queue requires its owner thread");
    }

    private boolean drained() { return head == tail && activeBuild == null && completedMesh == null; }
    public boolean isDrained() { checkOwner(); return drained(); }
    public boolean isPoisoned() { checkOwner(); return poisoned; }
    public int queuedTargets() { checkOwner(); return tail - head; }
    public int activeBuilds() { checkOwner(); return activeBuild == null ? 0 : 1; }
    public int completedSlots() { checkOwner(); return completedMesh == null ? 0 : 1; }
    public long publication() { checkOwner(); return publication; }
    public long resourceGeneration() { checkOwner(); return resourceGeneration; }
    public Metrics metrics() {
        checkOwner();
        return new Metrics(batches, completedMeshes, publishedMeshes, reportedCallbacks,
                maxCallbacksPerSlice, maxCallbackNanos, maxSoftOvershootNanos, peakQueuedTargets,
                peakActiveBuilds, peakCompletedMeshes, beginTime.snapshot(), sliceTime.snapshot(),
                finishTime.snapshot(), publishTime.snapshot(), buildCloseTime.snapshot(), meshCloseTime.snapshot(),
                new OwnerSliceMetrics(ownerSliceTime.snapshot(), maxOwnerSliceOperations, maxOwnerSliceCallbacks,
                        maxOwnerSliceOvershoot, timeBudgetYields, callbackLimitYields, operationLimitYields));
    }

    private long recordPhase(Timer timer, long start, Throwable prior) {
        try { return timer.record(start); }
        catch (RuntimeException | Error error) {
            if (prior == null) throw error;
            append(prior, error);
            return 0; // No elapsed measurement is available; the failed timer records no sample.
        }
    }

    private long elapsedSince(long start) { return Math.max(0, clock.getAsLong() - start); }

    private final class Timer {
        long calls, total, max;
        long record(long start) {
            long elapsed = elapsedSince(start);
            calls = increment(calls);
            total = add(total, elapsed);
            max = Math.max(max, elapsed);
            return elapsed;
        }
        Timing snapshot() { return new Timing(calls, total, max); }
    }
    private static long overshoot(long duration, long budget) { return duration > budget ? duration - budget : 0; }
    private static long increment(long value) { return value == Long.MAX_VALUE ? value : value + 1; }
    private static long add(long a, long b) { return Long.MAX_VALUE - a < b ? Long.MAX_VALUE : a + b; }
    private static Throwable append(Throwable prior, Throwable next) {
        if (prior == null) return next;
        if (prior != next) prior.addSuppressed(next);
        return prior;
    }
    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }
}
