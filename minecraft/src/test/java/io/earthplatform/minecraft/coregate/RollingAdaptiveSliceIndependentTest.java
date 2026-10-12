package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Independent owner-scheduler adversaries. No Minecraft, GL, worker, or hardware claim. */
class RollingAdaptiveSliceIndependentTest {
    enum Op { BEGIN, STEP, FINISH, BUILD_CLOSE, PUBLICATION }
    static final CubePos FIRST = new CubePos(1, -800, -3);

    @Test void cheapPhasesPackWithoutChangingTheOrderedCallbackTranscript() {
        var f = new Fixture(4, 100, 1000, 128);
        for (var op : Op.values()) f.cost.put(op, 1L);
        f.callbackCost = 1;
        f.start(9);
        var stats = f.slice();
        assertTrue(f.queue.isDrained());
        assertEquals("DRAINED", stats.stopReason().name());
        assertEquals(45, stats.operations());
        assertEquals(36, stats.callbacks());
        assertEquals(9, f.adopted.size());
        assertEquals(81, stats.elapsedNanos());
        assertEquals(0, stats.overshootNanos());
        assertEquals(1, f.queue.metrics().peakActiveBuilds());
        assertEquals(1, f.queue.metrics().peakCompletedMeshes());
        assertEquals(0, f.cpuBytes());
        assertEquals(9 * 4 * Long.BYTES, f.gpuBytes());
        f.queue.close();
        assertEquals(9 * 4 * Long.BYTES, f.gpuBytes(), "Adopted owners are not queue cleanup");
    }

    @Test void remainingTimeAndCountArePassedToDriverRatherThanResetPerPhase() {
        var f = new Fixture(9, 7, 10, 128);
        f.cost.put(Op.BEGIN, 7L);
        f.callbackCost = 2;
        f.start(1);
        var stats = f.slice();
        assertEquals(List.of(3L), f.stepBudgets);
        assertEquals(List.of(7), f.stepCaps);
        assertEquals(2, stats.callbacks());
        assertEquals(11, stats.elapsedNanos());
        assertEquals(1, stats.overshootNanos());
        assertEquals("TIME_BUDGET", stats.stopReason().name());
        assertEquals(List.of(Op.BEGIN, Op.STEP), f.events);
        assertEquals(1, f.queue.metrics().ownerSlice().maxSoftOvershootNanos());
        assertEquals(0, f.adopted.size());
        f.queue.close();
    }

    @ParameterizedTest @EnumSource(Op.class)
    void exactBudgetBoundaryStartsNoFollowingOperation(Op slow) {
        assertStopsAfter(slow, 10, 0);
    }

    @ParameterizedTest @EnumSource(Op.class)
    void indivisibleOvershootStartsNoFollowingOperationAndRemainsMeasured(Op slow) {
        assertStopsAfter(slow, 27, 17);
    }

    private void assertStopsAfter(Op slow, long duration, long overshoot) {
        var f = new Fixture(1, 20, 10, 128);
        f.cost.put(slow, duration);
        f.start(2);
        var stats = f.slice();
        assertEquals(List.of(Op.values()).subList(0, slow.ordinal() + 1), f.events);
        assertEquals(slow.ordinal() + 1, stats.operations());
        assertEquals(duration, stats.elapsedNanos());
        assertEquals(overshoot, stats.overshootNanos());
        assertEquals("TIME_BUDGET", stats.stopReason().name());
        assertEquals(slow == Op.PUBLICATION ? 1 : 0, f.adopted.size());
        if (slow == Op.FINISH) {
            assertEquals(1, f.queue.activeBuilds());
            assertEquals(1, f.queue.completedSlots());
            assertFalse(f.queue.publishCompleted((p, m) -> fail("Close must precede transfer")));
        }
        assertEquals(duration, f.queue.metrics().ownerSlice().timing().maxNanos());
        assertEquals(overshoot, f.queue.metrics().ownerSlice().maxSoftOvershootNanos());
        f.queue.close();
        assertEquals(0, f.cpuBytes());
        assertEquals(f.adopted.stream().mapToInt(m -> m.payload.size() * Long.BYTES).sum(), f.gpuBytes());
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4, 5, 7, 127, 128})
    void stoppedClockCannotEscapeTheFiniteOperationCeiling(int maxOperations) {
        var f = new Fixture(0, 4096, 100, maxOperations);
        f.start(28);
        var stats = f.slice();
        assertEquals(maxOperations, stats.operations());
        assertEquals(maxOperations, f.events.size());
        assertEquals("OPERATION_LIMIT", stats.stopReason().name());
        assertEquals(0, stats.elapsedNanos());
        assertEquals(0, stats.callbacks());
        assertEquals(1, f.queue.metrics().ownerSlice().operationLimitYields());
        assertTrue(f.queue.activeBuilds() <= 1 && f.queue.completedSlots() <= 1);
        f.queue.close();
        assertTrue(f.queue.isDrained());
    }

    @Test void aggregateCallbackLimitCannotBeRegrantedAtNextMesh() {
        var f = new Fixture(2, 3, 100, 128);
        f.start(3);
        var first = f.slice();
        assertEquals(3, first.callbacks());
        assertEquals(List.of(3, 1), f.stepCaps);
        assertEquals("CALLBACK_LIMIT", first.stopReason().name());
        assertEquals(1, f.adopted.size());
        assertEquals(1, f.builds.get(1).payload.size());
        assertEquals(0, f.clock.now);
        while (!f.queue.isDrained()) assertTrue(f.slice().callbacks() <= 3);
        assertEquals(6, f.adopted.stream().mapToInt(m -> m.payload.size()).sum());
        assertEquals(3, f.queue.metrics().ownerSlice().maxCallbacks());
        f.queue.close();
    }

    @Test void emptyCompleteWorkIsAllowedButIncompleteNoProgressPoisonsBeforeFinish() {
        var air = new Fixture(0, 1, 100, 128);
        air.start(1);
        assertEquals("DRAINED", air.slice().stopReason().name());
        assertTrue(air.adopted.getFirst().payload.isEmpty());
        air.queue.close();
        var bad = new Fixture(1, 1, 100, 128);
        bad.noProgress = true;
        bad.start(1);
        assertThrows(IllegalStateException.class, bad::slice);
        assertEquals(List.of(Op.BEGIN, Op.STEP), bad.events);
        assertEquals(1, bad.queue.activeBuilds());
        assertTrue(bad.queue.isPoisoned());
        assertThrows(IllegalStateException.class, bad::slice);
        bad.queue.close();
        assertEquals(0, bad.cpuBytes());
    }

    @ParameterizedTest @EnumSource(Op.class)
    void invalidationAtEachPackedPhaseRetainsCleanupAndPreventsAnyLaterPhase(Op boundary) {
        var f = new Fixture(1, 100, 100, 128);
        f.after.put(boundary, () -> f.current = false);
        f.start(2);
        assertThrows(IllegalStateException.class, f::slice);
        assertEquals(List.of(Op.values()).subList(0, boundary.ordinal() + 1), f.events);
        assertTrue(f.queue.isPoisoned());
        f.current = true;
        assertThrows(IllegalStateException.class, f::slice, "Restoring a revoked generation cannot revive the queue");
        f.after.clear();
        f.queue.close();
        assertTrue(f.queue.isDrained());
        assertEquals(0, f.cpuBytes());
        assertEquals(0, f.gpuBytes(), "Even a recorded but fence-rejected publication remains queue-owned");
    }

    @ParameterizedTest @EnumSource(Op.class)
    void throwingEachPackedPhaseRecordsFailedOwnerSliceAndRetainsAllAcquiredOwners(Op boundary) {
        for (boolean error : new boolean[]{false, true}) {
            var f = new Fixture(1, 100, 10, 128);
            Throwable sentinel = error ? new AssertionError("injected " + boundary) : new IllegalStateException("injected " + boundary);
            f.failAt = boundary; f.failure = sentinel; f.cost.put(boundary, 29L);
            f.start(2);
            assertSame(sentinel, assertThrows(Throwable.class, f::slice));
            assertEquals(List.of(Op.values()).subList(0, boundary.ordinal() + 1), f.events);
            assertTrue(f.queue.isPoisoned());
            assertEquals(29, f.queue.metrics().ownerSlice().timing().maxNanos());
            assertEquals(19, f.queue.metrics().ownerSlice().maxSoftOvershootNanos());
            f.failAt = null;
            f.queue.close();
            assertTrue(f.queue.isDrained());
            assertEquals(0, f.cpuBytes());
            assertEquals(0, f.gpuBytes());
        }
    }

    @ParameterizedTest @EnumSource(Op.class)
    void primaryDriverFailureSurvivesPhaseAndOwnerTimingClockFailures(Op boundary) {
        var f = new Fixture(1, 100, 100, 128);
        var primary = new IllegalStateException("primary driver " + boundary);
        f.failAt = boundary; f.failure = primary; f.failClockWithDriver = true;
        f.start(2);
        assertSame(primary, assertThrows(Throwable.class, f::slice));
        assertTrue(java.util.Arrays.stream(primary.getSuppressed()).anyMatch(e -> e.getMessage().contains("clock unavailable")),
                "Secondary clock diagnostics must remain attached to the first native failure");
        assertTrue(f.queue.isPoisoned());
        f.failAt = null; f.clock.failed = false;
        f.queue.close();
        assertTrue(f.queue.isDrained()); assertEquals(0, f.cpuBytes()); assertEquals(0, f.gpuBytes());
    }

    @Test void deferredFinishCleanupAttemptsBothOwnersAndRetriesOnlyUnreleasedOwners() {
        var f = new Fixture(1, 10, 10, 128);
        f.cost.put(Op.FINISH, 10L);
        f.start(2);
        f.slice();
        assertEquals(1, f.queue.activeBuilds());
        assertEquals(1, f.queue.completedSlots());
        f.failBuildClose = f.failMeshClose = true;
        assertThrows(IllegalStateException.class, f.queue::close);
        assertEquals(1, f.builds.getFirst().closeAttempts);
        assertEquals(1, f.meshes.getFirst().closeAttempts);
        assertEquals(0, f.queue.queuedTargets());
        f.failBuildClose = false;
        assertThrows(IllegalStateException.class, f.queue::close);
        assertEquals(0, f.queue.activeBuilds());
        assertEquals(1, f.queue.completedSlots());
        f.failMeshClose = false;
        f.queue.close(); f.queue.close();
        assertEquals(2, f.builds.getFirst().closeAttempts);
        assertEquals(3, f.meshes.getFirst().closeAttempts);
        assertEquals(0, f.cpuBytes()); assertEquals(0, f.gpuBytes());
        assertThrows(IllegalStateException.class, f::slice);
    }

    @Test void reentrantPackedPumpCannotBeResurrectedByACatchingCallback() {
        var f = new Fixture(2, 10, 100, 128);
        f.after.put(Op.STEP, () -> assertThrows(IllegalStateException.class, f::slice));
        f.start(2);
        assertThrows(IllegalStateException.class, f::slice);
        assertEquals(List.of(Op.BEGIN, Op.STEP), f.events);
        f.after.clear(); f.queue.close();
        assertEquals(0, f.cpuBytes());
    }

    @Test void currentPublicationAndResourceAreRequiredBetweenConsecutiveMeshes() {
        for (int identity : new int[]{0, 1, 2}) {
            var f = new Fixture(1, 10, 100, 128);
            long[] stamp = {11, 31, 47};
            f.queue.start(f.positions(2), stamp[0], stamp[1], () -> stamp[0] == 11 && stamp[1] == 31 && stamp[2] == 47);
            f.after.put(Op.PUBLICATION, () -> stamp[identity]++);
            assertThrows(IllegalStateException.class, f::slice);
            assertEquals(1, f.builds.size(), "No next begin after publication, resource or session revocation");
            assertEquals(1, f.queue.completedSlots());
            f.after.clear(); f.queue.close();
            assertEquals(0, f.gpuBytes());
        }
    }

    @Test void fullCapacityRejectsTwentyNinthTargetWithoutLosingOrOverwritingAnOwner() {
        var f = new Fixture(0, 4096, 100, 128);
        assertThrows(IllegalArgumentException.class, () -> f.queue.start(f.positions(29), 1, 1, () -> true));
        assertTrue(f.queue.isDrained()); assertFalse(f.queue.isPoisoned());
        f.start(28);
        while (!f.queue.isDrained()) {
            var stats = f.slice();
            assertTrue(stats.operations() <= 128);
            assertTrue(f.queue.activeBuilds() <= 1 && f.queue.completedSlots() <= 1);
        }
        assertEquals(f.positions(28), f.adopted.stream().map(m -> m.position).toList());
        assertEquals(28, f.queue.metrics().publishedMeshes());
        f.queue.close();
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 7, 31})
    void adaptiveAndLegacySchedulesProduceIdenticalOrderedPayloadsAndOwnership(int blockCount) {
        for (int maxOps : new int[]{1, 3, 11, 128}) {
            var packed = new Fixture(blockCount, 13, 17, maxOps);
            var legacy = new Fixture(blockCount, 13, 17, maxOps);
            for (var f : List.of(packed, legacy)) {
                for (var op : Op.values()) f.cost.put(op, (long) (op.ordinal() + 1));
                f.callbackCost = 2;
                f.start(9);
            }
            int packedFrames = 0, legacyFrames = 0;
            while (!packed.queue.isDrained()) { packed.slice(); assertTrue(++packedFrames < 10000); }
            while (!legacy.queue.isDrained()) {
                legacy.queue.pump(); legacy.queue.publishCompleted(legacy::adopt); assertTrue(++legacyFrames < 10000);
            }
            assertEquals(legacy.adopted.stream().map(m -> m.position).toList(), packed.adopted.stream().map(m -> m.position).toList());
            assertEquals(legacy.adopted.stream().map(m -> m.payload).toList(), packed.adopted.stream().map(m -> m.payload).toList());
            assertEquals(legacy.queue.metrics().reportedCallbacks(), packed.queue.metrics().reportedCallbacks());
            assertEquals(legacy.cpuBytes(), packed.cpuBytes()); assertEquals(legacy.gpuBytes(), packed.gpuBytes());
            assertEquals(legacy.queue.metrics().begin().calls(), packed.queue.metrics().begin().calls());
            assertEquals(legacy.queue.metrics().finishUpload().calls(), packed.queue.metrics().finishUpload().calls());
            packed.queue.close(); legacy.queue.close();
        }
    }

    @Test void ownerSliceEnforcesOwnerThreadWithoutPoisoningTheActualOwner() throws InterruptedException {
        var f = new Fixture(1, 10, 100, 128);
        f.start(1);
        var failure = new AtomicReference<Throwable>();
        var thread = new Thread(() -> { try { f.slice(); } catch (Throwable t) { failure.set(t); } });
        thread.start(); thread.join();
        assertInstanceOf(IllegalStateException.class, failure.get());
        assertFalse(f.queue.isPoisoned()); assertTrue(f.events.isEmpty());
        assertEquals("DRAINED", f.slice().stopReason().name());
        f.queue.close();
    }

    @Test void signedClockOriginAndNanoTimeWrapPreserveTheSameBoundaries() {
        for (long origin : new long[]{Long.MIN_VALUE + 100, -100, Long.MAX_VALUE - 4}) {
            var f = new Fixture(1, 10, 10, 128);
            f.clock.now = origin;
            f.cost.put(Op.BEGIN, 7L); f.cost.put(Op.STEP, 6L);
            f.start(2);
            var stats = f.slice();
            assertEquals(13, stats.elapsedNanos()); assertEquals(3, stats.overshootNanos());
            assertEquals(List.of(Op.BEGIN, Op.STEP), f.events);
            f.queue.close();
        }
    }

    static final class Clock implements LongSupplier { long now; boolean failed; public long getAsLong() { if (failed) throw new IllegalStateException("clock unavailable"); return now; } }
    static final class Build {
        final CubePos position; final List<Long> payload = new ArrayList<>();
        int cpu = 64, partialGpu, closeAttempts;
        Build(CubePos position) { this.position = position; }
    }
    static final class Mesh {
        final CubePos position; final List<Long> payload;
        int gpu, closeAttempts;
        Mesh(Build build) { position = build.position; payload = List.copyOf(build.payload); gpu = payload.size() * Long.BYTES; }
    }
    static final class Fixture implements RollingMeshWorkQueue.Driver<Build, Mesh> {
        final Clock clock = new Clock();
        final EnumMap<Op, Long> cost = new EnumMap<>(Op.class);
        final EnumMap<Op, Runnable> after = new EnumMap<>(Op.class);
        final List<Op> events = new ArrayList<>();
        final List<Build> builds = new ArrayList<>();
        final List<Mesh> meshes = new ArrayList<>(), adopted = new ArrayList<>();
        final List<Long> stepBudgets = new ArrayList<>(); final List<Integer> stepCaps = new ArrayList<>();
        final RollingMeshWorkQueue<Build, Mesh> queue;
        final int total;
        long callbackCost;
        boolean current = true, noProgress, failBuildClose, failMeshClose, failClockWithDriver;
        Op failAt; Throwable failure;
        Fixture(int total, int callbackLimit, long budget, int operations) {
            this.total = total;
            queue = new RollingMeshWorkQueue<>(this, 28, callbackLimit, budget, operations, clock);
        }
        List<CubePos> positions(int count) { return IntStream.range(0, count).mapToObj(i -> FIRST.offset(i, 0, 0)).toList(); }
        void start(int count) { queue.start(positions(count), 1, 1, () -> current); }
        RollingMeshWorkQueue.SliceStats slice() { return queue.pumpSlice(this::adopt); }
        void enter(Op op) { events.add(op); clock.now += cost.getOrDefault(op, 0L); if (failAt == op) { clock.failed = failClockWithDriver; raise(failure); } }
        void exit(Op op) { var action = after.get(op); if (action != null) action.run(); }
        @Override public Build begin(CubePos position) {
            enter(Op.BEGIN); var build = new Build(position); builds.add(build); exit(Op.BEGIN); return build;
        }
        @Override public RollingMeshWorkQueue.StepStats step(Build build, int maxCallbacks, long softNanos) {
            long start = clock.now;
            stepBudgets.add(softNanos); stepCaps.add(maxCallbacks);
            enter(Op.STEP);
            int initial = build.payload.size();
            while (!noProgress && build.payload.size() < total && build.payload.size() - initial < maxCallbacks) {
                if (build.payload.size() > initial && clock.now - start >= softNanos) break;
                int index = build.payload.size();
                build.payload.add((build.position.x() * 0x9e3779b9L) ^ (build.position.y() << 12) ^ (build.position.z() << 24) ^ index);
                clock.now += callbackCost;
            }
            exit(Op.STEP);
            long elapsed = clock.now - start;
            return new RollingMeshWorkQueue.StepStats(build.payload.size() - initial, build.payload.size(), total,
                    elapsed, softNanos, Math.max(0, elapsed - softNanos), build.payload.size() == initial ? 0 : callbackCost,
                    build.payload.size() == total);
        }
        @Override public Mesh finish(Build build) {
            assertEquals(total, build.payload.size(), "Partial native work cannot become a mesh");
            if (failAt == Op.FINISH) build.partialGpu = 8;
            enter(Op.FINISH); var mesh = new Mesh(build); meshes.add(mesh); exit(Op.FINISH); return mesh;
        }
        @Override public void closeBuild(Build build) {
            build.closeAttempts++;
            enter(Op.BUILD_CLOSE);
            if (failBuildClose) throw new IllegalStateException("build cleanup retry");
            build.cpu = build.partialGpu = 0; exit(Op.BUILD_CLOSE);
        }
        @Override public void closeMesh(Mesh mesh) {
            mesh.closeAttempts++;
            if (failMeshClose) throw new IllegalStateException("mesh cleanup retry");
            mesh.gpu = 0;
        }
        void adopt(CubePos position, Mesh mesh) {
            assertEquals(position, mesh.position);
            enter(Op.PUBLICATION); adopted.add(mesh); exit(Op.PUBLICATION);
        }
        int cpuBytes() { return builds.stream().mapToInt(b -> b.cpu).sum(); }
        int gpuBytes() { return builds.stream().mapToInt(b -> b.partialGpu).sum() + meshes.stream().mapToInt(m -> m.gpu).sum(); }
    }
    static void raise(Throwable failure) { if (failure instanceof Error error) throw error; throw (RuntimeException) failure; }
}
