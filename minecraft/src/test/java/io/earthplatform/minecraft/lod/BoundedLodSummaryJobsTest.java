package io.earthplatform.minecraft.lod;

import io.earthplatform.core.WorldIdentity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BoundedLodSummaryJobsTest {
    private static final WorldIdentity WORLD = new WorldIdentity(17, "analytic", "v1", "none-v1", "palette-v1");
    private static final LodJobStamp STAMP = new LodJobStamp(1, 2, 3);

    private record Data(String content, long declaredBytes) implements BoundedLodSummaryJobs.Payload { }
    private static LodSummaryKey key(long y) { return key(y, 0, "mask-v1"); }
    private static LodSummaryKey key(long y, long revision, String variant) {
        return new LodSummaryKey(WORLD, "analytic-volumetric-v1",
                new LodSummaryKey.Volume(-32, y, 48, -16, y + 16, 64), 2, revision, variant);
    }
    private static BoundedLodSummaryJobs.Limits limits(int running, int queued, int items, long bytes) {
        return new BoundedLodSummaryJobs.Limits(running, queued, 3, items, bytes, 100);
    }
    private static final class ManualExecutor implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable task) { tasks.addLast(task); }
        void runNext() { tasks.removeFirst().run(); }
        void runAll() { while (!tasks.isEmpty()) runNext(); }
    }
    private record Pending(LodSummaryKey key, BoundedLodSummaryJobs.Cancellation cancellation,
                           long budget, CompletableFuture<Data> future) { }
    private static final class Source implements BoundedLodSummaryJobs.Source<Data> {
        final List<Pending> calls = new ArrayList<>();
        @Override public CompletableFuture<Data> load(LodSummaryKey key,
                BoundedLodSummaryJobs.Cancellation cancellation, long budget) {
            var pending = new Pending(key, cancellation, budget, new CompletableFuture<>());
            calls.add(pending);
            return pending.future();
        }
        void complete(int index, long bytes) { calls.get(index).future().complete(new Data("result-" + index, bytes)); }
    }

    @Test void exactThreeDimensionalIdentityAndAllWorldInputsParticipate() {
        var original = key(-2049);
        assertEquals(original, key(-2049));
        assertNotEquals(original, key(-2048));
        assertNotEquals(original, key(0));
        assertNotEquals(original, key(-2049, 1, "mask-v1"));
        assertNotEquals(original, key(-2049, 0, "mask-v2"));
        assertNotEquals(original, new LodSummaryKey(WORLD, "other-provider", original.volume(), 2, 0, "mask-v1"));
        assertNotEquals(original, new LodSummaryKey(WORLD, original.providerIdentity(), original.volume(), 3, 0, "mask-v1"));
        for (WorldIdentity world : List.of(
                new WorldIdentity(18, "analytic", "v1", "none-v1", "palette-v1"),
                new WorldIdentity(17, "other", "v1", "none-v1", "palette-v1"),
                new WorldIdentity(17, "analytic", "v2", "none-v1", "palette-v1"),
                new WorldIdentity(17, "analytic", "v1", "dataset-v2", "palette-v1"),
                new WorldIdentity(17, "analytic", "v1", "none-v1", "palette-v2"))) {
            assertNotEquals(original, new LodSummaryKey(world, original.providerIdentity(), original.volume(), 2, 0, "mask-v1"));
        }
        // The generic key permits arbitrary native Y, with no renderer's 2048-block page restriction.
        assertEquals(-1_000_001, key(-1_000_001).volume().minY());
        assertNotEquals(original, new LodSummaryKey(WORLD, original.providerIdentity(),
                new LodSummaryKey.Volume(-31, -2049, 48, -15, -2033, 64), 2, 0, "mask-v1"));
        assertNotEquals(original, new LodSummaryKey(WORLD, original.providerIdentity(),
                new LodSummaryKey.Volume(-32, -2049, 47, -16, -2033, 63), 2, 0, "mask-v1"));
    }

    @Test void rejectsInvalidUnboundedOrAmbiguousKeysAndBudgets() {
        assertThrows(IllegalArgumentException.class, () -> new LodSummaryKey.Volume(0, 0, 0, 1, 0, 1));
        assertThrows(ArithmeticException.class, () -> new LodSummaryKey.Volume(Long.MIN_VALUE, 0, 0, Long.MAX_VALUE, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> key(0, -1, "mask"));
        assertThrows(IllegalArgumentException.class, () -> key(0, 0, " "));
        assertThrows(IllegalArgumentException.class, () -> key(0, 0, "x".repeat(1025)));
        assertThrows(IllegalArgumentException.class, () -> new LodJobStamp(0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> limits(0, 1, 1, 100));
        assertThrows(ArithmeticException.class, () -> new BoundedLodSummaryJobs.Limits(2, 1, 1, 1, 1, Long.MAX_VALUE));
    }

    @Test void analyticCaveOverhangAndEditResultsNeverAliasAcrossYOrRevision() {
        var executor = new ManualExecutor();
        AtomicInteger calls = new AtomicInteger();
        try (var jobs = new BoundedLodSummaryJobs<Data>((key, cancellation, maxBytes) -> {
            calls.incrementAndGet();
            String occupancy = key.editRevision() > 0 ? "edited" : key.volume().minY() < 0 ? "cave" : "overhang";
            return CompletableFuture.completedFuture(new Data(occupancy, 8));
        }, executor, limits(2, 4, 8, 100))) {
            var cave = jobs.request(key(-32), STAMP);
            var overhang = jobs.request(key(32), STAMP);
            var edited = jobs.request(key(-32, 1, "mask-v1"), STAMP);
            executor.runAll();
            assertEquals("cave", cave.future().join().payload().content());
            assertEquals("overhang", overhang.future().join().payload().content());
            assertEquals("edited", edited.future().join().payload().content());
            assertEquals(3, calls.get());
            assertEquals(3, jobs.stats().cachedItems());
        }
    }

    @Test void deduplicatesSourceButPreservesIndependentStampAndCancellation() {
        var source = new Source(); var executor = new ManualExecutor();
        try (var jobs = new BoundedLodSummaryJobs<>(source, executor, limits(1, 2, 4, 100))) {
            var first = jobs.request(key(0), STAMP);
            var secondStamp = new LodJobStamp(1, 99, 4);
            var second = jobs.request(key(0), secondStamp);
            assertFalse(first.future().isDone());
            assertEquals(0, source.calls.size());
            executor.runNext();
            assertEquals(1, source.calls.size());
            assertEquals(100, source.calls.getFirst().budget());
            first.close();
            assertTrue(first.future().isCancelled());
            assertFalse(source.calls.getFirst().cancellation().isCancelled());
            source.complete(0, 10);
            assertEquals(secondStamp, second.future().join().stamp());
            assertTrue(second.publishIfCurrent(secondStamp, value -> assertEquals("result-0", value.content())));
            assertFalse(second.publishIfCurrent(STAMP, value -> fail()));
            assertFalse(first.publishIfCurrent(STAMP, value -> fail()));
            assertEquals(1, jobs.stats().deduplicated());
        }
    }

    @Test void cancellationIgnoringSourceNeverFreesItsRunningSlotEarly() {
        var source = new Source(); var executor = new ManualExecutor();
        try (var jobs = new BoundedLodSummaryJobs<>(source, executor, limits(1, 1, 4, 100))) {
            var old = jobs.request(key(0), STAMP);
            executor.runNext();
            old.close();
            assertTrue(source.calls.getFirst().cancellation().isCancelled());
            assertFalse(source.calls.getFirst().future().isDone(), "Source future must never be cancelled by the scheduler");
            var replacement = jobs.request(key(0), new LodJobStamp(1, 3, 3));
            assertEquals(1, jobs.stats().running());
            assertEquals(1, jobs.stats().queued());
            assertThrows(RejectedExecutionException.class, () -> jobs.request(key(16), STAMP));
            assertTrue(executor.tasks.isEmpty());
            source.complete(0, 10);
            assertEquals(0, jobs.stats().cachedItems());
            assertEquals(1, jobs.stats().running());
            assertEquals(0, jobs.stats().queued());
            executor.runNext();
            source.complete(1, 10);
            assertEquals("result-1", replacement.future().join().payload().content());
            assertEquals(1, jobs.stats().cancelled());
        }
    }

    @Test void cancellationBeforeExecutorDispatchSkipsSourceAndQueuedCancellationReleasesAdmission() {
        var source = new Source(); var executor = new ManualExecutor();
        try (var jobs = new BoundedLodSummaryJobs<>(source, executor, limits(1, 1, 4, 100))) {
            var running = jobs.request(key(0), STAMP);
            var queued = jobs.request(key(16), STAMP);
            queued.future().cancel(false);
            assertEquals(0, jobs.stats().queued());
            var replacement = jobs.request(key(32), STAMP);
            running.close();
            assertEquals(1, jobs.stats().running());
            executor.runNext();
            assertEquals(0, source.calls.size());
            executor.runNext();
            source.complete(0, 10);
            assertEquals(key(32), replacement.future().join().key());
            assertEquals(2, jobs.stats().cancelled());
        }
    }

    @Test void subscriberAndQueueBackpressureAreHardAndRetryable() {
        var source = new Source(); var executor = new ManualExecutor();
        try (var jobs = new BoundedLodSummaryJobs<>(source, executor,
                new BoundedLodSummaryJobs.Limits(1, 0, 2, 1, 100, 100))) {
            var one = jobs.request(key(0), STAMP);
            var two = jobs.request(key(0), STAMP);
            assertThrows(RejectedExecutionException.class, () -> jobs.request(key(0), STAMP));
            assertThrows(RejectedExecutionException.class, () -> jobs.request(key(16), STAMP));
            two.close();
            var three = jobs.request(key(0), STAMP);
            executor.runNext(); source.complete(0, 10);
            assertSame(one.future().join().payload(), three.future().join().payload());
            assertEquals(2, jobs.stats().rejected());
        }
    }

    @Test void sameKeyABADoesNotRemoveOrPublishTheNewGeneration() {
        var source = new Source(); var executor = new ManualExecutor();
        try (var jobs = new BoundedLodSummaryJobs<>(source, executor, limits(2, 2, 4, 100))) {
            var old = jobs.request(key(0), STAMP);
            executor.runNext();
            jobs.invalidateAll();
            var current = jobs.request(key(0), STAMP);
            executor.runNext();
            assertEquals(2, jobs.stats().running());
            source.complete(0, 10);
            assertEquals(0, jobs.stats().cachedItems());
            var sameCurrent = jobs.request(key(0), new LodJobStamp(1, 3, 3));
            assertTrue(executor.tasks.isEmpty(), "Old completion must not remove the current key mapping");
            source.complete(1, 20);
            assertTrue(old.future().isCancelled());
            assertFalse(old.publishIfCurrent(STAMP, ignored -> fail()));
            assertEquals(1, current.future().join().generation());
            assertSame(current.future().join().payload(), sameCurrent.future().join().payload());
            assertEquals(20, jobs.stats().cachedBytes());
        }
    }

    @Test void lateOldResultCannotReplaceNewCachedPayload() {
        var source = new Source(); var executor = new ManualExecutor();
        try (var jobs = new BoundedLodSummaryJobs<>(source, executor, limits(2, 2, 4, 100))) {
            jobs.request(key(0), STAMP); executor.runNext(); jobs.invalidateAll();
            jobs.request(key(0), STAMP); executor.runNext();
            source.complete(1, 20); source.complete(0, 10);
            var hit = jobs.request(key(0), STAMP);
            assertEquals("result-1", hit.future().join().payload().content());
            assertEquals(20, jobs.stats().cachedBytes());
            assertEquals(1, jobs.stats().completed());
        }
    }

    @Test void resourceReloadReusesCpuPayloadOnlyUnderFreshStampAndInvalidationFencesCompletedLeases() {
        var source = new Source(); var executor = new ManualExecutor();
        try (var jobs = new BoundedLodSummaryJobs<>(source, executor, limits(1, 1, 4, 100))) {
            var old = jobs.request(key(0), STAMP); executor.runNext(); source.complete(0, 10);
            var newStamp = new LodJobStamp(STAMP.epoch(), STAMP.lease(), STAMP.resourceGeneration() + 1);
            var fresh = jobs.request(key(0), newStamp);
            assertSame(old.future().join().payload(), fresh.future().join().payload());
            assertEquals(1, source.calls.size());
            assertFalse(old.publishIfCurrent(newStamp, ignored -> fail()));
            assertTrue(fresh.publishIfCurrent(newStamp, ignored -> { }));
            assertFalse(fresh.isCurrent(new LodJobStamp(2, 2, 4)));
            assertFalse(fresh.isCurrent(new LodJobStamp(1, 3, 4)));
            jobs.invalidateAll();
            assertFalse(fresh.publishIfCurrent(newStamp, ignored -> fail()));
            assertFalse(old.isCurrent(STAMP));
            assertEquals(0, jobs.stats().cachedBytes());
        }
    }

    @Test void lruEvictsByDeclaredBytesAndChargesOnceForDeduplicatedPayloads() {
        var source = new Source(); var executor = new ManualExecutor();
        try (var jobs = new BoundedLodSummaryJobs<>(source, executor, limits(2, 2, 10, 10))) {
            jobs.request(key(0), STAMP); jobs.request(key(0), STAMP);
            executor.runNext(); source.complete(0, 4);
            jobs.request(key(16), STAMP); executor.runNext(); source.complete(1, 4);
            assertEquals(8, jobs.stats().cachedBytes());
            jobs.request(key(0), STAMP); // A is now most recently used.
            jobs.request(key(32), STAMP); executor.runNext(); source.complete(2, 4);
            assertEquals(8, jobs.stats().cachedBytes());
            assertEquals(2, jobs.stats().cachedItems());
            assertEquals(1, jobs.stats().evicted());
            assertTrue(jobs.request(key(0), STAMP).future().isDone());
            assertFalse(jobs.request(key(16), STAMP).future().isDone());
        }
    }

    @Test void zeroBytePayloadsStillObeyItemBudgetAndDisabledCacheRetainsNothing() {
        var source = new Source(); var executor = new ManualExecutor();
        try (var jobs = new BoundedLodSummaryJobs<>(source, executor, limits(1, 1, 2, 0))) {
            for (int i = 0; i < 3; i++) {
                jobs.request(key(i * 16), STAMP); executor.runNext(); source.complete(i, 0);
            }
            assertEquals(2, jobs.stats().cachedItems());
            assertEquals(0, jobs.stats().cachedBytes());
            assertEquals(1, jobs.stats().evicted());
        }
        try (var jobs = new BoundedLodSummaryJobs<>(source, executor, limits(1, 1, 0, 100))) {
            jobs.request(key(0), STAMP); executor.runNext(); source.complete(3, 10);
            assertEquals(0, jobs.stats().cachedItems());
        }
    }

    @Test void resultLargerThanCacheButWithinPerResultLimitSucceedsWithoutEvictingOthers() {
        var source = new Source(); var executor = new ManualExecutor();
        try (var jobs = new BoundedLodSummaryJobs<>(source, executor, limits(1, 1, 2, 10))) {
            jobs.request(key(0), STAMP); executor.runNext(); source.complete(0, 4);
            var large = jobs.request(key(16), STAMP); executor.runNext(); source.complete(1, 11);
            assertEquals(11, large.future().join().payload().declaredBytes());
            assertEquals(1, jobs.stats().cachedItems());
            assertEquals(4, jobs.stats().cachedBytes());
            assertEquals(0, jobs.stats().evicted());
        }
    }

    @Test void failuresNullAndOversizeResultsReleaseSlotsAndNeverReceiveCacheCharge() {
        var source = new Source(); var executor = new ManualExecutor();
        try (var jobs = new BoundedLodSummaryJobs<>(source, executor, limits(1, 1, 4, 100))) {
            for (int i = 0; i < 5; i++) {
                var lease = jobs.request(key(0), STAMP); executor.runNext();
                switch (i) {
                    case 0 -> source.calls.get(i).future().completeExceptionally(new IllegalStateException("source failed"));
                    case 1 -> source.calls.get(i).future().complete(null);
                    case 2 -> source.complete(i, 101);
                    case 3 -> source.complete(i, -1);
                    case 4 -> source.calls.get(i).future().cancel(false);
                }
                assertTrue(lease.future().isCompletedExceptionally());
                assertFalse(lease.publishIfCurrent(STAMP, ignored -> fail()));
                assertEquals(0, jobs.stats().cachedBytes());
                assertEquals(0, jobs.stats().cachedItems());
                assertEquals(0, jobs.stats().running());
            }
            var retry = jobs.request(key(0), STAMP); executor.runNext(); source.complete(5, 100);
            assertEquals(100, retry.future().join().payload().declaredBytes());
            assertEquals(5, jobs.stats().failed());
            assertEquals(100, jobs.stats().cachedBytes());
        }
    }

    @Test void throwingSourceAndThrowingDeclaredSizeAreContainedAndRetryable() {
        var executor = new ManualExecutor(); var attempt = new AtomicInteger();
        try (var jobs = new BoundedLodSummaryJobs<BoundedLodSummaryJobs.Payload>((key, cancellation, maxBytes) -> {
            return switch (attempt.getAndIncrement()) {
                case 0 -> throw new IllegalStateException("source invocation");
                case 1 -> null;
                case 2 -> CompletableFuture.completedFuture(() -> { throw new IllegalStateException("declared size"); });
                default -> CompletableFuture.completedFuture(new Data("retry", 10));
            };
        }, executor, limits(1, 0, 2, 100))) {
            for (int i = 0; i < 3; i++) {
                var lease = jobs.request(key(0), STAMP); executor.runNext();
                assertThrows(CompletionException.class, () -> lease.future().join());
                assertEquals(0, jobs.stats().running());
                assertEquals(0, jobs.stats().cachedBytes());
            }
            var retry = jobs.request(key(0), STAMP); executor.runNext();
            assertEquals(10, retry.future().join().payload().declaredBytes());
        }
    }

    @Test void inlineExecutorAndRejectionNeverInvokeSourceAndCanBeRetried() {
        var source = new Source();
        try (var jobs = new BoundedLodSummaryJobs<>(source, Runnable::run, limits(1, 1, 2, 100))) {
            var lease = jobs.request(key(0), STAMP);
            assertInstanceOf(RejectedExecutionException.class,
                    assertThrows(CompletionException.class, () -> lease.future().join()).getCause());
            assertEquals(0, source.calls.size());
            assertEquals(0, jobs.stats().running());
        }
        var executor = new ManualExecutor(); var reject = new AtomicBoolean(true);
        try (var jobs = new BoundedLodSummaryJobs<>(source, task -> {
            if (reject.get()) throw new RejectedExecutionException("saturated");
            executor.execute(task);
        }, limits(1, 0, 2, 100))) {
            assertTrue(jobs.request(key(0), STAMP).future().isCompletedExceptionally());
            assertEquals(0, jobs.stats().cachedBytes());
            reject.set(false);
            var retry = jobs.request(key(0), STAMP); executor.runNext(); source.complete(0, 10);
            assertEquals(10, retry.future().join().payload().declaredBytes());
        }
    }

    @Test void invalidationClearsQueuedJobsButChargesLiveWorkUntilItReallyStops() {
        var source = new Source(); var executor = new ManualExecutor();
        try (var jobs = new BoundedLodSummaryJobs<>(source, executor, limits(1, 1, 2, 100))) {
            var old = jobs.request(key(0), STAMP); executor.runNext();
            var waiting = jobs.request(key(16), STAMP);
            jobs.invalidateAll();
            assertTrue(old.future().isCancelled()); assertTrue(waiting.future().isCancelled());
            assertEquals(1, jobs.stats().running()); assertEquals(0, jobs.stats().queued());
            jobs.request(key(32), STAMP);
            assertEquals(1, jobs.stats().queued());
            source.complete(0, 10);
            assertEquals(0, jobs.stats().cachedBytes());
            executor.runNext(); source.complete(1, 20);
            assertEquals(20, jobs.stats().cachedBytes());
        }
    }

    @Test void closeCancelsLeasesAndRejectsRequestsButDoesNotReleaseUnsettledWorkOrShutExecutor() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            var invoked = new CountDownLatch(1);
            var sourceFuture = new CompletableFuture<Data>();
            var token = new AtomicReference<BoundedLodSummaryJobs.Cancellation>();
            var caller = Thread.currentThread();
            var worker = new AtomicReference<Thread>();
            var jobs = new BoundedLodSummaryJobs<Data>((key, cancellation, budget) -> {
                token.set(cancellation); worker.set(Thread.currentThread()); invoked.countDown(); return sourceFuture;
            }, executor, limits(1, 1, 2, 100));
            var lease = jobs.request(key(0), STAMP);
            assertTrue(invoked.await(3, TimeUnit.SECONDS));
            assertNotSame(caller, worker.get());
            var queued = jobs.request(key(16), STAMP);
            jobs.close(); jobs.close();
            assertTrue(token.get().isCancelled());
            assertThrows(java.util.concurrent.CancellationException.class, () -> token.get().throwIfCancelled());
            assertTrue(lease.future().isCancelled()); assertTrue(queued.future().isCancelled());
            assertEquals(1, jobs.stats().running());
            assertEquals(0, jobs.stats().queued());
            assertThrows(RejectedExecutionException.class, () -> jobs.request(key(32), STAMP));
            sourceFuture.complete(new Data("late", 10));
            // The source-entry latch fires before load() returns and before invoke() attaches
            // its completion callback. Completing the source may therefore precede callback
            // registration. Wait for physical settlement, keeping all pre-settlement slot and
            // cancellation assertions above intact; a stuck worker still fails within 3s.
            long settledDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (jobs.stats().running() != 0 && System.nanoTime() < settledDeadline) Thread.sleep(1);
            assertEquals(0, jobs.stats().running(), "Physical source settlement completed within 3s");
            assertEquals(0, jobs.stats().cachedItems());
            assertEquals(7, executor.submit(() -> 7).get(3, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }

    @Test void externalFutureCompletionCannotForgeGuardedPublication() {
        var source = new Source(); var executor = new ManualExecutor();
        try (var jobs = new BoundedLodSummaryJobs<>(source, executor, limits(1, 1, 2, 100))) {
            var lease = jobs.request(key(0), STAMP);
            lease.future().complete(new BoundedLodSummaryJobs.Result<>(key(16), STAMP, 0, new Data("forged", 1)));
            assertFalse(lease.publishIfCurrent(STAMP, ignored -> fail()));
            executor.runNext(); source.complete(0, 10);
            assertTrue(lease.publishIfCurrent(STAMP, value -> assertEquals("result-0", value.content())));
        }
    }

    @Test void successorIsScheduledBeforeCallingPotentiallyBlockingClientContinuations() {
        var source = new Source(); var executor = new ManualExecutor();
        try (var jobs = new BoundedLodSummaryJobs<>(source, executor, limits(1, 1, 2, 100))) {
            var first = jobs.request(key(0), STAMP); executor.runNext();
            var next = jobs.request(key(16), STAMP);
            var continuation = first.future().thenRun(() -> {
                assertEquals(1, executor.tasks.size(), "A client continuation must not delay successor dispatch");
                executor.runNext(); source.complete(1, 10);
                assertTrue(next.future().isDone());
            });
            source.complete(0, 10);
            continuation.join();
            assertEquals(2, jobs.stats().completed());
        }
    }

    @Test void repeatedInvalidationAndQueuedRetryCannotAccumulateOrphanWork() {
        var source = new Source(); var executor = new ManualExecutor();
        try (var jobs = new BoundedLodSummaryJobs<>(source, executor, limits(1, 1, 2, 100))) {
            jobs.request(key(0), STAMP); executor.runNext();
            for (int i = 0; i < 100; i++) {
                jobs.invalidateAll();
                var queued = jobs.request(key(i * 16), STAMP);
                assertEquals(1, jobs.stats().running());
                assertEquals(1, jobs.stats().queued());
                assertEquals(1, source.calls.size());
                assertTrue(executor.tasks.isEmpty());
                queued.close();
                assertEquals(0, jobs.stats().queued());
            }
            source.complete(0, 10);
            assertEquals(0, jobs.stats().running());
            assertEquals(0, jobs.stats().cachedBytes());
            var retry = jobs.request(key(0), STAMP); executor.runNext(); source.complete(1, 10);
            assertEquals(100, retry.future().join().generation());
        }
    }

    @Test void retainedCallerFuturesDoNotCreateAnUnboundedSchedulerResultOrErrorQueue() {
        var source = new Source(); var executor = new ManualExecutor();
        var retained = new ArrayList<CompletableFuture<BoundedLodSummaryJobs.Result<Data>>>();
        try (var jobs = new BoundedLodSummaryJobs<>(source, executor, limits(1, 0, 2, 10))) {
            for (int i = 0; i < 40; i++) {
                var lease = jobs.request(key(i * 16), STAMP); retained.add(lease.future()); executor.runNext();
                if (i % 2 == 0) source.complete(i, 5);
                else source.calls.get(i).future().completeExceptionally(new IllegalArgumentException("failure-" + i));
                assertEquals(0, jobs.stats().running());
                assertTrue(jobs.stats().cachedBytes() <= 10);
                assertTrue(jobs.stats().cachedItems() <= 2);
            }
            assertEquals(20, jobs.stats().failed());
            assertEquals(20, jobs.stats().completed());
            assertEquals(2, jobs.stats().cachedItems());
            assertEquals(10, jobs.stats().cachedBytes());
            // These are deliberately caller-owned references, explicitly outside cache accounting.
            assertEquals("result-0", retained.getFirst().join().payload().content());
            assertTrue(retained.get(1).isCompletedExceptionally());
            jobs.invalidateAll();
            assertEquals(0, jobs.stats().cachedItems());
            assertEquals(0, jobs.stats().cachedBytes());
        }
    }

    @Test void publicationAndGlobalInvalidationAreAtomicWithRespectToEachOther() throws Exception {
        var source = new Source(); var manual = new ManualExecutor();
        var workers = Executors.newFixedThreadPool(2);
        try (var jobs = new BoundedLodSummaryJobs<>(source, manual, limits(1, 1, 2, 100))) {
            var lease = jobs.request(key(0), STAMP); manual.runNext(); source.complete(0, 10);
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var invalidating = new CountDownLatch(1);
            var publication = workers.submit(() -> lease.publishIfCurrent(STAMP, payload -> {
                entered.countDown();
                try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { throw new AssertionError(error); }
            }));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var invalidation = workers.submit(() -> { invalidating.countDown(); jobs.invalidateAll(); });
            assertTrue(invalidating.await(3, TimeUnit.SECONDS));
            assertFalse(invalidation.isDone());
            release.countDown();
            assertTrue(publication.get(3, TimeUnit.SECONDS));
            invalidation.get(3, TimeUnit.SECONDS);
            assertFalse(lease.publishIfCurrent(STAMP, ignored -> fail()));
            assertEquals(0, jobs.stats().cachedBytes());
        } finally { workers.shutdownNow(); }
    }
}
