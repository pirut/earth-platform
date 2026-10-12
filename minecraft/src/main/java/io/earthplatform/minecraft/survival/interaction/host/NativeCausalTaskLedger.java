package io.earthplatform.minecraft.survival.interaction.host;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinTask;

/** Bounded task ownership shared by exact native adapters; no executor or Host authority here.
 * The concrete owner prepays the whole fixed index, ticket/envelope peak, and callback payload
 * before reserve() and before the original native lambda factory. Ordinary historical payloads
 * may retain their existing native owner, but no Earth payload may borrow that baseline.
 * Every ticket is fresh. A completed callback is distinct from disposal of executor aliases.
 */
final class NativeCausalTaskLedger {
    private final Ticket[] tickets;
    private long lineage;
    private final FailureLatch failures = new FailureLatch();

    NativeCausalTaskLedger(int capacity) {
        if (capacity < 1 || capacity > 256) throw new IllegalArgumentException("Bounded task capacity required");
        tickets = new Ticket[capacity];
    }

    static final class Ticket {
        private final NativeCausalTaskLedger owner;
        private final long sequence;
        private Object executor;
        private Runnable original;
        private Envelope envelope;
        private boolean submissionEntered, submissionReturned, originReturned;
        private boolean runEntered, callbackBodyReturned, callbackReturned, retired;
        private Ticket(NativeCausalTaskLedger owner, long sequence, Object executor) {
            this.owner = owner; this.sequence = sequence; this.executor = executor;
        }
        long sequence() { return sequence; }
    }

    /** No executor, endpoint, ticket, callback, or ledger reference survives in a healthy tombstone. */
    private static final class Envelope implements Runnable {
        private Ticket ticket;
        private final FailureLatch failures;
        private boolean claimed;
        private Envelope(Ticket ticket) { this.ticket = ticket; this.failures = ticket.owner.failures; }
        @Override public void run() {
            final Ticket executing;
            synchronized (this) {
                if (claimed || ticket == null) {
                    failures.fail(failures.duplicate);
                    throw failures.duplicate;
                }
                claimed = true;
                executing = ticket;
            }
            // No original callback alias survives this returned helper scope on healthy completion.
            executing.owner.invokeOriginal(executing);
            synchronized (this) { ticket = null; }
            // Final publication is a field-only, nonthrowing tail. No hook/callback dispatch follows.
            executing.owner.publishCallbackReturn(executing);
        }
    }

    /** Standalone failure authority retains no Host/world when healthy. Failed payloads stay charged. */
    private static final class FailureLatch {
        private volatile Throwable first;
        private boolean suppressed;
        private final ProtocolViolation duplicate = new ProtocolViolation("Native task invoked twice");
        synchronized void fail(Throwable failure) {
            if (failure == null) throw new IllegalArgumentException("Missing task failure");
            if (first == null) first = failure;
            else if (first != failure && !suppressed) { suppressed = true; first.addSuppressed(failure); }
        }
    }
    private static final class ProtocolViolation extends RuntimeException {
        private ProtocolViolation(String message) { super(message, null, false, false); }
    }

    synchronized Ticket reserve(Object exactExecutor) {
        healthy();
        if (exactExecutor == null) throw reject("Missing original executor");
        int free = -1;
        for (int i = 0; i < tickets.length; i++) if (tickets[i] == null) { free = i; break; }
        if (free < 0) throw new IllegalStateException("Task capacity refused before native factory");
        if (lineage == Long.MAX_VALUE) throw reject("Task lineage exhausted");
        Ticket ticket = new Ticket(this, ++lineage, exactExecutor);
        tickets[free] = ticket;
        return ticket;
    }

    /** Only the source-authenticated adapter may hand this envelope to the identical original executor. */
    synchronized Runnable submissionEntered(Ticket ticket, Object exactExecutor, Runnable original) {
        own(ticket);
        if (ticket.submissionEntered || ticket.originReturned || ticket.executor != exactExecutor || original == null)
            throw reject("Foreign or duplicate native task submission");
        // Wrapping these changes executor helping/async semantics. The native lambda sites use neither.
        if (original instanceof ForkJoinTask<?> || original instanceof CompletableFuture.AsynchronousCompletionTask)
            throw reject("Non-ordinary Runnable cannot be enveloped");
        ticket.original = original;
        ticket.envelope = new Envelope(ticket);
        ticket.submissionEntered = true;
        return ticket.envelope;
    }

    synchronized void submissionReturned(Ticket ticket, Throwable thrown) {
        own(ticket);
        if (!ticket.submissionEntered || ticket.submissionReturned) throw reject("Native submission return changed");
        if (thrown != null) fail(thrown); // Enqueue-then-throw may still execute: retain everything.
        ticket.submissionReturned = true;
    }

    /** Called after the original method containing the lambda/submission has left its helper. */
    synchronized void originReturned(Ticket ticket) {
        own(ticket);
        if (ticket.originReturned || !ticket.submissionReturned) throw reject("Native origin returned out of order");
        ticket.originReturned = true;
        if (ticket.callbackBodyReturned && failures.first == null) ticket.executor = null;
    }

    private void invokeOriginal(Ticket ticket) {
        Runnable callback;
        synchronized (this) {
            own(ticket);
            if (!ticket.submissionEntered || ticket.runEntered) throw reject("Native task invoked twice or before submission");
            ticket.runEntered = true;
            callback = ticket.original;
        }
        // Existing accepted work still delegates exactly once after another task poisons this ledger.
        // Do not call healthy() here or silently drop already submitted ordinary/native work.
        try {
            callback.run();
        } catch (Throwable failure) {
            fail(failure); // Before any outer native executor catch can log/swallow it.
            throwUnchecked(failure);
        }
        synchronized (this) {
            ticket.callbackBodyReturned = true;
            if (failures.first == null) {
                ticket.original = null;
                if (ticket.originReturned) ticket.executor = null;
            }
        }
    }

    private synchronized void publishCallbackReturn(Ticket ticket) {
        ticket.callbackReturned = true;
    }

    /** An original Packet.handle failure must arrive BEFORE PacketUtils' inner catch swallows it. */
    synchronized void handlerFailed(Ticket ticket, Throwable failure) {
        own(ticket);
        if (!ticket.runEntered || ticket.callbackReturned) throw reject("Packet failure lacks its running original task");
        fail(failure);
    }

    /** An original source branch that created/submitted no task can retire only its unused ticket. */
    synchronized void retireUnused(Ticket ticket) {
        healthy(); own(ticket);
        if (ticket.submissionEntered || ticket.original != null || ticket.envelope != null || ticket.runEntered)
            throw reject("A submitted task cannot be retired as unused");
        ticket.executor = null;
        retire(ticket);
    }

    /** Separate authenticated adapter evidence required; callback return alone is insufficient. */
    synchronized void executorAliasesDisposed(Ticket ticket) {
        healthy(); own(ticket);
        if (!payloadComplete(ticket)) throw reject("Executor alias disposal preceded native callback/origin return");
        ticket.envelope = null;
        retire(ticket);
    }

    private void retire(Ticket ticket) {
        for (int i = 0; i < tickets.length; i++) if (tickets[i] == ticket) {
            tickets[i] = null; ticket.retired = true; return;
        }
        throw reject("Task index ownership diverged");
    }

    synchronized boolean payloadsSettled() {
        healthy();
        for (Ticket ticket : tickets) if (ticket != null && !payloadComplete(ticket)) return false;
        return true;
    }
    synchronized boolean allAliasesDisposed() {
        healthy();
        for (Ticket ticket : tickets) if (ticket != null) return false;
        return true;
    }
    synchronized int chargedSlots() {
        int count = 0;
        for (Ticket ticket : tickets) if (ticket != null) count++;
        return count;
    }
    synchronized boolean retainsCallback(Ticket ticket) { own(ticket); return ticket.original != null; }
    synchronized boolean matchesEnvelope(Ticket ticket, Object candidate) { own(ticket); return ticket.envelope == candidate; }
    synchronized long lineage() { healthy(); return lineage; }
    Throwable failure() { return failures.first; }
    void fail(Throwable failure) { failures.fail(failure); }

    private boolean payloadComplete(Ticket ticket) {
        return ticket.submissionReturned && ticket.originReturned && ticket.callbackReturned
                && ticket.original == null && ticket.executor == null;
    }
    private void own(Ticket ticket) {
        if (ticket == null || ticket.owner != this || ticket.retired) throw reject("Foreign or stale native task ticket");
        for (Ticket current : tickets) if (current == ticket) return;
        throw reject("Missing native task ticket");
    }
    private IllegalStateException reject(String reason) {
        IllegalStateException failure = new IllegalStateException(reason);
        fail(failure); return failure;
    }
    private void healthy() { if (failures.first != null) throwUnchecked(failures.first); }
    @SuppressWarnings("unchecked") private static <T extends Throwable> void throwUnchecked(Throwable failure) throws T { throw (T) failure; }
}
