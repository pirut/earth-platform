package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Owner-thread, frame-sliced native mesh work with a fixed, bounded ownership journal.
 * One pump performs only begin, one callback slice, or finish/upload. Finish never runs on
 * the frame which reports the last callback. Published results must be consumed explicitly.
 *
 * <p>The 2 ms default is a soft slice budget, not a frame deadline: a single model callback,
 * begin, finish/upload, publication or cleanup can exceed it. No native work is asynchronous.
 * The caller must reserve the entire native-generation budget before starting a batch.
 */
public final class RollingMeshWorkQueue<B, M> implements AutoCloseable {
    public static final int MAX_TARGETS = 28;
    public static final int MAX_CALLBACKS = 4096;
    public static final long SOFT_SLICE_NANOS = 2_000_000L;

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

    public enum Phase { IDLE, BEGIN, STEP, FINISH, WAITING_PUBLICATION }

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
                          Timing publication, Timing buildClose, Timing meshClose) {}

    private final Thread owner = Thread.currentThread();
    private final Driver<B, M> driver;
    private final CubePos[] targets;
    private final int maxCallbacks;
    private final long softNanos;
    private final LongSupplier clock;
    private final Timer beginTime = new Timer(), sliceTime = new Timer(), finishTime = new Timer();
    private final Timer publishTime = new Timer(), buildCloseTime = new Timer(), meshCloseTime = new Timer();
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
        if (targetLimit < 1 || targetLimit > MAX_TARGETS)
            throw new IllegalArgumentException("Target limit must be between 1 and 28");
        if (maxCallbacks < 1 || maxCallbacks > MAX_CALLBACKS || softNanos < 1)
            throw new IllegalArgumentException("Invalid callback or soft-time limit");
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

    /** Call at most once per render frame. Never loops into the next phase or next cube. */
    public Phase pump() {
        checkNormal();
        operating = true;
        try {
            if (drained()) return Phase.IDLE;
            requireCurrent();
            if (completedMesh != null) return Phase.WAITING_PUBLICATION;
            if (activeBuild == null) {
                CubePos next = targets[head];
                long start = clock.getAsLong();
                try {
                    // Retain before any fallible post-check, clock read or metrics work.
                    activeBuild = Objects.requireNonNull(driver.begin(next), "Driver returned no build owner");
                    activePosition = next;
                    targets[head++] = null;
                    peakActiveBuilds = 1;
                    activeComplete = false;
                    completedCallbacks = 0;
                    totalCallbacks = -1;
                } finally { beginTime.record(start); }
                requireCurrent();
                return Phase.BEGIN;
            }
            if (!activeComplete) {
                long start = clock.getAsLong();
                StepStats stats;
                try { stats = driver.step(activeBuild, maxCallbacks, softNanos); }
                finally {
                    long elapsed = sliceTime.record(start);
                    maxSoftOvershootNanos = Math.max(maxSoftOvershootNanos, overshoot(elapsed, softNanos));
                }
                requireCurrent();
                acceptStats(Objects.requireNonNull(stats, "Driver returned no step statistics"));
                return Phase.STEP;
            }
            long start = clock.getAsLong();
            try {
                // finish transfers GPU ownership at return: retain its result immediately.
                completedMesh = Objects.requireNonNull(driver.finish(activeBuild), "Driver returned no mesh owner");
                completedPosition = activePosition;
                completedMeshes = increment(completedMeshes);
                peakCompletedMeshes = 1;
            } finally { finishTime.record(start); }
            requireCurrent();
            closeActiveBuild();
            requireCurrent();
            return Phase.FINISH;
        } catch (RuntimeException | Error failure) { poisoned = true; throw failure; }
        finally { operating = false; }
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
        try {
            if (completedMesh == null) return false;
            requireCurrent();
            long start = clock.getAsLong();
            try { consumer.accept(completedPosition, completedMesh); }
            finally { publishTime.record(start); }
            requireCurrent();
            completedMesh = null;
            completedPosition = null;
            publishedMeshes = increment(publishedMeshes);
            return true;
        } catch (RuntimeException | Error failure) { poisoned = true; throw failure; }
        finally { operating = false; }
    }

    private void acceptStats(StepStats value) {
        if (value.blocks() < 0 || value.blocks() > maxCallbacks || value.completedBlocks() < 0
                || value.totalBlocks() < 0 || value.completedBlocks() > value.totalBlocks()
                || value.completedBlocks() - completedCallbacks != value.blocks()
                || totalCallbacks != -1 && value.totalBlocks() != totalCallbacks
                || value.complete() != (value.completedBlocks() == value.totalBlocks())
                || value.blocks() == 0 && !value.complete()
                || value.elapsedNanos() < 0 || value.budgetNanos() != softNanos
                || value.overshootNanos() != overshoot(value.elapsedNanos(), value.budgetNanos())
                || value.maxBlockNanos() < 0 || value.maxBlockNanos() > value.elapsedNanos())
            throw new IllegalStateException("Driver violated its bounded callback/statistics contract");
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
        try {
            driver.closeBuild(activeBuild);
            activeBuild = null;
            activePosition = null;
            activeComplete = false;
        } finally { buildCloseTime.record(start); }
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
                finishTime.snapshot(), publishTime.snapshot(), buildCloseTime.snapshot(), meshCloseTime.snapshot());
    }

    private final class Timer {
        long calls, total, max;
        long record(long start) {
            long elapsed = Math.max(0, clock.getAsLong() - start);
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
