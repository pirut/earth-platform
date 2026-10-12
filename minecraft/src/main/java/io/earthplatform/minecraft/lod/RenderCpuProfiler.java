package io.earthplatform.minecraft.lod;

import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Optional owner-thread elapsed CPU-side stage timings, with bounded completed-frame history.
 * Stages may include or overlap other stages: their times must not be summed. These are not
 * complete frame, FPS, GPU execution, or thread CPU-consumption measurements.
 *
 * <p>The caller owns the warm-up decision and must pair beginFrame/endFrame and start/finish
 * using finally blocks, including failed work. A finish is attributed to the frame open when
 * it finishes; initialization outside any frame affects lifetime/unframed totals only.
 */
public final class RenderCpuProfiler {
    public enum Stage {
        FRAME_UPDATE, NEAR_PLAN, NEAR_SOURCE, NATIVE_MESH, NATIVE_RETIRE,
        FAR_PUMP, FAR_COVERAGE, FAR_DRAW, NATIVE_DRAW,
        CPU_TILE_DIFF, OFFICIAL_DH_BUILDER, GL_BUFFER_UPLOAD, GL_RETIREMENT,
        GL_STATE_CAPTURE, GL_STATE_RESTORE, FAR_INITIALIZE, REPORT_IO
    }

    private static final int FRAME_CAPACITY = 512;
    private static final Stage[] STAGES = Stage.values();
    private final boolean enabled;
    private final Thread owner;
    private final LongSupplier clock;
    private final StageStats[] stages;
    private final boolean[] warmFrames;
    private boolean frameOpen;
    private boolean frameWarmed;
    private int nextFrame;
    private int retainedFrames;
    private long completedFrames;
    private long coldCompletedFrames;
    private long warmCompletedFrames;
    private boolean countersSaturated;

    public RenderCpuProfiler(boolean enabled) {
        this(enabled, System::nanoTime);
    }

    RenderCpuProfiler(boolean enabled, LongSupplier clock) {
        this.enabled = enabled;
        this.clock = Objects.requireNonNull(clock, "clock");
        owner = enabled ? Thread.currentThread() : null;
        // The opt-out path does not allocate the stage histories either.
        stages = enabled ? new StageStats[STAGES.length] : null;
        warmFrames = enabled ? new boolean[FRAME_CAPACITY] : null;
        if (enabled) {
            for (int i = 0; i < stages.length; i++) stages[i] = new StageStats();
        }
    }

    public long start() {
        if (!enabled) return 0L;
        assertOwner();
        return clock.getAsLong();
    }

    public void finish(Stage stage, long started) {
        if (!enabled) return;
        assertOwner();
        StageStats stats = stages[Objects.requireNonNull(stage, "stage").ordinal()];
        // Subtract before interpreting the result: nanoTime's origin may be negative and its
        // signed representation may wrap during a valid duration shorter than 2^63 ns.
        long elapsed = clock.getAsLong() - started;
        if (elapsed < 0L) throw new IllegalArgumentException("Negative stage duration; invalid clock or start token");
        stats.invocations = add(stats.invocations, 1L);
        stats.totalNanos = add(stats.totalNanos, elapsed);
        stats.maxNanos = Math.max(stats.maxNanos, elapsed);
        if (frameOpen) {
            stats.frameNanos = add(stats.frameNanos, elapsed);
        } else {
            stats.unframedInvocations = add(stats.unframedInvocations, 1L);
            stats.unframedTotalNanos = add(stats.unframedTotalNanos, elapsed);
            stats.unframedMaxNanos = Math.max(stats.unframedMaxNanos, elapsed);
        }
    }

    /** warmed is supplied by the caller, normally after the first complete paired draw. */
    public void beginFrame(boolean warmed) {
        if (!enabled) return;
        assertOwner();
        if (frameOpen) throw new IllegalStateException("A profiling frame is already open");
        frameOpen = true;
        frameWarmed = warmed;
        for (StageStats stats : stages) stats.frameNanos = 0L;
    }

    /** Call in the frame's finally block so partial/failed frames are retained too. */
    public void endFrame() {
        if (!enabled) return;
        assertOwner();
        if (!frameOpen) throw new IllegalStateException("No profiling frame is open");
        for (StageStats stats : stages) {
            stats.frames[nextFrame] = stats.frameNanos;
            if (frameWarmed) stats.warmFrameMaxNanos = Math.max(stats.warmFrameMaxNanos, stats.frameNanos);
            else stats.coldFrameMaxNanos = Math.max(stats.coldFrameMaxNanos, stats.frameNanos);
        }
        warmFrames[nextFrame] = frameWarmed;
        nextFrame = (nextFrame + 1) % FRAME_CAPACITY;
        retainedFrames = Math.min(retainedFrames + 1, FRAME_CAPACITY);
        completedFrames = add(completedFrames, 1L);
        if (frameWarmed) warmCompletedFrames = add(warmCompletedFrames, 1L);
        else coldCompletedFrames = add(coldCompletedFrames, 1L);
        frameOpen = false;
    }

    /** An owner-thread snapshot. Diagnostics do not read the clock or close an active frame. */
    public JsonObject diagnostics() {
        if (enabled) assertOwner();
        JsonObject result = new JsonObject();
        result.addProperty("enabled", enabled);
        result.addProperty("measurement", "owner-thread CPU-side elapsed wall-clock nanoseconds");
        result.addProperty("warning", "Stages are inclusive/nested: do not sum them or treat them as full frame, FPS, GPU duration, or thread CPU consumption.");
        result.addProperty("warmup", "Caller-controlled per frame; normally true after the first complete paired draw.");
        result.addProperty("percentiles", "Nearest rank over the latest completed frames, including zero-valued frames; cold/warm subsets share the same ring.");
        result.addProperty("frameCapacity", FRAME_CAPACITY);
        result.addProperty("retainedFrames", retainedFrames);
        result.addProperty("completedFrames", completedFrames);
        result.addProperty("coldCompletedFrames", coldCompletedFrames);
        result.addProperty("warmCompletedFrames", warmCompletedFrames);
        result.addProperty("frameOpen", frameOpen);
        result.addProperty("countersSaturated", countersSaturated);
        JsonObject stageResults = new JsonObject();
        if (enabled) {
            for (Stage stage : STAGES) {
                StageStats stats = stages[stage.ordinal()];
                JsonObject summary = new JsonObject();
                summary.addProperty("invocations", stats.invocations);
                summary.addProperty("totalNanos", stats.totalNanos);
                summary.addProperty("maxNanos", stats.maxNanos);
                summary.addProperty("unframedInvocations", stats.unframedInvocations);
                summary.addProperty("unframedTotalNanos", stats.unframedTotalNanos);
                summary.addProperty("unframedMaxNanos", stats.unframedMaxNanos);
                summary.addProperty("coldFrameMaxNanos", stats.coldFrameMaxNanos);
                summary.addProperty("warmFrameMaxNanos", stats.warmFrameMaxNanos);
                summary.add("recentFrames", distribution(stats, null));
                summary.add("recentColdFrames", distribution(stats, false));
                summary.add("recentWarmFrames", distribution(stats, true));
                stageResults.add(stage.name(), summary);
            }
        }
        result.add("stages", stageResults);
        return result;
    }

    private JsonObject distribution(StageStats stats, Boolean warmed) {
        long[] values = new long[retainedFrames];
        int count = 0;
        // Before the first wrap, slots [0, retainedFrames) are valid; after it all slots are.
        for (int i = 0; i < retainedFrames; i++) {
            if (warmed == null || warmFrames[i] == warmed) values[count++] = stats.frames[i];
        }
        Arrays.sort(values, 0, count);
        JsonObject result = new JsonObject();
        result.addProperty("count", count);
        result.addProperty("p50Nanos", percentile(values, count, 50));
        result.addProperty("p95Nanos", percentile(values, count, 95));
        result.addProperty("p99Nanos", percentile(values, count, 99));
        result.addProperty("maxNanos", count == 0 ? 0L : values[count - 1]);
        return result;
    }

    private static long percentile(long[] sorted, int count, int percent) {
        return count == 0 ? 0L : sorted[(count * percent + 99) / 100 - 1];
    }

    private long add(long current, long amount) {
        if (Long.MAX_VALUE - current < amount) {
            countersSaturated = true;
            return Long.MAX_VALUE;
        }
        return current + amount;
    }

    private void assertOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("CPU profiler accessed outside its owner thread");
    }

    private static final class StageStats {
        final long[] frames = new long[FRAME_CAPACITY];
        long invocations;
        long totalNanos;
        long maxNanos;
        long unframedInvocations;
        long unframedTotalNanos;
        long unframedMaxNanos;
        long frameNanos;
        long coldFrameMaxNanos;
        long warmFrameMaxNanos;
    }
}
