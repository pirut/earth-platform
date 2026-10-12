package io.earthplatform.minecraft.survival.interaction.host;

import java.lang.ref.WeakReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinTask;

/** Constructor-origin ordinary-baseline observation. This is NOT an Earth payload owner.
 * Only the original native executor holds an envelope's callback strongly. The permanent index
 * contains scalar tickets and weak executor identities, never callbacks/packets/players/worlds.
 * A missed, dropped, failed or capacity-exceeding task permanently prevents this epoch adopting.
 * Every ticket/envelope/weak wrapper and the fixed index must be prepaid before native factories.
 */
final class NativeOrdinaryTaskFrontier {
    static final int MAX_TICKETS = 8192;
    enum Failure { NONE, CAPACITY, SOURCE, SPECIALIZED_TASK, DUPLICATE, SUBMISSION, CALLBACK }
    private static final NativeOrdinaryTaskFrontier[] ACTIVE = new NativeOrdinaryTaskFrontier[4];
    private static final WeakReference<?>[] THREADS = new WeakReference<?>[4];
    private static int threadCount;
    private static synchronized boolean admitThread() {
        Thread current=Thread.currentThread();
        for(int i=0;i<threadCount;i++)if(THREADS[i].get()==current)return true;
        if(threadCount==THREADS.length)return false;
        THREADS[threadCount++]=new WeakReference<>(current); return true;
    }
    private static synchronized NativeOrdinaryTaskFrontier activeValue(){
        Thread current=Thread.currentThread();for(int i=0;i<threadCount;i++)if(THREADS[i].get()==current)return ACTIVE[i];return null;
    }
    private static synchronized void activeValue(NativeOrdinaryTaskFrontier value){
        Thread current=Thread.currentThread();for(int i=0;i<threadCount;i++)if(THREADS[i].get()==current){ACTIVE[i]=value;return;}
    }
    private final Ticket[] tickets;
    private int count;
    private final FailureState failure = new FailureState();

    NativeOrdinaryTaskFrontier(int capacity) {
        if (capacity < 1 || capacity > MAX_TICKETS) throw new IllegalArgumentException("Finite ordinary observation required");
        tickets = new Ticket[capacity];
    }

    static final class Ticket {
        private final NativeOrdinaryTaskFrontier owner;
        private final long sequence;
        private final WeakReference<Object> executor;
        private boolean submitted, submissionReturned, originReturned, callbackUnwound, noTask;
        private Ticket(NativeOrdinaryTaskFrontier owner, long sequence, Object executor) {
            this.owner = owner; this.sequence = sequence; this.executor = new WeakReference<>(executor);
        }
    }

    private static final class FailureState {
        private volatile Failure first = Failure.NONE;
        private final Duplicate duplicate = new Duplicate();
        synchronized void mark(Failure reason) { if (first == Failure.NONE) first = reason; }
    }
    private static final class Duplicate extends RuntimeException {
        private Duplicate() { super("Ordinary task envelope invoked twice", null, false, false); }
    }

    private static final class Envelope implements Runnable {
        private Ticket ticket;
        private Runnable original;
        private final FailureState failure;
        private boolean claimed;
        private Envelope(Ticket ticket, Runnable original) {
            this.ticket = ticket; this.original = original; this.failure = ticket.owner.failure;
        }
        @Override public void run() {
            final Ticket executing;
            synchronized (this) {
                if (claimed || ticket == null) { failure.mark(Failure.DUPLICATE); throw failure.duplicate; }
                claimed = true; executing = ticket;
            }
            Throwable thrown = null;
            try { invokeOriginal(); }
            catch (Throwable originalFailure) { failure.mark(Failure.CALLBACK); thrown = originalFailure; }
            // invokeOriginal's frame has actually unwound. Do not keep an extra ordinary callback alias.
            synchronized (this) { original = null; ticket = null; }
            executing.owner.publishUnwound(executing);
            // Preserve the exact original native exception/catch behavior; do not retain it in this observer.
            if (thrown != null) throwUnchecked(thrown);
        }
        private void invokeOriginal() {
            Runnable callback;
            synchronized (this) { callback = original; }
            NativeOrdinaryTaskFrontier previous = null;
            boolean scopeInstalled = false;
            try {
                if(admitThread()) { previous = activeValue(); activeValue(ticket.owner); scopeInstalled = true; }
                else failure.mark(Failure.SOURCE);
            } catch(Throwable bookkeeping) { try { failure.mark(Failure.SOURCE); } catch(Throwable ignored) {} }
            // Observation setup must never skip the accepted original callback.
            try { callback.run(); }
            finally {
                if(scopeInstalled) try { activeValue(previous); }
                catch(Throwable bookkeeping) { try { failure.mark(Failure.SOURCE); } catch(Throwable ignored) {} }
            }
        }
    }

    synchronized Ticket reserveBeforeFactory(Object executor) {
        if (failure.first != Failure.NONE) return null;
        if (executor == null) { failure.mark(Failure.SOURCE); return null; }
        if (count == tickets.length) { failure.mark(Failure.CAPACITY); return null; }
        Ticket ticket = new Ticket(this, count + 1L, executor);
        tickets[count++] = ticket;
        return ticket;
    }

    /** Source adapters must call the SAME original execute operation once with the returned value. */
    synchronized Runnable envelopeAtOriginalSubmission(Ticket ticket, Object executor, Runnable original) {
        if (ticket == null) return original; // Incomplete observation cannot interfere with ordinary execution.
        if (!owns(ticket) || ticket.submitted || ticket.originReturned || ticket.executor.get() != executor || original == null) {
            failure.mark(Failure.SOURCE); return original;
        }
        if (original instanceof ForkJoinTask<?> || original instanceof CompletableFuture.AsynchronousCompletionTask) {
            failure.mark(Failure.SPECIALIZED_TASK); return original;
        }
        Envelope result = new Envelope(ticket, original);
        ticket.submitted = true;
        return result;
    }

    synchronized void originalSubmissionReturned(Ticket ticket, boolean threw) {
        if (ticket == null) return;
        if (!owns(ticket) || !ticket.submitted || ticket.submissionReturned) { failure.mark(Failure.SOURCE); return; }
        if (threw) failure.mark(Failure.SUBMISSION);
        ticket.submissionReturned = true;
    }

    synchronized void originalOriginReturned(Ticket ticket) {
        if (ticket == null) return;
        if (!owns(ticket) || ticket.originReturned) { failure.mark(Failure.SOURCE); return; }
        if (!ticket.submitted) ticket.noTask = true; // Only the authenticated original no-submission branch.
        else if (!ticket.submissionReturned) failure.mark(Failure.SOURCE);
        ticket.originReturned = true;
    }

    /** Inner native catches (PacketUtils packet.handle) report only a prepaid scalar before swallowing. */
    static NativeOrdinaryTaskFrontier active() { return activeValue(); }
    void originalCallbackFailed() { failure.mark(Failure.CALLBACK); }
    void sourceLost() { failure.mark(Failure.SOURCE); }

    private synchronized void publishUnwound(Ticket ticket) { ticket.callbackUnwound = true; }

    synchronized boolean observedPayloadsSettled() {
        if (failure.first != Failure.NONE) return false;
        for (int i = 0; i < count; i++) {
            Ticket ticket = tickets[i];
            if (!ticket.originReturned || !ticket.noTask && (!ticket.submissionReturned || !ticket.callbackUnwound)) return false;
        }
        return true;
    }
    synchronized long lineage() { return count; }
    Failure failure() { return failure.first; }
    private boolean owns(Ticket ticket) {
        return ticket.owner == this && ticket.sequence > 0 && ticket.sequence <= count
                && tickets[(int) ticket.sequence - 1] == ticket;
    }
    @SuppressWarnings("unchecked") private static <T extends Throwable> void throwUnchecked(Throwable failure) throws T { throw (T) failure; }
}
