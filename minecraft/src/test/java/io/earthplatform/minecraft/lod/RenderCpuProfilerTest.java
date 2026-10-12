package io.earthplatform.minecraft.lod;

import com.google.gson.JsonObject;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import static io.earthplatform.minecraft.lod.RenderCpuProfiler.Stage.*;
import static org.junit.jupiter.api.Assertions.*;

class RenderCpuProfilerTest {
    private static final class Clock implements LongSupplier {
        long now;
        int reads;
        @Override public long getAsLong() { reads++; return now; }
        void advance(long nanos) { now += nanos; }
    }

    private static JsonObject stage(RenderCpuProfiler profiler, RenderCpuProfiler.Stage stage) {
        return profiler.diagnostics().getAsJsonObject("stages").getAsJsonObject(stage.name());
    }

    private static long number(JsonObject object, String key) { return object.get(key).getAsLong(); }

    private static void record(RenderCpuProfiler profiler, Clock clock, RenderCpuProfiler.Stage stage, long nanos) {
        long start = profiler.start();
        clock.advance(nanos);
        profiler.finish(stage, start);
    }

    private static Throwable onOtherThread(Runnable action) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try { action.run(); }
            catch (Throwable thrown) { failure.set(thrown); }
        });
        thread.start();
        thread.join();
        return failure.get();
    }

    @Test void disabledCallsNeverReadTheClockOrAssertOwnershipOrRequireFramePairing() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        RenderCpuProfiler profiler = new RenderCpuProfiler(false, () -> {
            reads.incrementAndGet();
            throw new AssertionError("disabled profiler read its clock");
        });
        assertNull(onOtherThread(() -> {
            assertEquals(0L, profiler.start());
            profiler.finish(null, Long.MIN_VALUE);
            profiler.endFrame();
            profiler.beginFrame(false);
            profiler.beginFrame(true);
            profiler.endFrame();
            JsonObject diagnostics = profiler.diagnostics();
            assertFalse(diagnostics.get("enabled").getAsBoolean());
            assertFalse(diagnostics.get("frameOpen").getAsBoolean());
            assertEquals(0, number(diagnostics, "completedFrames"));
            assertTrue(diagnostics.getAsJsonObject("stages").isEmpty());
        }));
        assertEquals(0, reads.get());
    }

    @Test void enabledApiRejectsEveryOffOwnerOperationBeforeReadingClockOrMutatingState() throws Exception {
        Clock clock = new Clock();
        RenderCpuProfiler profiler = new RenderCpuProfiler(true, clock);
        assertInstanceOf(IllegalStateException.class, onOtherThread(profiler::start));
        assertInstanceOf(IllegalStateException.class, onOtherThread(() -> profiler.finish(NEAR_PLAN, 0L)));
        assertInstanceOf(IllegalStateException.class, onOtherThread(() -> profiler.beginFrame(false)));
        assertInstanceOf(IllegalStateException.class, onOtherThread(profiler::endFrame));
        assertInstanceOf(IllegalStateException.class, onOtherThread(profiler::diagnostics));
        assertEquals(0, clock.reads);
        assertEquals(0, number(profiler.diagnostics(), "completedFrames"));
        profiler.beginFrame(false);
        record(profiler, clock, NEAR_PLAN, 12L);
        profiler.endFrame();
        assertEquals(12L, number(stage(profiler, NEAR_PLAN), "coldFrameMaxNanos"));
    }

    @Test void initializationHasSeparateUnframedTotalsAndDoesNotPolluteFrameStatistics() {
        Clock clock = new Clock();
        RenderCpuProfiler profiler = new RenderCpuProfiler(true, clock);
        record(profiler, clock, FAR_INITIALIZE, 200L);
        record(profiler, clock, FAR_INITIALIZE, 50L);
        profiler.beginFrame(false);
        record(profiler, clock, FAR_INITIALIZE, 3L);
        profiler.endFrame();
        JsonObject stage = stage(profiler, FAR_INITIALIZE);
        assertEquals(3L, number(stage, "invocations"));
        assertEquals(253L, number(stage, "totalNanos"));
        assertEquals(200L, number(stage, "maxNanos"));
        assertEquals(2L, number(stage, "unframedInvocations"));
        assertEquals(250L, number(stage, "unframedTotalNanos"));
        assertEquals(200L, number(stage, "unframedMaxNanos"));
        assertEquals(3L, number(stage, "coldFrameMaxNanos"));
        assertEquals(3L, number(stage.getAsJsonObject("recentFrames"), "p99Nanos"));
    }

    @Test void repeatedInvocationsAggregateWithinAFrameAndZeroFramesAreIncluded() {
        Clock clock = new Clock();
        RenderCpuProfiler profiler = new RenderCpuProfiler(true, clock);
        profiler.beginFrame(false);
        record(profiler, clock, NEAR_SOURCE, 10L);
        record(profiler, clock, NEAR_SOURCE, 15L);
        profiler.endFrame();
        for (int i = 0; i < 99; i++) {
            profiler.beginFrame(true);
            profiler.endFrame();
        }
        JsonObject stage = stage(profiler, NEAR_SOURCE);
        assertEquals(2L, number(stage, "invocations"));
        assertEquals(25L, number(stage, "totalNanos"));
        assertEquals(15L, number(stage, "maxNanos"));
        assertEquals(25L, number(stage, "coldFrameMaxNanos"));
        assertEquals(0L, number(stage, "warmFrameMaxNanos"));
        JsonObject recent = stage.getAsJsonObject("recentFrames");
        assertEquals(100, number(recent, "count"));
        assertEquals(0L, number(recent, "p50Nanos"));
        assertEquals(0L, number(recent, "p95Nanos"));
        assertEquals(0L, number(recent, "p99Nanos"));
        assertEquals(25L, number(recent, "maxNanos"));
        assertEquals(99, number(stage.getAsJsonObject("recentWarmFrames"), "count"));
        assertEquals(100, number(stage(profiler, GL_BUFFER_UPLOAD).getAsJsonObject("recentFrames"), "count"));
    }

    @Test void diagnosticsDuringAFrameExposeCompletedInvocationsButOnlyCompletedFrameSamples() {
        Clock clock = new Clock();
        RenderCpuProfiler profiler = new RenderCpuProfiler(true, clock);
        profiler.beginFrame(false);
        record(profiler, clock, FRAME_UPDATE, 7L);
        profiler.endFrame();
        profiler.beginFrame(true);
        record(profiler, clock, FRAME_UPDATE, 90L);
        JsonObject active = profiler.diagnostics();
        assertTrue(active.get("frameOpen").getAsBoolean());
        assertEquals(1L, number(active, "completedFrames"));
        JsonObject stage = stage(profiler, FRAME_UPDATE);
        assertEquals(97L, number(stage, "totalNanos"));
        assertEquals(0L, number(stage, "warmFrameMaxNanos"));
        assertEquals(7L, number(stage.getAsJsonObject("recentFrames"), "maxNanos"));
        assertEquals(0, number(stage.getAsJsonObject("recentWarmFrames"), "count"));
        int reads = clock.reads;
        profiler.endFrame();
        JsonObject ended = stage(profiler, FRAME_UPDATE);
        assertEquals(90L, number(ended, "warmFrameMaxNanos"));
        assertEquals(2, number(ended.getAsJsonObject("recentFrames"), "count"));
        assertEquals(reads, clock.reads, "frame bookkeeping and snapshots must not read the clock");
    }

    @Test void nearestRankPercentilesAndCallerControlledWarmColdClassificationAreExact() {
        Clock clock = new Clock();
        RenderCpuProfiler profiler = new RenderCpuProfiler(true, clock);
        for (int i = 1; i <= 100; i++) {
            profiler.beginFrame(i > 50);
            record(profiler, clock, NATIVE_MESH, i);
            profiler.endFrame();
        }
        JsonObject stage = stage(profiler, NATIVE_MESH);
        JsonObject recent = stage.getAsJsonObject("recentFrames");
        assertEquals(50L, number(recent, "p50Nanos"));
        assertEquals(95L, number(recent, "p95Nanos"));
        assertEquals(99L, number(recent, "p99Nanos"));
        JsonObject cold = stage.getAsJsonObject("recentColdFrames");
        assertEquals(50, number(cold, "count"));
        assertEquals(25L, number(cold, "p50Nanos"));
        assertEquals(48L, number(cold, "p95Nanos"));
        assertEquals(50L, number(cold, "p99Nanos"));
        JsonObject warm = stage.getAsJsonObject("recentWarmFrames");
        assertEquals(50, number(warm, "count"));
        assertEquals(75L, number(warm, "p50Nanos"));
        assertEquals(98L, number(warm, "p95Nanos"));
        assertEquals(100L, number(warm, "p99Nanos"));
        assertEquals(50L, number(stage, "coldFrameMaxNanos"));
        assertEquals(100L, number(stage, "warmFrameMaxNanos"));
        // The profiler never makes warm-up sticky or substitutes an inferred warm state.
        profiler.beginFrame(false);
        record(profiler, clock, NATIVE_MESH, 150L);
        profiler.endFrame();
        assertEquals(150L, number(stage(profiler, NATIVE_MESH), "coldFrameMaxNanos"));
        assertEquals(51L, number(profiler.diagnostics(), "coldCompletedFrames"));
    }

    @Test void exactlyTheLatest512CompletedFramesAreRetainedAndLifetimeMaximaSurviveEviction() {
        Clock clock = new Clock();
        RenderCpuProfiler profiler = new RenderCpuProfiler(true, clock);
        for (int i = 1; i <= 1024; i++) {
            profiler.beginFrame(i > 256);
            record(profiler, clock, FAR_DRAW, i);
            profiler.endFrame();
        }
        JsonObject diagnostics = profiler.diagnostics();
        assertEquals(512, number(diagnostics, "frameCapacity"));
        assertEquals(512, number(diagnostics, "retainedFrames"));
        assertEquals(1024L, number(diagnostics, "completedFrames"));
        assertEquals(256L, number(diagnostics, "coldCompletedFrames"));
        assertEquals(768L, number(diagnostics, "warmCompletedFrames"));
        JsonObject stage = stage(profiler, FAR_DRAW);
        assertEquals(1024L, number(stage, "invocations"));
        assertEquals(524800L, number(stage, "totalNanos"));
        assertEquals(256L, number(stage, "coldFrameMaxNanos"));
        JsonObject recent = stage.getAsJsonObject("recentFrames");
        assertEquals(512, number(recent, "count"));
        assertEquals(768L, number(recent, "p50Nanos"));
        assertEquals(999L, number(recent, "p95Nanos"));
        assertEquals(1019L, number(recent, "p99Nanos"));
        assertEquals(0, number(stage.getAsJsonObject("recentColdFrames"), "count"));
        assertEquals(0L, number(stage.getAsJsonObject("recentColdFrames"), "maxNanos"));
        assertEquals(512, number(stage.getAsJsonObject("recentWarmFrames"), "count"));
        assertEquals(RenderCpuProfiler.Stage.values().length, diagnostics.getAsJsonObject("stages").size());
        assertTrue(diagnostics.toString().length() < 20000, "diagnostics must not serialize unbounded history");
    }

    @Test void evictedSlotsAreOverwrittenWithZeroEvenWhenAStageDoesNotRunAgain() {
        Clock clock = new Clock();
        RenderCpuProfiler profiler = new RenderCpuProfiler(true, clock);
        profiler.beginFrame(true);
        record(profiler, clock, NATIVE_RETIRE, 60L);
        profiler.endFrame();
        for (int i = 0; i < 512; i++) {
            profiler.beginFrame(i % 2 == 0);
            profiler.endFrame();
        }
        JsonObject stage = stage(profiler, NATIVE_RETIRE);
        assertEquals(60L, number(stage, "warmFrameMaxNanos"));
        assertEquals(0L, number(stage.getAsJsonObject("recentFrames"), "maxNanos"));
        assertEquals(256, number(stage.getAsJsonObject("recentWarmFrames"), "count"));
        assertEquals(256, number(stage.getAsJsonObject("recentColdFrames"), "count"));
    }

    @Test void finallyRecordsFailedStagesAndEndsThePartialFrame() {
        Clock clock = new Clock();
        RenderCpuProfiler profiler = new RenderCpuProfiler(true, clock);
        RuntimeException original = new RuntimeException("mesh failed");
        assertSame(original, assertThrows(RuntimeException.class, () -> {
            profiler.beginFrame(false);
            try {
                long started = profiler.start();
                try { clock.advance(57L); throw original; }
                finally { profiler.finish(OFFICIAL_DH_BUILDER, started); }
            } finally { profiler.endFrame(); }
        }));
        assertFalse(profiler.diagnostics().get("frameOpen").getAsBoolean());
        assertEquals(1L, number(profiler.diagnostics(), "completedFrames"));
        assertEquals(57L, number(stage(profiler, OFFICIAL_DH_BUILDER), "coldFrameMaxNanos"));
        profiler.beginFrame(true);
        profiler.endFrame();
        assertEquals(2L, number(profiler.diagnostics(), "completedFrames"));
    }

    @Test void nestedStagesRemainInclusiveAndDiagnosticsWarnAgainstSummingOrFpsGpuClaims() {
        Clock clock = new Clock();
        RenderCpuProfiler profiler = new RenderCpuProfiler(true, clock);
        profiler.beginFrame(false);
        long outer = profiler.start();
        clock.advance(10L);
        record(profiler, clock, GL_BUFFER_UPLOAD, 5L);
        clock.advance(10L);
        profiler.finish(FAR_DRAW, outer);
        profiler.endFrame();
        assertEquals(25L, number(stage(profiler, FAR_DRAW), "coldFrameMaxNanos"));
        assertEquals(5L, number(stage(profiler, GL_BUFFER_UPLOAD), "coldFrameMaxNanos"));
        String warning = profiler.diagnostics().get("warning").getAsString();
        assertTrue(warning.contains("inclusive/nested"));
        assertTrue(warning.contains("do not sum"));
        assertTrue(warning.contains("full frame"));
        assertTrue(warning.contains("FPS"));
        assertTrue(warning.contains("GPU"));
    }

    @Test void signedClockOriginsAndNanoTimeWrapPreserveDurationArithmetic() {
        Clock clock = new Clock();
        clock.now = -100L;
        RenderCpuProfiler profiler = new RenderCpuProfiler(true, clock);
        record(profiler, clock, GL_STATE_CAPTURE, 20L);
        clock.now = Long.MAX_VALUE - 2L;
        record(profiler, clock, GL_STATE_CAPTURE, 10L);
        clock.now = 0L;
        record(profiler, clock, GL_STATE_CAPTURE, 0L);
        JsonObject stage = stage(profiler, GL_STATE_CAPTURE);
        assertEquals(30L, number(stage, "totalNanos"));
        assertEquals(20L, number(stage, "maxNanos"));
        assertEquals(3L, number(stage, "invocations"));
    }

    @Test void backwardClockAndNullStageRejectWithoutChangingRecordedStatistics() {
        Clock clock = new Clock();
        RenderCpuProfiler profiler = new RenderCpuProfiler(true, clock);
        long started = profiler.start();
        clock.advance(-1L);
        assertThrows(IllegalArgumentException.class, () -> profiler.finish(NEAR_PLAN, started));
        assertEquals(0L, number(stage(profiler, NEAR_PLAN), "invocations"));
        int reads = clock.reads;
        assertThrows(NullPointerException.class, () -> profiler.finish(null, started));
        assertEquals(reads, clock.reads);
    }

    @Test void durationsAndFrameSumsSaturateInsteadOfOverflowingNegative() {
        Clock clock = new Clock();
        RenderCpuProfiler profiler = new RenderCpuProfiler(true, clock);
        profiler.beginFrame(true);
        record(profiler, clock, FAR_PUMP, Long.MAX_VALUE);
        record(profiler, clock, FAR_PUMP, 17L);
        profiler.endFrame();
        record(profiler, clock, FAR_PUMP, Long.MAX_VALUE);
        record(profiler, clock, FAR_PUMP, 30L);
        JsonObject stage = stage(profiler, FAR_PUMP);
        assertTrue(profiler.diagnostics().get("countersSaturated").getAsBoolean());
        assertEquals(4L, number(stage, "invocations"));
        assertEquals(Long.MAX_VALUE, number(stage, "totalNanos"));
        assertEquals(Long.MAX_VALUE, number(stage, "maxNanos"));
        assertEquals(Long.MAX_VALUE, number(stage, "unframedTotalNanos"));
        assertEquals(Long.MAX_VALUE, number(stage, "warmFrameMaxNanos"));
        assertEquals(Long.MAX_VALUE, number(stage.getAsJsonObject("recentFrames"), "p99Nanos"));
    }

    @Test void framePairingErrorsDoNotDiscardAnOpenFrameOrInventCompletedFrames() {
        Clock clock = new Clock();
        RenderCpuProfiler profiler = new RenderCpuProfiler(true, clock);
        assertThrows(IllegalStateException.class, profiler::endFrame);
        profiler.beginFrame(false);
        record(profiler, clock, FAR_COVERAGE, 9L);
        assertThrows(IllegalStateException.class, () -> profiler.beginFrame(true));
        profiler.endFrame();
        assertThrows(IllegalStateException.class, profiler::endFrame);
        assertEquals(1L, number(profiler.diagnostics(), "completedFrames"));
        assertEquals(1L, number(profiler.diagnostics(), "coldCompletedFrames"));
        assertEquals(9L, number(stage(profiler, FAR_COVERAGE), "coldFrameMaxNanos"));
    }

    @Test void emptyAndReturnedDiagnosticsAreStableSnapshotsWithNoClockReads() {
        Clock clock = new Clock();
        RenderCpuProfiler profiler = new RenderCpuProfiler(true, clock);
        JsonObject empty = stage(profiler, GL_STATE_RESTORE);
        assertEquals(0L, number(empty, "invocations"));
        assertEquals(0, number(empty.getAsJsonObject("recentFrames"), "count"));
        assertEquals(0L, number(empty.getAsJsonObject("recentFrames"), "p50Nanos"));
        assertEquals(0L, number(empty.getAsJsonObject("recentFrames"), "p95Nanos"));
        assertEquals(0L, number(empty.getAsJsonObject("recentFrames"), "p99Nanos"));
        empty.addProperty("totalNanos", -500L);
        assertEquals(0L, number(stage(profiler, GL_STATE_RESTORE), "totalNanos"));
        assertEquals(0, clock.reads);
        record(profiler, clock, GL_STATE_RESTORE, 4L);
        assertEquals(0L, number(empty, "invocations"));
        assertEquals(1L, number(stage(profiler, GL_STATE_RESTORE), "invocations"));
    }
}
