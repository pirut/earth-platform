package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static io.earthplatform.minecraft.coregate.RollingMeshWorkQueue.Phase.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure owner/fence/frame tests: no Minecraft renderer, native allocation, or asynchronous callbacks. */
class RollingMeshWorkQueueTest {
    private static final CubePos A = new CubePos(0, 0, 0), B = new CubePos(1, 0, 0);

    @Test void beginSlicesFinishAndNextCubeOccupySeparatePumpFrames() {
        var fixture = new Fixture(5, 2, 100);
        fixture.start(A, B);
        assertEquals(BEGIN, fixture.queue.pump());
        assertEquals(List.of("begin"), fixture.driver.events);
        assertEquals(1, fixture.queue.activeBuilds());
        assertEquals(1, fixture.queue.queuedTargets());
        assertFalse(fixture.queue.publishCompleted((position, mesh) -> fail("No mesh before finish")));
        assertEquals(STEP, fixture.queue.pump());
        assertEquals(STEP, fixture.queue.pump());
        assertEquals(STEP, fixture.queue.pump());
        assertEquals(0, fixture.driver.finishCalls);
        assertEquals(0, fixture.queue.completedSlots());
        assertEquals(FINISH, fixture.queue.pump());
        assertEquals(1, fixture.driver.finishCalls);
        assertEquals(0, fixture.queue.activeBuilds());
        assertEquals(1, fixture.queue.completedSlots());
        assertEquals(1, fixture.driver.builds.getFirst().closeAttempts);
        assertEquals(WAITING_PUBLICATION, fixture.queue.pump());
        assertEquals(1, fixture.driver.builds.size(), "The completed slot blocks the next build");
        var accepted = new ArrayList<Mesh>();
        assertTrue(fixture.queue.publishCompleted((position, mesh) -> { assertEquals(A, position); accepted.add(mesh); }));
        assertFalse(fixture.queue.isDrained(), "The second target is still queued");
        assertEquals(BEGIN, fixture.queue.pump());
        fixture.finishBatch(accepted);
        assertTrue(fixture.queue.isDrained());
        assertEquals(IDLE, fixture.queue.pump());
        fixture.queue.close();
        assertTrue(accepted.stream().noneMatch(mesh -> mesh.closed), "Published owners belong to the consumer");
        assertEquals(2, fixture.queue.metrics().publishedMeshes());
    }

    @Test void defaultCallbackCountIsHardEvenWhenTheClockDoesNotAdvance() {
        var clock = new Clock();
        var driver = new FakeDriver(clock, 4100);
        driver.callbackNanos = 0;
        var queue = new RollingMeshWorkQueue<>(driver);
        queue.start(List.of(A), 1, 1, () -> true);
        assertEquals(BEGIN, queue.pump());
        assertEquals(STEP, queue.pump());
        assertEquals(4096, driver.builds.getFirst().completed);
        assertEquals(0, driver.finishCalls);
        assertEquals(STEP, queue.pump());
        assertEquals(4100, driver.builds.getFirst().completed);
        assertEquals(4096, queue.metrics().maxCallbacksPerSlice());
        assertEquals(2_000_000, driver.lastSoftNanos);
        assertEquals(FINISH, queue.pump());
        queue.close();
    }

    @Test void indivisibleCallbackCanOvershootSoftTimeAndItsDurationIsReportedHonestly() {
        var f = new Fixture(3, 3, 10);
        f.driver.callbackNanos = 25;
        f.start(A);
        f.queue.pump();
        assertEquals(STEP, f.queue.pump());
        assertEquals(1, f.driver.builds.getFirst().completed);
        assertEquals(25, f.queue.metrics().maxCallbackNanos());
        assertEquals(15, f.queue.metrics().maxSoftOvershootNanos());
        assertEquals(25, f.queue.metrics().slice().maxNanos());
        assertEquals(0, f.driver.finishCalls);
        f.queue.close();
    }

    @Test void sliceStopsAtTheSoftLimitBeforeStartingAnotherCallback() {
        var f = new Fixture(7, 7, 10);
        f.driver.callbackNanos = 5;
        f.start(A);
        f.queue.pump();
        f.queue.pump();
        assertEquals(2, f.driver.builds.getFirst().completed);
        assertEquals(0, f.queue.metrics().maxSoftOvershootNanos());
        f.queue.close();
    }

    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3, 4})
    void invalidationBetweenEveryPhasePreventsFurtherDriverWork(int boundary) {
        var f = new Fixture(1, 1, 10);
        f.start(A, B);
        for (int i = 0; i < boundary && i < 3; i++) f.queue.pump();
        if (boundary == 4) f.queue.publishCompleted((position, mesh) -> {});
        int callbacksBefore = f.driver.events.size();
        f.current = false;
        if (boundary == 3) assertThrows(IllegalStateException.class,
                () -> f.queue.publishCompleted((position, mesh) -> fail("Stale publication")));
        else assertThrows(IllegalStateException.class, f.queue::pump);
        assertEquals(callbacksBefore, f.driver.events.size());
        assertTrue(f.queue.isPoisoned());
        f.queue.close();
        assertTrue(f.queue.isDrained());
    }

    @ParameterizedTest @EnumSource(value = Event.class, names = {"BEGIN", "STEP", "FINISH", "BUILD_CLOSE"})
    void invalidationInsideEveryDriverCallbackRetainsEveryReturnedOwner(Event boundary) {
        var f = new Fixture(1, 1, 10);
        f.driver.after.put(boundary, () -> f.current = false);
        f.start(A);
        if (boundary != Event.BEGIN) f.queue.pump();
        if (boundary == Event.FINISH || boundary == Event.BUILD_CLOSE) f.queue.pump();
        assertThrows(IllegalStateException.class, f.queue::pump);
        assertTrue(f.queue.isPoisoned());
        assertEquals(boundary == Event.BUILD_CLOSE ? 0 : 1, f.queue.activeBuilds());
        assertEquals(boundary == Event.FINISH || boundary == Event.BUILD_CLOSE ? 1 : 0, f.queue.completedSlots());
        assertThrows(IllegalStateException.class, f.queue::pump);
        f.driver.after.clear();
        f.queue.close();
        assertTrue(f.queue.isDrained());
        assertTrue(f.driver.meshes.stream().allMatch(mesh -> mesh.closed));
    }

    @Test void publicationInvalidatingItsOwnFenceKeepsTheCompletedCleanupOwner() {
        var f = new Fixture(1, 1, 10);
        f.completeOne();
        assertThrows(IllegalStateException.class,
                () -> f.queue.publishCompleted((position, mesh) -> f.current = false));
        assertEquals(1, f.queue.completedSlots());
        assertEquals(0, f.queue.metrics().publishedMeshes());
        f.queue.close();
        assertTrue(f.driver.meshes.getFirst().closed);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void throwingPublicationKeepsItsSlotAndPermanentlyPoisonsNormalWork(boolean error) {
        var f = new Fixture(1, 1, 10);
        f.completeOne();
        Throwable failure = failure(error, "consumer");
        assertSame(failure, assertThrows(Throwable.class,
                () -> f.queue.publishCompleted((position, mesh) -> raise(failure))));
        assertEquals(1, f.queue.completedSlots());
        assertEquals(1, f.queue.metrics().publication().calls());
        assertThrows(IllegalStateException.class, () -> f.queue.publishCompleted((position, mesh) -> {}));
        assertThrows(IllegalStateException.class, () -> f.queue.start(List.of(B), 2, 1, () -> true));
        f.queue.close();
        assertTrue(f.queue.isDrained());
        assertTrue(f.driver.meshes.getFirst().closed);
    }

    @ParameterizedTest @MethodSource("finishFailureStages")
    void partialFinishAllocationsRemainReachableThroughBuildAndCleanupCanRetry(int allocations, boolean error) {
        var f = new Fixture(1, 1, 10);
        f.start(A, B);
        f.queue.pump();
        f.queue.pump();
        f.driver.finishAllocations = allocations;
        f.driver.finishFailure = failure(error, "partial finish");
        assertSame(f.driver.finishFailure, assertThrows(Throwable.class, f.queue::pump));
        assertEquals(allocations, f.driver.builds.getFirst().gpuOwned);
        assertEquals(1, f.queue.activeBuilds());
        assertEquals(0, f.queue.completedSlots());
        f.driver.buildCloseFailure = new IllegalStateException("cleanup retry");
        assertThrows(IllegalStateException.class, f.queue::close);
        assertEquals(1, f.queue.activeBuilds());
        assertEquals(0, f.queue.queuedTargets());
        f.driver.buildCloseFailure = null;
        f.queue.close();
        assertEquals(0, f.driver.builds.getFirst().gpuOwned);
        assertTrue(f.queue.isDrained());
        assertThrows(IllegalStateException.class, () -> f.queue.start(List.of(B), 2, 1, () -> true));
    }

    static Stream<Arguments> finishFailureStages() {
        return IntStream.rangeClosed(0, 3).boxed().flatMap(stage -> Stream.of(false, true).map(error -> Arguments.of(stage, error)));
    }

    @Test void failedPostFinishBuildClosePreservesBothOwnersAndCloseAttemptsBoth() {
        var f = new Fixture(1, 1, 10);
        f.start(A, B);
        f.queue.pump();
        f.queue.pump();
        var buildFailure = new IllegalStateException("build close");
        var meshFailure = new AssertionError("mesh close");
        f.driver.buildCloseFailure = buildFailure;
        f.driver.meshCloseFailure = meshFailure;
        assertSame(buildFailure, assertThrows(IllegalStateException.class, f.queue::pump));
        assertEquals(1, f.queue.activeBuilds());
        assertEquals(1, f.queue.completedSlots());
        assertFalse(f.queue.isDrained());
        assertSame(buildFailure, assertThrows(IllegalStateException.class, f.queue::close));
        assertArrayEquals(new Throwable[]{meshFailure}, buildFailure.getSuppressed());
        assertEquals(2, f.driver.builds.getFirst().closeAttempts);
        assertEquals(1, f.driver.meshes.getFirst().closeAttempts);
        assertEquals(1, f.queue.activeBuilds());
        assertEquals(1, f.queue.completedSlots());
        f.driver.buildCloseFailure = null;
        assertSame(meshFailure, assertThrows(AssertionError.class, f.queue::close));
        assertEquals(0, f.queue.activeBuilds());
        assertEquals(1, f.queue.completedSlots());
        f.driver.meshCloseFailure = null;
        f.queue.close();
        f.queue.close();
        assertEquals(3, f.driver.builds.getFirst().closeAttempts, "Successful build cleanup is not retried");
        assertEquals(3, f.driver.meshes.getFirst().closeAttempts);
        assertTrue(f.queue.isDrained());
    }

    @Test void sameFailureObjectFromBothCleanupCallbacksIsSafe() {
        var f = new Fixture(1, 1, 10);
        f.start(A);
        f.queue.pump();
        f.queue.pump();
        var failure = new AssertionError("same");
        f.driver.buildCloseFailure = f.driver.meshCloseFailure = failure;
        assertSame(failure, assertThrows(AssertionError.class, f.queue::pump));
        assertSame(failure, assertThrows(AssertionError.class, f.queue::close));
        assertEquals(0, failure.getSuppressed().length);
        assertEquals(1, f.driver.meshes.getFirst().closeAttempts);
        f.driver.buildCloseFailure = f.driver.meshCloseFailure = null;
        f.queue.close();
        assertTrue(f.queue.isDrained());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void throwingBeginExposesNoOwnedNativeHandleAndNeverRetriesNormalWork(boolean error) {
        var f = new Fixture(1, 1, 10);
        Throwable failure = failure(error, "begin");
        f.driver.before.put(Event.BEGIN, () -> raise(failure));
        f.start(A);
        assertSame(failure, assertThrows(Throwable.class, f.queue::pump));
        assertEquals(0, f.queue.activeBuilds());
        assertEquals(0, f.queue.completedSlots());
        assertTrue(f.driver.builds.isEmpty());
        assertEquals(1, f.queue.queuedTargets());
        assertThrows(IllegalStateException.class, f.queue::pump);
        f.queue.close();
        assertTrue(f.queue.isDrained());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void callbackFailureRetainsCpuOwnerAndFailedSliceWallTiming(boolean error) {
        var f = new Fixture(5, 5, 100);
        var failure = failure(error, "model callback");
        f.driver.onBlock = () -> { f.clock.advance(40); raise(failure); };
        f.start(A);
        f.queue.pump();
        assertSame(failure, assertThrows(Throwable.class, f.queue::pump));
        assertEquals(1, f.queue.activeBuilds());
        assertEquals(1, f.queue.metrics().slice().calls());
        assertEquals(40, f.queue.metrics().slice().totalNanos());
        f.queue.close();
        assertEquals(0, f.driver.builds.getFirst().cpuOwned);
    }

    @Test void nativeDriverFencesEachModelCallbackWithinASlice() {
        var f = new Fixture(5, 5, 100);
        f.driver.onBlock = () -> f.driver.nativeCurrent = false;
        f.start(A);
        f.queue.pump();
        assertThrows(IllegalStateException.class, f.queue::pump);
        assertEquals(1, f.driver.modelCalls, "No next model callback after reload inside a callback");
        assertEquals(0, f.driver.builds.getFirst().completed);
        f.queue.close();
    }

    @ParameterizedTest @EnumSource(value = Event.class, names = {"BEGIN", "FINISH"})
    void returnedOwnerIsRetainedBeforeFalliblePostCallbackClockRead(Event callback) {
        var f = new Fixture(1, 1, 10);
        f.driver.after.put(callback, () -> f.clock.failNext = true);
        f.start(A);
        if (callback == Event.FINISH) { f.queue.pump(); f.queue.pump(); }
        assertThrows(IllegalStateException.class, f.queue::pump);
        assertEquals(1, f.queue.activeBuilds());
        assertEquals(callback == Event.FINISH ? 1 : 0, f.queue.completedSlots());
        f.queue.close();
        assertTrue(f.queue.isDrained());
    }

    @ParameterizedTest @EnumSource(value = Event.class, names = {"BEGIN", "STEP", "FINISH", "BUILD_CLOSE"})
    void reentrantCloseFencesButNeverFreesAnActiveCallbackOwner(Event callback) {
        var f = new Fixture(1, 1, 10);
        f.driver.after.put(callback, () -> {
            int closesBefore = f.driver.events.stream().filter(value -> value.endsWith("close")).toList().size();
            assertThrows(IllegalStateException.class, f.queue::close);
            int closesAfter = f.driver.events.stream().filter(value -> value.endsWith("close")).toList().size();
            assertEquals(closesBefore, closesAfter);
        });
        f.start(A, B);
        if (callback != Event.BEGIN) f.queue.pump();
        if (callback == Event.FINISH || callback == Event.BUILD_CLOSE) f.queue.pump();
        assertThrows(IllegalStateException.class, f.queue::pump);
        assertTrue(f.queue.queuedTargets() >= 0, "Reentrant close cannot corrupt the in-progress cursor");
        f.driver.after.clear();
        f.queue.close();
        assertTrue(f.queue.isDrained());
    }

    @Test void reentrantPumpRemainsPoisonedEvenWhenTheCallbackCatchesItsException() {
        var f = new Fixture(1, 1, 10);
        f.driver.after.put(Event.BEGIN, () -> assertThrows(IllegalStateException.class, f.queue::pump));
        f.start(A);
        assertThrows(IllegalStateException.class, f.queue::pump);
        assertEquals(1, f.queue.activeBuilds());
        f.queue.close();
    }

    @Test void everyPublicOperationEnforcesItsConstructingOwnerThread() throws InterruptedException {
        var f = new Fixture(1, 1, 10);
        List<Runnable> operations = List.of(
                () -> f.queue.start(List.of(A), 1, 1, () -> true), f.queue::pump,
                () -> f.queue.publishCompleted((position, mesh) -> {}), f.queue::close,
                f.queue::isDrained, f.queue::isPoisoned, f.queue::queuedTargets, f.queue::activeBuilds,
                f.queue::completedSlots, f.queue::publication, f.queue::resourceGeneration, f.queue::metrics);
        for (var operation : operations) {
            var failure = new AtomicReference<Throwable>();
            var thread = new Thread(() -> { try { operation.run(); } catch (Throwable value) { failure.set(value); } });
            thread.start(); thread.join();
            assertInstanceOf(IllegalStateException.class, failure.get());
        }
        assertFalse(f.queue.isPoisoned(), "Wrong-thread calls cannot mutate owner state");
        f.start(A);
        assertEquals(BEGIN, f.queue.pump());
        f.queue.close();
    }

    @Test void overlappingBatchesRejectQueuedActiveAndCompletedWork() {
        var f = new Fixture(1, 1, 10);
        f.start(A);
        for (int phase = 0; phase < 4; phase++) {
            assertThrows(IllegalStateException.class, () -> f.queue.start(List.of(B), 2, 1, () -> true));
            assertEquals(1, f.queue.publication());
            if (phase < 3) f.queue.pump();
        }
        assertTrue(f.queue.publishCompleted((position, mesh) -> {}));
        f.queue.start(List.of(B), 2, 1, () -> true);
        assertEquals(2, f.queue.publication());
        f.queue.close();
    }

    @Test void drainedBatchCannotReplayPublicationOrRegressResourceGeneration() {
        var f = new Fixture(1, 1, 10);
        f.queue.start(List.of(), 20, 3, () -> true);
        assertTrue(f.queue.isDrained());
        for (long publication : new long[]{1, 19, 20})
            assertThrows(IllegalArgumentException.class, () -> f.queue.start(List.of(), publication, 3, () -> true));
        assertThrows(IllegalArgumentException.class, () -> f.queue.start(List.of(), 21, 2, () -> true));
        assertThrows(IllegalArgumentException.class, () -> f.queue.start(List.of(), 1, 4, () -> true));
        f.queue.start(List.of(), 21, 3, () -> true);
        f.queue.start(List.of(), 22, 4, () -> true);
        assertEquals(3, f.queue.metrics().batches());
        assertEquals(22, f.queue.publication());
        assertEquals(4, f.queue.resourceGeneration());
        f.queue.close();
    }

    @Test void externalPublicationAndResourcePredicateRejectsABAWhileWorkIsActive() {
        for (boolean resource : new boolean[]{false, true}) {
            var f = new Fixture(1, 1, 10);
            long[] state = {10, 5};
            f.queue.start(List.of(A), 10, 5, () -> state[0] == 10 && state[1] == 5);
            f.queue.pump();
            state[resource ? 1 : 0]++;
            assertThrows(IllegalStateException.class, f.queue::pump);
            state[0] = 10; state[1] = 5;
            assertThrows(IllegalStateException.class, f.queue::pump, "Restoring values cannot resurrect poisoned work");
            assertEquals(0, f.driver.modelCalls);
            f.queue.close();
        }
    }

    @Test void emptyBatchStartsAndDrainsWithoutInventingBuildersOrMeshes() {
        var f = new Fixture(1, 1, 10);
        f.start();
        assertTrue(f.queue.isDrained());
        assertEquals(IDLE, f.queue.pump());
        assertFalse(f.queue.publishCompleted((position, mesh) -> fail("Empty batch")));
        assertTrue(f.driver.events.isEmpty());
        assertEquals(1, f.queue.metrics().batches());
        assertEquals(0, f.queue.metrics().begin().calls());
        f.queue.close();
    }

    @Test void zeroCallbackEmptyMeshStillHasSeparateStepFinishAndPublicationFences() {
        var f = new Fixture(0, 1, 10);
        f.start(A);
        assertEquals(BEGIN, f.queue.pump());
        assertEquals(STEP, f.queue.pump());
        assertEquals(0, f.driver.modelCalls);
        assertEquals(0, f.driver.finishCalls);
        assertEquals(FINISH, f.queue.pump());
        assertEquals(1, f.queue.completedSlots());
        assertFalse(f.queue.isDrained());
        assertTrue(f.queue.publishCompleted((position, mesh) -> assertEquals(0, mesh.blocks)));
        assertTrue(f.queue.isDrained());
        f.queue.close();
    }

    @Test void boundedInputValidationRejectsOversizeDuplicatesNullsAndDefensivelyCopies() {
        var f = new Fixture(1, 1, 10);
        var tooMany = IntStream.range(0, 29).mapToObj(x -> new CubePos(x, 0, 0)).toList();
        assertThrows(IllegalArgumentException.class, () -> f.queue.start(tooMany, 1, 1, () -> true));
        assertThrows(IllegalArgumentException.class, () -> f.queue.start(List.of(A, A), 1, 1, () -> true));
        var nulls = new ArrayList<CubePos>(); nulls.add(A); nulls.add(null);
        assertThrows(NullPointerException.class, () -> f.queue.start(nulls, 1, 1, () -> true));
        assertTrue(f.queue.isDrained());
        assertFalse(f.queue.isPoisoned());
        var positions = new ArrayList<>(List.of(A, B));
        f.queue.start(positions, 1, 1, () -> true);
        positions.clear();
        positions.add(new CubePos(99, 0, 0));
        f.queue.pump();
        assertEquals(A, f.driver.builds.getFirst().position);
        assertEquals(1, f.queue.queuedTargets());
        f.queue.close();
    }

    @Test void fullTwentyEightTargetBatchNeverOwnsMoreThanOneBuildOrOneCompletedSlot() {
        var f = new Fixture(1, 1, 10);
        var positions = IntStream.range(0, 28).mapToObj(x -> new CubePos(x, 0, 0)).toList();
        f.queue.start(positions, 1, 1, () -> true);
        var accepted = new ArrayList<Mesh>();
        f.finishBatch(accepted);
        var metrics = f.queue.metrics();
        assertEquals(28, metrics.peakQueuedTargets());
        assertEquals(1, metrics.peakActiveBuilds());
        assertEquals(1, metrics.peakCompletedMeshes());
        assertEquals(28, metrics.completedMeshes());
        assertEquals(28, accepted.size());
        f.queue.close();
    }

    @Test void constructorNeverPermitsExpandingProductionTargetOrCallbackCaps() {
        var driver = new FakeDriver(new Clock(), 1);
        for (int limit : new int[]{0, -1, 29}) assertThrows(IllegalArgumentException.class,
                () -> new RollingMeshWorkQueue<>(driver, limit, 1, 1, System::nanoTime));
        for (int callbacks : new int[]{0, -1, 4097}) assertThrows(IllegalArgumentException.class,
                () -> new RollingMeshWorkQueue<>(driver, 1, callbacks, 1, System::nanoTime));
        assertThrows(IllegalArgumentException.class, () -> new RollingMeshWorkQueue<>(driver, 1, 1, 0, System::nanoTime));
        var queue = new RollingMeshWorkQueue<>(driver, 1, 1, 1, System::nanoTime);
        assertThrows(IllegalArgumentException.class, () -> queue.start(List.of(A, B), 1, 1, () -> true));
        queue.close();
    }

    @ParameterizedTest @MethodSource("invalidStatistics")
    void impossibleDriverStatisticsPoisonBeforeFinish(RollingMeshWorkQueue.StepStats stats) {
        var f = new Fixture(1, 1, 10);
        f.driver.overrideStats = stats;
        f.start(A);
        f.queue.pump();
        assertThrows(IllegalStateException.class, f.queue::pump);
        assertEquals(0, f.driver.finishCalls);
        assertEquals(1, f.queue.activeBuilds());
        f.queue.close();
    }

    static Stream<RollingMeshWorkQueue.StepStats> invalidStatistics() {
        return Stream.of(
                new RollingMeshWorkQueue.StepStats(2, 2, 3, 2, 10, 0, 1, false),
                new RollingMeshWorkQueue.StepStats(-1, 0, 3, 2, 10, 0, 1, false),
                new RollingMeshWorkQueue.StepStats(1, 2, 3, 2, 10, 0, 1, false),
                new RollingMeshWorkQueue.StepStats(0, 0, 3, 2, 10, 0, 1, false),
                new RollingMeshWorkQueue.StepStats(1, 1, 1, 2, 10, 0, 1, false),
                new RollingMeshWorkQueue.StepStats(1, 1, 3, 2, 10, 0, 1, true),
                new RollingMeshWorkQueue.StepStats(1, 1, 3, -1, 10, 0, 1, false),
                new RollingMeshWorkQueue.StepStats(1, 1, 3, 2, 9, 0, 1, false),
                new RollingMeshWorkQueue.StepStats(1, 1, 3, 2, 10, 1, 1, false),
                new RollingMeshWorkQueue.StepStats(1, 1, 3, 2, 10, 0, 3, false));
    }

    @Test void nullDriverReturnsDoNotLoseAnyPreviouslyRetainedOwner() {
        var f = new Fixture(1, 1, 10);
        f.driver.returnNullFinish = true;
        f.start(A); f.queue.pump(); f.queue.pump();
        assertThrows(NullPointerException.class, f.queue::pump);
        assertEquals(1, f.queue.activeBuilds());
        assertEquals(0, f.queue.completedSlots());
        f.queue.close();
    }

    @Test void timingMetricsAreCumulativeAndKeepFinishUploadOutsideSliceBudget() {
        var f = new Fixture(2, 2, 100);
        f.driver.callbackNanos = 5;
        f.completeOne();
        assertTrue(f.queue.publishCompleted((position, mesh) -> f.clock.advance(17)));
        f.queue.start(List.of(B), 2, 1, () -> true);
        f.queue.pump(); f.queue.pump(); f.queue.pump();
        f.queue.close();
        var m = f.queue.metrics();
        assertEquals(new RollingMeshWorkQueue.Timing(2, 6, 3), m.begin());
        assertEquals(new RollingMeshWorkQueue.Timing(2, 20, 10), m.slice());
        assertEquals(new RollingMeshWorkQueue.Timing(2, 14, 7), m.finishUpload());
        assertEquals(new RollingMeshWorkQueue.Timing(1, 17, 17), m.publication());
        assertEquals(new RollingMeshWorkQueue.Timing(2, 22, 11), m.buildClose());
        assertEquals(new RollingMeshWorkQueue.Timing(1, 13, 13), m.meshClose());
        assertEquals(4, m.reportedCallbacks());
        assertEquals(5, m.maxCallbackNanos());
        assertEquals(0, m.maxSoftOvershootNanos());
    }

    @Test void predicateExceptionsPoisonAndCleanupDoesNotConsultAnExpiredPredicate() {
        var f = new Fixture(1, 1, 10);
        boolean[] reject = {false};
        f.queue.start(List.of(A), 1, 1, () -> { if (reject[0]) throw new AssertionError("predicate"); return true; });
        f.queue.pump();
        reject[0] = true;
        assertThrows(AssertionError.class, f.queue::pump);
        f.queue.close();
        assertTrue(f.queue.isDrained());
    }

    @Test void closeBeforeStartAndRepeatedCloseAreSafeButNeverReopenTheQueue() {
        var f = new Fixture(1, 1, 10);
        f.queue.close(); f.queue.close();
        assertTrue(f.queue.isDrained());
        assertTrue(f.queue.isPoisoned());
        assertTrue(f.driver.events.isEmpty());
        assertThrows(IllegalStateException.class, () -> f.start(A));
        assertThrows(IllegalStateException.class, f.queue::pump);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void consumerAdoptingThenThrowingOrInvalidatingRetainsSafeIdempotentCleanup(boolean invalidate) {
        var f = new Fixture(1, 1, 10);
        f.completeOne();
        var adopted = new ArrayList<Mesh>();
        assertThrows(IllegalStateException.class, () -> f.queue.publishCompleted((position, mesh) -> {
            adopted.add(mesh);
            if (invalidate) f.current = false;
            else throw new IllegalStateException("after consumer adopted owner");
        }));
        assertEquals(1, adopted.size());
        assertEquals(1, f.queue.completedSlots());
        f.queue.close();
        assertTrue(adopted.getFirst().closed);
        // A production SceneMesh close is success-idempotent when the Session still has a reference.
        f.driver.closeMesh(adopted.getFirst());
        assertEquals(1, adopted.getFirst().closeAttempts);
    }

    @Test void reentrantCloseInsideConsumerRetainsSlotUntilCallbackReturnsAndExplicitCleanupRuns() {
        var f = new Fixture(1, 1, 10);
        f.completeOne();
        assertThrows(IllegalStateException.class, () -> f.queue.publishCompleted((position, mesh) -> {
            assertThrows(IllegalStateException.class, f.queue::close);
            assertFalse(mesh.closed);
            assertEquals(1, f.queue.completedSlots());
        }));
        assertEquals(1, f.queue.completedSlots());
        f.queue.close();
        assertTrue(f.queue.isDrained());
        assertTrue(f.driver.meshes.getFirst().closed);
    }

    @Test void postCloseClockFailureDoesNotRetrySuccessfullyClosedBuildAndStillClosesMesh() {
        var f = new Fixture(1, 1, 10);
        f.start(A); f.queue.pump(); f.queue.pump();
        f.driver.buildCloseFailure = new IllegalStateException("retain both");
        assertThrows(IllegalStateException.class, f.queue::pump);
        f.driver.buildCloseFailure = null;
        f.driver.after.put(Event.BUILD_CLOSE, () -> f.clock.failNext = true);
        assertThrows(IllegalStateException.class, f.queue::close);
        assertEquals(0, f.queue.activeBuilds());
        assertEquals(0, f.queue.completedSlots());
        assertTrue(f.driver.meshes.getFirst().closed);
        f.queue.close();
        assertEquals(2, f.driver.builds.getFirst().closeAttempts);
        assertEquals(1, f.driver.meshes.getFirst().closeAttempts);
    }

    @Test void nullBeginAndNullStepStatisticsPoisonWithoutLosingOwnership() {
        for (boolean nullBegin : new boolean[]{false, true}) {
            var f = new Fixture(1, 1, 10);
            f.driver.returnNullBegin = nullBegin;
            f.driver.returnNullStats = !nullBegin;
            f.start(A);
            if (!nullBegin) f.queue.pump();
            assertThrows(NullPointerException.class, f.queue::pump);
            assertEquals(nullBegin ? 0 : 1, f.queue.activeBuilds());
            assertTrue(f.queue.isPoisoned());
            f.queue.close();
            assertTrue(f.queue.isDrained());
        }
    }

    @Test void aChangedCallbackTotalCannotMasqueradeAsCompletion() {
        var f = new Fixture(3, 1, 10);
        f.start(A); f.queue.pump(); f.queue.pump();
        f.driver.overrideStats = new RollingMeshWorkQueue.StepStats(1, 2, 2, 1, 10, 0, 1, true);
        assertThrows(IllegalStateException.class, f.queue::pump);
        assertEquals(0, f.driver.finishCalls);
        f.queue.close();
    }

    @ParameterizedTest @ValueSource(ints = {1, 2})
    void startFencesBothBeforeAndAfterInstallingResourceFreeTargets(int failedCheck) {
        var f = new Fixture(1, 1, 10);
        int[] checks = {0};
        assertThrows(IllegalStateException.class, () -> f.queue.start(List.of(A), 1, 1,
                () -> ++checks[0] != failedCheck));
        assertTrue(f.queue.isPoisoned());
        assertEquals(0, f.queue.activeBuilds());
        assertTrue(f.driver.events.isEmpty());
        f.queue.close();
        assertTrue(f.queue.isDrained());
    }

    @Test void invalidGenerationsNeverBeginABatch() {
        var f = new Fixture(1, 1, 10);
        for (long generation : new long[]{Long.MIN_VALUE, -1, 0}) {
            assertThrows(IllegalArgumentException.class, () -> f.queue.start(List.of(A), generation, 1, () -> true));
            assertThrows(IllegalArgumentException.class, () -> f.queue.start(List.of(A), 1, generation, () -> true));
        }
        assertTrue(f.queue.isDrained());
        assertEquals(0, f.queue.metrics().batches());
        f.queue.close();
    }

    @Test void cumulativeTimingSaturatesInsteadOfWrappingAndUsesBoundedSnapshots() {
        var f = new Fixture(0, 1, 10);
        f.driver.after.put(Event.BEGIN, () -> f.clock.advance(Long.MAX_VALUE - 10));
        f.start(A, B);
        f.finishBatch(new ArrayList<>());
        var timing = f.queue.metrics().begin();
        assertEquals(2, timing.calls());
        assertEquals(Long.MAX_VALUE, timing.totalNanos());
        assertEquals(Long.MAX_VALUE - 7, timing.maxNanos());
        assertEquals(0, f.queue.queuedTargets());
        assertEquals(0, f.queue.activeBuilds());
        assertEquals(0, f.queue.completedSlots());
        f.queue.close();
    }

    private static Throwable failure(boolean error, String text) {
        return error ? new AssertionError(text) : new IllegalStateException(text);
    }
    private static void raise(Throwable failure) {
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
    }

    private enum Event { BEGIN, STEP, FINISH, BUILD_CLOSE, MESH_CLOSE }
    private static final class Clock implements LongSupplier {
        long now;
        boolean failNext;
        public long getAsLong() {
            if (failNext) { failNext = false; throw new IllegalStateException("clock failure"); }
            return now;
        }
        void advance(long nanos) { now += nanos; }
    }
    private static final class Build {
        final CubePos position;
        int completed, cpuOwned, gpuOwned, closeAttempts;
        Build(CubePos position) { this.position = position; }
    }
    private static final class Mesh {
        final int blocks;
        int closeAttempts;
        boolean closed;
        Mesh(int blocks) { this.blocks = blocks; }
    }
    private static final class FakeDriver implements RollingMeshWorkQueue.Driver<Build, Mesh> {
        final Clock clock;
        final int total;
        final List<String> events = new ArrayList<>();
        final List<Build> builds = new ArrayList<>();
        final List<Mesh> meshes = new ArrayList<>();
        final EnumMap<Event, Runnable> before = new EnumMap<>(Event.class), after = new EnumMap<>(Event.class);
        Runnable onBlock = () -> {};
        long callbackNanos = 1, lastSoftNanos;
        int modelCalls, finishCalls, finishAllocations = 3;
        boolean nativeCurrent = true, returnNullFinish, returnNullBegin, returnNullStats;
        Throwable finishFailure, buildCloseFailure, meshCloseFailure;
        RollingMeshWorkQueue.StepStats overrideStats;
        FakeDriver(Clock clock, int total) { this.clock = clock; this.total = total; }
        private void run(EnumMap<Event, Runnable> hooks, Event event) {
            var hook = hooks.get(event); if (hook != null) hook.run();
        }
        public Build begin(CubePos position) {
            events.add("begin"); run(before, Event.BEGIN);
            clock.advance(3);
            if (returnNullBegin) return null;
            var build = new Build(position);
            run(after, Event.BEGIN);
            builds.add(build);
            return build;
        }
        public RollingMeshWorkQueue.StepStats step(Build build, int maxCallbacks, long softNanos) {
            events.add("step"); run(before, Event.STEP);
            lastSoftNanos = softNanos;
            long start = clock.now;
            int initial = build.completed;
            if (total > 0) do {
                if (!nativeCurrent) throw new IllegalStateException("native stale before callback");
                build.cpuOwned = 1;
                modelCalls++;
                onBlock.run();
                clock.advance(callbackNanos);
                if (!nativeCurrent) throw new IllegalStateException("native stale after callback");
                build.completed++;
            } while (build.completed < total && build.completed - initial < maxCallbacks && clock.now - start < softNanos);
            run(after, Event.STEP);
            if (returnNullStats) return null;
            if (overrideStats != null) return overrideStats;
            long elapsed = clock.now - start;
            return new RollingMeshWorkQueue.StepStats(build.completed - initial, build.completed, total,
                    elapsed, softNanos, Math.max(0, elapsed - softNanos), total == 0 ? 0 : callbackNanos, build.completed == total);
        }
        public Mesh finish(Build build) {
            events.add("finish"); run(before, Event.FINISH); finishCalls++;
            if (build.completed != total) throw new AssertionError("premature finish");
            clock.advance(7);
            build.gpuOwned = finishAllocations;
            if (finishFailure != null) raise(finishFailure);
            if (returnNullFinish) return null;
            build.cpuOwned = build.gpuOwned = 0;
            var mesh = new Mesh(total);
            meshes.add(mesh);
            run(after, Event.FINISH);
            return mesh;
        }
        public void closeBuild(Build build) {
            events.add("build-close"); build.closeAttempts++; run(before, Event.BUILD_CLOSE);
            clock.advance(11);
            if (buildCloseFailure != null) raise(buildCloseFailure);
            build.cpuOwned = build.gpuOwned = 0;
            run(after, Event.BUILD_CLOSE);
        }
        public void closeMesh(Mesh mesh) {
            if (mesh.closed) return;
            events.add("mesh-close"); mesh.closeAttempts++; run(before, Event.MESH_CLOSE);
            clock.advance(13);
            if (meshCloseFailure != null) raise(meshCloseFailure);
            mesh.closed = true;
            run(after, Event.MESH_CLOSE);
        }
    }
    private static final class Fixture {
        final Clock clock = new Clock();
        final FakeDriver driver;
        final RollingMeshWorkQueue<Build, Mesh> queue;
        boolean current = true;
        Fixture(int total, int callbacks, long softNanos) {
            driver = new FakeDriver(clock, total);
            queue = new RollingMeshWorkQueue<>(driver, 28, callbacks, softNanos, clock);
        }
        void start(CubePos... positions) { queue.start(List.of(positions), 1, 1, () -> current); }
        void completeOne() {
            start(A);
            assertEquals(BEGIN, queue.pump());
            while (driver.builds.getFirst().completed < driver.total) assertEquals(STEP, queue.pump());
            if (driver.total == 0) assertEquals(STEP, queue.pump());
            assertEquals(FINISH, queue.pump());
        }
        void finishBatch(List<Mesh> accepted) {
            int frames = 0;
            while (!queue.isDrained()) {
                assertTrue(++frames < 10000, "Bounded fake workload must terminate");
                assertTrue(queue.queuedTargets() <= 28);
                assertTrue(queue.activeBuilds() <= 1);
                assertTrue(queue.completedSlots() <= 1);
                queue.pump();
                queue.publishCompleted((position, mesh) -> accepted.add(mesh));
            }
        }
    }
}
