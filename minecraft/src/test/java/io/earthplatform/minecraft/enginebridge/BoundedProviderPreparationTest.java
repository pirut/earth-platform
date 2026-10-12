package io.earthplatform.minecraft.enginebridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static io.earthplatform.minecraft.enginebridge.BoundedProviderPreparation.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure JDK/JUnit tests: no registries, Minecraft, natives, GL, or process-runtime shutdown. */
class BoundedProviderPreparationTest {
    private static final Stamp STAMP = new Stamp(11, 23);
    private final List<Pool> pools = new ArrayList<>();
    private final List<CountDownLatch> gates = new ArrayList<>();

    private Pool pool() { return pool(new Pool(2, "test-provider-preparation")); }
    private Pool pool(Pool pool) { pools.add(pool); return pool; }
    private CountDownLatch gate() { CountDownLatch gate = new CountDownLatch(1); gates.add(gate); return gate; }

    @AfterEach void cleanup() throws Exception {
        gates.forEach(CountDownLatch::countDown);
        pools.forEach(Pool::close);
        for (Pool pool : pools) await(() -> pool.diagnostics().outstanding() == 0);
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) fail("Timed out waiting for bounded preparation");
            Thread.sleep(1);
        }
    }

    private static <T> Ticket<T> submitEventually(Pool pool, Stamp stamp, Source<T> source) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try { return pool.submit(stamp, source); }
            catch (RejectedExecutionException busy) {
                if (pool.diagnostics().closed() || System.nanoTime() >= deadline) throw busy;
                Thread.sleep(1);
            }
        }
    }

    private static void bounds(Pool pool) {
        Diagnostics d = pool.diagnostics();
        assertTrue(d.running() >= 0 && d.running() <= d.outstanding());
        assertTrue(d.outstanding() <= d.maximumOutstanding());
        assertTrue(d.completedUnclaimed() >= 0 && d.completedUnclaimed() <= d.outstanding());
        assertTrue(d.running() + d.completedUnclaimed() <= d.outstanding());
        assertEquals(0, d.queued());
    }

    @Test void daemonWorkerPublishesOnceOnlyForExactCurrentIdentity() throws Exception {
        Pool pool = pool();
        Thread caller = Thread.currentThread();
        AtomicReference<Thread> worker = new AtomicReference<>();
        Object value = new Object();
        Ticket<Object> ticket = pool.submit(STAMP, cancellation -> {
            worker.set(Thread.currentThread()); return value;
        });
        await(() -> ticket.state() == State.READY);
        assertNotSame(caller, worker.get());
        assertTrue(worker.get().isDaemon());
        assertTrue(worker.get().getName().startsWith("test-provider-preparation-"));
        assertEquals(STAMP, ticket.stamp());
        assertTrue(ticket.physicalSettled());
        assertFalse(ticket.completionAcknowledged());
        assertEquals(1, pool.diagnostics().completedUnclaimed());
        assertEquals(Optional.empty(), ticket.takeReady(new Stamp(12, 23)));
        assertEquals(Optional.empty(), ticket.takeReady(new Stamp(11, 24)));
        assertEquals(State.READY, ticket.state());
        assertEquals(1, pool.diagnostics().outstanding());
        assertSame(value, ticket.takeReady(STAMP).orElseThrow());
        assertEquals(State.CLAIMED, ticket.state());
        assertTrue(ticket.completionAcknowledged());
        assertTrue(ticket.takeReady(STAMP).isEmpty());
        ticket.close(); ticket.close();
        assertEquals(State.CLAIMED, ticket.state(), "Late close cannot revoke transferred ownership");
        assertFalse(ticket.cancellationRequested());
        assertEquals(0, pool.diagnostics().outstanding());
        bounds(pool);
        for (var method : Ticket.class.getMethods())
            assertFalse(Future.class.isAssignableFrom(method.getReturnType()), "No externally completable future");
    }

    @Test void cancelledButStillRunningSourceKeepsItsPhysicalSlot() throws Exception {
        Pool pool = pool();
        CountDownLatch started = new CountDownLatch(2), finishA = gate(), finishB = gate();
        AtomicReference<Cancellation> cancellationA = new AtomicReference<>();
        Ticket<String> a = pool.submit(STAMP, cancellation -> {
            cancellationA.set(cancellation); started.countDown(); finishA.await(); return "late A";
        });
        Ticket<String> b = pool.submit(new Stamp(11, 24), cancellation -> {
            started.countDown(); finishB.await(); return "B";
        });
        assertTrue(started.await(10, TimeUnit.SECONDS));
        a.close(); a.close();
        assertTrue(cancellationA.get().isCancelled());
        assertEquals(State.CANCELLED, a.state());
        assertTrue(a.completionAcknowledged());
        assertFalse(a.physicalSettled());
        assertEquals(2, pool.diagnostics().running());
        AtomicInteger rejectedSourceCalls = new AtomicInteger();
        assertThrows(RejectedExecutionException.class, () -> pool.submit(new Stamp(12, 25), cancellation -> {
            rejectedSourceCalls.incrementAndGet(); return "must not run";
        }));
        assertEquals(0, rejectedSourceCalls.get());
        assertEquals(2, pool.diagnostics().outstanding());
        finishA.countDown();
        await(a::physicalSettled);
        assertEquals(1, pool.diagnostics().outstanding());
        assertTrue(a.takeReady(STAMP).isEmpty(), "Cancellation must drop a late immutable result");
        assertTrue(a.failure().isEmpty());
        Ticket<String> c = submitEventually(pool, new Stamp(12, 25), cancellation -> "C");
        await(() -> c.state() == State.READY);
        assertEquals("C", c.takeReady(new Stamp(12, 25)).orElseThrow());
        finishB.countDown(); await(() -> b.state() == State.READY); b.close();
        assertEquals(0, pool.diagnostics().outstanding());
        assertEquals(2, pool.diagnostics().cancelled());
        bounds(pool);
    }

    @Test void completedUnclaimedResultsAndFailuresShareTheTwoSlotBudget() throws Exception {
        Pool pool = pool();
        Ticket<String> ready = pool.submit(STAMP, cancellation -> "provider");
        IllegalStateException problem = new IllegalStateException("offline pack invalid");
        Ticket<String> failed = pool.submit(new Stamp(11, 24), cancellation -> { throw problem; });
        await(() -> ready.physicalSettled() && failed.physicalSettled());
        assertEquals(State.FAILED, failed.state());
        assertSame(problem, failed.failure().orElseThrow());
        assertTrue(failed.takeReady(new Stamp(11, 24)).isEmpty());
        assertEquals(0, pool.diagnostics().running());
        assertEquals(2, pool.diagnostics().completedUnclaimed());
        assertThrows(RejectedExecutionException.class, () -> pool.submit(new Stamp(12, 25), c -> "third"));
        assertEquals(2, pool.diagnostics().outstanding(), "Reading a failure does not acknowledge it");
        failed.close();
        assertTrue(failed.failure().isEmpty(), "Closing clears retained Throwable and its references");
        assertTrue(failed.completionAcknowledged());
        assertEquals(1, pool.diagnostics().completedUnclaimed());
        Ticket<String> replacement = submitEventually(pool, new Stamp(12, 25), cancellation -> "replacement");
        await(replacement::physicalSettled);
        assertEquals(2, pool.diagnostics().completedUnclaimed());
        assertEquals("provider", ready.takeReady(STAMP).orElseThrow());
        replacement.close();
        assertEquals(0, pool.diagnostics().outstanding());
        assertEquals(3, pool.diagnostics().settled());
        assertEquals(1, pool.diagnostics().failed());
        bounds(pool);
    }

    @Test void cancellationBeforeWorkerEntryDoesNotInvokeSourceOrReleaseEarly() throws Exception {
        CountDownLatch startWorker = gate();
        ThreadFactory delayed = task -> new Thread(() -> {
            try { startWorker.await(); }
            catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
            task.run();
        });
        Pool pool = pool(new Pool(1, delayed));
        AtomicInteger calls = new AtomicInteger();
        Ticket<String> ticket = pool.submit(STAMP, cancellation -> { calls.incrementAndGet(); return "unexpected"; });
        ticket.close();
        assertEquals(0, pool.diagnostics().running());
        assertEquals(1, pool.diagnostics().outstanding());
        assertFalse(ticket.physicalSettled());
        assertThrows(RejectedExecutionException.class, () -> pool.submit(new Stamp(12, 24), c -> "too soon"));
        pool.close();
        assertFalse(ticket.physicalSettled(), "Pool close cannot settle a dispatched worker by assumption");
        startWorker.countDown();
        await(ticket::physicalSettled);
        assertEquals(0, calls.get());
        assertEquals(0, pool.diagnostics().outstanding());
        assertEquals(1, pool.diagnostics().settled());
        bounds(pool);
    }

    @Test void isolatedPoolCloseIsNonblockingAndRejectsWithoutRetainingAnotherOwner() throws Exception {
        Pool pool = pool();
        CountDownLatch started = gate(), finish = gate();
        Ticket<String> ticket = pool.submit(STAMP, cancellation -> {
            started.countDown(); finish.await(); return "ignored cancellation";
        });
        assertTrue(started.await(10, TimeUnit.SECONDS));
        pool.close(); pool.close();
        assertTrue(pool.diagnostics().closed());
        assertEquals(State.CANCELLED, ticket.state());
        assertTrue(ticket.cancellationRequested());
        assertFalse(ticket.physicalSettled());
        assertEquals(1, pool.diagnostics().running());
        assertThrows(RejectedExecutionException.class, () -> pool.submit(new Stamp(13, 1), c -> "no"));
        assertEquals(1, pool.diagnostics().outstanding());
        finish.countDown(); await(ticket::physicalSettled);
        assertEquals(0, pool.diagnostics().outstanding());
        assertTrue(ticket.takeReady(STAMP).isEmpty());
        bounds(pool);
    }

    @Test void executorStartRejectionRollsBackEveryReferenceAndSlot() {
        AtomicInteger sourceCalls = new AtomicInteger();
        Pool pool = pool(new Pool(2, task -> { throw new RejectedExecutionException("Cannot create worker"); }));
        for (int i = 0; i < 20; i++) {
            assertThrows(RejectedExecutionException.class, () -> pool.submit(STAMP, cancellation -> {
                sourceCalls.incrementAndGet(); return "unexpected";
            }));
            assertEquals(0, pool.diagnostics().outstanding());
            assertEquals(0, pool.diagnostics().running());
            assertEquals(0, pool.diagnostics().completedUnclaimed());
        }
        assertEquals(0, sourceCalls.get());
        assertEquals(0, pool.diagnostics().submitted());
        assertEquals(20, pool.diagnostics().rejected());
        bounds(pool);
    }

    @Test void nullResultsAndThrowablesStayBoundedUntilAcknowledged() throws Exception {
        Pool pool = pool();
        Ticket<Object> nullResult = pool.submit(STAMP, cancellation -> null);
        AssertionError failure = new AssertionError("Decoder fault");
        Ticket<Object> error = pool.submit(new Stamp(11, 24), cancellation -> { throw failure; });
        await(() -> nullResult.physicalSettled() && error.physicalSettled());
        assertEquals(State.FAILED, nullResult.state());
        assertInstanceOf(NullPointerException.class, nullResult.failure().orElseThrow());
        assertSame(failure, error.failure().orElseThrow());
        assertEquals(2, pool.diagnostics().failed());
        assertEquals(2, pool.diagnostics().completedUnclaimed());
        nullResult.close(); error.close();
        assertEquals(0, pool.diagnostics().outstanding());
        bounds(pool);
    }

    @Test void sourceCanReentrantlyCloseItsTicketAndPoolWithoutDeadlock() throws Exception {
        Pool pool = pool();
        CountDownLatch published = gate(), closedInside = gate(), finish = gate();
        AtomicReference<Ticket<String>> self = new AtomicReference<>();
        AtomicBoolean stillPhysicallyRunning = new AtomicBoolean();
        Ticket<String> ticket = pool.submit(STAMP, cancellation -> {
            published.await();
            self.get().close();
            pool.close();
            stillPhysicallyRunning.set(!self.get().physicalSettled()
                    && pool.diagnostics().running() == 1 && pool.diagnostics().outstanding() == 1);
            closedInside.countDown();
            finish.await();
            return "must be discarded";
        });
        self.set(ticket); published.countDown();
        assertTrue(closedInside.await(10, TimeUnit.SECONDS));
        assertTrue(stillPhysicallyRunning.get());
        assertEquals(State.CANCELLED, ticket.state());
        finish.countDown(); await(ticket::physicalSettled);
        assertTrue(ticket.takeReady(STAMP).isEmpty());
        assertEquals(0, pool.diagnostics().outstanding());
        bounds(pool);
    }

    @Test void concurrentReadyClaimAndCloseTransferAtMostOnceWithoutLeaks() throws Exception {
        Pool pool = pool();
        for (int round = 0; round < 100; round++) {
            Object result = new Object();
            Ticket<Object> ticket = submitEventually(pool, new Stamp(round, round), c -> result);
            await(() -> ticket.state() == State.READY);
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Optional<Object>> claimed = new AtomicReference<>();
            AtomicReference<Throwable> raceFailure = new AtomicReference<>();
            Thread claim = racer(start, raceFailure, () -> claimed.set(ticket.takeReady(ticket.stamp())));
            Thread close = racer(start, raceFailure, ticket::close);
            start.countDown(); claim.join(10_000); close.join(10_000);
            assertFalse(claim.isAlive()); assertFalse(close.isAlive());
            assertNull(raceFailure.get());
            assertNotNull(claimed.get());
            if (claimed.get().isPresent()) {
                assertSame(result, claimed.get().orElseThrow());
                assertEquals(State.CLAIMED, ticket.state());
            } else assertEquals(State.CANCELLED, ticket.state());
            assertTrue(ticket.takeReady(ticket.stamp()).isEmpty());
            assertTrue(ticket.completionAcknowledged());
            assertTrue(ticket.physicalSettled());
            assertEquals(0, pool.diagnostics().outstanding());
            bounds(pool);
        }
    }

    @Test void concurrentSourceFinishAndRepeatedCancellationNeverLeakOrResurrect() throws Exception {
        Pool pool = pool();
        for (int round = 0; round < 100; round++) {
            CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
            Ticket<String> ticket = submitEventually(pool, new Stamp(round, round), cancellation -> {
                started.countDown(); finish.await();
                if ((cancellation.isCancelled())) throw new IllegalStateException("Cancelled decoder fault");
                return "late result";
            });
            assertTrue(started.await(10, TimeUnit.SECONDS));
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> raceFailure = new AtomicReference<>();
            Thread complete = racer(start, raceFailure, finish::countDown);
            Thread cancel = racer(start, raceFailure, () -> { ticket.close(); ticket.close(); });
            start.countDown(); complete.join(10_000); cancel.join(10_000);
            assertFalse(complete.isAlive()); assertFalse(cancel.isAlive());
            assertNull(raceFailure.get());
            await(ticket::physicalSettled);
            assertEquals(State.CANCELLED, ticket.state());
            assertTrue(ticket.takeReady(ticket.stamp()).isEmpty());
            assertTrue(ticket.failure().isEmpty());
            assertTrue(ticket.completionAcknowledged());
            assertEquals(0, pool.diagnostics().outstanding());
            bounds(pool);
        }
    }

    @Test void invalidInputCannotReserveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new Pool(0, "invalid"));
        assertThrows(IllegalArgumentException.class, () -> new Pool(3, "invalid"));
        assertThrows(IllegalArgumentException.class, () -> new Stamp(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Stamp(0, -1));
        Pool pool = pool();
        assertThrows(NullPointerException.class, () -> pool.submit(null, c -> "invalid"));
        assertThrows(NullPointerException.class, () -> pool.submit(STAMP, null));
        assertEquals(0, pool.diagnostics().outstanding());
        assertEquals(0, pool.diagnostics().submitted());
        bounds(pool);
    }

    private static Thread racer(CountDownLatch start, AtomicReference<Throwable> failure, Runnable operation) {
        Thread thread = new Thread(() -> {
            try { start.await(); operation.run(); }
            catch (Throwable error) { failure.compareAndSet(null, error); }
        }, "preparation-test-racer");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
