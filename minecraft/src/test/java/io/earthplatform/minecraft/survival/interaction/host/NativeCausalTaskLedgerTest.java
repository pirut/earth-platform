package io.earthplatform.minecraft.survival.interaction.host;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinTask;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit state-model tests; no simulated executor return is production authority. */
class NativeCausalTaskLedgerTest {
    @Test void callbackSubmissionOriginAndExecutorDisposalAreSeparate() {
        var ledger = new NativeCausalTaskLedger(1); var executor = new Object();
        var ticket = ledger.reserve(executor); int[] calls = {0};
        Runnable wrapped = ledger.submissionEntered(ticket, executor, () -> calls[0]++);
        assertFalse(ledger.payloadsSettled()); wrapped.run(); assertEquals(1, calls[0]);
        assertFalse(ledger.retainsCallback(ticket)); assertFalse(ledger.payloadsSettled());
        ledger.submissionReturned(ticket, null); assertFalse(ledger.payloadsSettled());
        ledger.originReturned(ticket); assertTrue(ledger.payloadsSettled());
        assertFalse(ledger.allAliasesDisposed()); assertEquals(1, ledger.chargedSlots());
        ledger.executorAliasesDisposed(ticket); assertTrue(ledger.allAliasesDisposed());
    }
    @Test void originalCallbackRemainsOwnedUntilItsActualReturn() {
        var ledger = new NativeCausalTaskLedger(1); var executor = new Object(); var ticket = ledger.reserve(executor);
        Runnable wrapped = ledger.submissionEntered(ticket, executor, () -> {
            assertTrue(ledger.retainsCallback(ticket)); assertFalse(ledger.payloadsSettled());
        });
        ledger.submissionReturned(ticket, null); ledger.originReturned(ticket); wrapped.run();
        assertTrue(ledger.payloadsSettled()); assertFalse(ledger.retainsCallback(ticket));
    }
    @Test void neverRunOrCancelledByExecutorDoesNotRetireOwnership() {
        var ledger = new NativeCausalTaskLedger(1); var executor = new Object(); var ticket = ledger.reserve(executor);
        ledger.submissionEntered(ticket, executor, () -> fail("Never scheduled"));
        ledger.submissionReturned(ticket, null); ledger.originReturned(ticket);
        assertFalse(ledger.payloadsSettled()); assertTrue(ledger.retainsCallback(ticket)); assertEquals(1, ledger.chargedSlots());
    }
    @Test void enqueueThenThrowKeepsChargeAndStillDelegatesTheAcceptedCallback() {
        var ledger = new NativeCausalTaskLedger(1); var executor = new Object(); var ticket = ledger.reserve(executor);
        int[] calls = {0}; Runnable wrapped = ledger.submissionEntered(ticket, executor, () -> calls[0]++);
        var original = new IllegalStateException("Executor enqueued then threw");
        ledger.submissionReturned(ticket, original); ledger.originReturned(ticket); wrapped.run();
        assertEquals(1, calls[0]); assertSame(original, ledger.failure()); assertTrue(ledger.retainsCallback(ticket));
        assertSame(original, assertThrows(IllegalStateException.class, ledger::payloadsSettled)); assertEquals(1, ledger.chargedSlots());
    }
    @Test void failureLatchesBeforeAnOuterNativeCatch() {
        var ledger = new NativeCausalTaskLedger(1); var executor = new Object(); var ticket = ledger.reserve(executor);
        var original = new IllegalStateException("Original callback");
        Runnable wrapped = ledger.submissionEntered(ticket, executor, () -> { throw original; });
        ledger.submissionReturned(ticket, null); ledger.originReturned(ticket);
        try { wrapped.run(); fail("Original throw was swallowed"); }
        catch (IllegalStateException caught) { assertSame(original, caught); assertSame(original, ledger.failure()); }
        assertTrue(ledger.retainsCallback(ticket)); assertEquals(1, ledger.chargedSlots());
    }
    @Test void innerPacketHandlerCatchCannotHideAnObservedFailure() {
        var ledger = new NativeCausalTaskLedger(1); var executor = new Object(); var ticket = ledger.reserve(executor);
        var original = new IllegalStateException("Packet.handle before native catch");
        Runnable wrapped = ledger.submissionEntered(ticket, executor, () -> {
            try { ledger.handlerFailed(ticket, original); throw original; } catch (IllegalStateException nativeCatch) { }
        });
        ledger.submissionReturned(ticket, null); ledger.originReturned(ticket); wrapped.run();
        assertSame(original, ledger.failure()); assertTrue(ledger.retainsCallback(ticket));
    }
    @Test void duplicateInvocationPoisonsWithoutReplay() {
        var ledger = new NativeCausalTaskLedger(1); var executor = new Object(); var ticket = ledger.reserve(executor);
        int[] calls = {0}; Runnable wrapped = ledger.submissionEntered(ticket, executor, () -> calls[0]++);
        ledger.submissionReturned(ticket, null); ledger.originReturned(ticket); wrapped.run();
        assertThrows(RuntimeException.class, wrapped::run); assertEquals(1, calls[0]); assertNotNull(ledger.failure());
    }
    @Test void unrelatedEarlierFailureDoesNotDropAlreadyAcceptedNativeWork() {
        var ledger = new NativeCausalTaskLedger(2); var executor = new Object();
        var first = ledger.reserve(executor); var second = ledger.reserve(executor); int[] calls = {0};
        Runnable one = ledger.submissionEntered(first, executor, () -> { throw new IllegalStateException("First"); });
        Runnable two = ledger.submissionEntered(second, executor, () -> calls[0]++);
        ledger.submissionReturned(first, null); ledger.originReturned(first);
        ledger.submissionReturned(second, null); ledger.originReturned(second);
        assertThrows(IllegalStateException.class, one::run); two.run(); assertEquals(1, calls[0]);
        assertTrue(ledger.retainsCallback(first)); assertTrue(ledger.retainsCallback(second));
    }
    @Test void capacityRefusalPrecedesTheNextFactoryAndDoesNotPoisonExistingWork() {
        var ledger = new NativeCausalTaskLedger(1); var executor = new Object(); var ticket = ledger.reserve(executor);
        assertThrows(IllegalStateException.class, () -> ledger.reserve(executor)); assertNull(ledger.failure());
        ledger.retireUnused(ticket); assertEquals(1, ledger.lineage()); assertEquals(0, ledger.chargedSlots());
    }
    @Test void retiredTicketCannotDisposeAReusedIndex() {
        var ledger = new NativeCausalTaskLedger(1); var executor = new Object(); var old = ledger.reserve(executor);
        ledger.retireUnused(old); var current = ledger.reserve(executor);
        assertThrows(IllegalStateException.class, () -> ledger.executorAliasesDisposed(old));
        assertEquals(1, ledger.chargedSlots()); assertNotSame(old, current);
    }
    @Test void unknownExecutorAliasCannotBeReleasedAfterCallbackOnly() {
        var ledger = new NativeCausalTaskLedger(1); var executor = new Object(); var ticket = ledger.reserve(executor);
        Runnable wrapped = ledger.submissionEntered(ticket, executor, () -> {}); wrapped.run();
        assertThrows(IllegalStateException.class, () -> ledger.executorAliasesDisposed(ticket)); assertEquals(1, ledger.chargedSlots());
    }
    @Test void firstFailureAndOneCleanupFailureStayBoundedAndRetained() {
        var ledger = new NativeCausalTaskLedger(1); var executor = new Object(); ledger.reserve(executor);
        var first = new IllegalStateException("First"); var cleanup = new IllegalStateException("Cleanup");
        ledger.fail(first); ledger.fail(cleanup); ledger.fail(new IllegalStateException("Another cleanup"));
        assertSame(first, ledger.failure()); assertArrayEquals(new Throwable[]{cleanup}, first.getSuppressed());
        assertEquals(1, ledger.chargedSlots());
    }
    @Test void forkJoinTaskCannotBeChangedIntoOrdinaryRunnable() {
        var ledger = new NativeCausalTaskLedger(1); var executor = new Object(); var ticket = ledger.reserve(executor);
        class Specialized extends ForkJoinTask<Void> implements Runnable {
            public Void getRawResult() { return null; }
            protected void setRawResult(Void ignored) {}
            protected boolean exec() { return true; }
            public void run() {}
        }
        Runnable specialized = new Specialized();
        assertThrows(IllegalStateException.class, () -> ledger.submissionEntered(ticket, executor, specialized));
    }
    @Test void asynchronousCompletionTaskCannotBeChangedIntoOrdinaryRunnable() {
        class Specialized implements Runnable, CompletableFuture.AsynchronousCompletionTask { public void run() {} }
        var ledger = new NativeCausalTaskLedger(1); var executor = new Object(); var ticket = ledger.reserve(executor);
        assertThrows(IllegalStateException.class, () -> ledger.submissionEntered(ticket, executor, new Specialized()));
    }
    @Test void genuineDisposalAllowsFreshGenerationsWithoutReusingEnvelopeCapability() {
        var ledger = new NativeCausalTaskLedger(1); var executor = new Object(); Runnable previous = null;
        for (int i = 1; i <= 128; i++) {
            var ticket = ledger.reserve(executor); Runnable wrapped = ledger.submissionEntered(ticket, executor, () -> {});
            assertNotSame(previous, wrapped); ledger.submissionReturned(ticket, null); ledger.originReturned(ticket);
            wrapped.run(); assertTrue(ledger.payloadsSettled()); ledger.executorAliasesDisposed(ticket);
            assertTrue(ledger.allAliasesDisposed()); assertEquals(i, ledger.lineage()); previous = wrapped;
        }
    }
    @Test void returnedSubmissionAndOriginStayPendingWhileRealWorkerCallbackIsBlocked() throws Exception {
        var ledger = new NativeCausalTaskLedger(1); var executor = new Object(); var ticket = ledger.reserve(executor);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var completed = new java.util.concurrent.CountDownLatch(1);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Runnable wrapped = ledger.submissionEntered(ticket, executor, () -> {
            entered.countDown();
            try { if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Worker release timed out"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        });
        Thread worker = new Thread(() -> {
            try { wrapped.run(); } catch (Throwable thrown) { failure.set(thrown); } finally { completed.countDown(); }
        }, "causal-task-ledger-model");
        worker.setDaemon(true); worker.start();
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            ledger.submissionReturned(ticket, null); ledger.originReturned(ticket);
            assertFalse(ledger.payloadsSettled()); assertTrue(ledger.retainsCallback(ticket));
            release.countDown(); assertTrue(completed.await(5, java.util.concurrent.TimeUnit.SECONDS));
            worker.join(5000); assertFalse(worker.isAlive()); assertNull(failure.get());
            assertTrue(ledger.payloadsSettled()); assertFalse(ledger.allAliasesDisposed());
        } finally { release.countDown(); worker.join(5000); assertFalse(worker.isAlive(), "Model worker must terminate"); }
    }
    @Test void duplicateDuringActualBlockedCallbackPoisonsWithoutReplay() throws Exception {
        var ledger = new NativeCausalTaskLedger(1); var executor = new Object(); var ticket = ledger.reserve(executor);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var completed = new java.util.concurrent.CountDownLatch(1);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        Runnable wrapped = ledger.submissionEntered(ticket, executor, () -> {
            calls.incrementAndGet(); entered.countDown();
            try { if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Worker release timed out"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        });
        Thread worker = new Thread(() -> {
            try { wrapped.run(); } catch (Throwable thrown) { failure.set(thrown); } finally { completed.countDown(); }
        }, "causal-task-ledger-duplicate-model");
        worker.setDaemon(true); worker.start();
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            ledger.submissionReturned(ticket, null); ledger.originReturned(ticket);
            RuntimeException duplicate = assertThrows(RuntimeException.class, wrapped::run);
            assertSame(duplicate, ledger.failure()); assertEquals(1, calls.get());
            release.countDown(); assertTrue(completed.await(5, java.util.concurrent.TimeUnit.SECONDS));
            worker.join(5000); assertFalse(worker.isAlive()); assertNull(failure.get());
            assertEquals(1, calls.get()); assertTrue(ledger.retainsCallback(ticket));
            assertSame(duplicate, assertThrows(RuntimeException.class, ledger::payloadsSettled));
        } finally { release.countDown(); worker.join(5000); assertFalse(worker.isAlive(), "Model worker must terminate"); }
    }

}
