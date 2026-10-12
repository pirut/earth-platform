package io.earthplatform.minecraft.enginebridge;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CPU-only provider bootstrap with two process-wide physical/admission slots and no work queue.
 * Source work never runs on the requesting thread. Sources capture only immutable palette/policy
 * inputs, never a player, server, Level, renderer or another mutable owner. Results must be safely
 * published immutable provider handles with no separately owned executor/closeable resource;
 * private bounded, thread-safe caches are allowed. Cancellation drops a late result, not a resource.
 *
 * <p>A slot covers preparation, cancellation-ignoring preparation, and a completed but unclaimed
 * result/failure. Closing a ticket signals cooperative cancellation and drops its references, but
 * cannot release its slot before the source physically returns or throws. Claiming a result moves
 * ownership to the caller; caller-retained results are no longer this helper's responsibility.
 * Sources must not start asynchronous work that outlives their invocation.
 *
 * <p>There are no futures, completion callbacks, waiting game-thread methods or completed-result
 * queues. The owner polls its ticket, checks its current immutable request stamp, then claims once.
 * It must independently validate the prepared world's identity before publishing it. A stale owner
 * cannot claim with a different stamp. Polling physicalSettled acknowledges actual source exit;
 * completionAcknowledged separately reports owner claim/close, which can precede physical exit.
 */
public final class BoundedProviderPreparation {
    public static final int MAX_OUTSTANDING = 2;

    private BoundedProviderPreparation() { }

    /** Lazy process runtime; isolated tests never need to start or shut it down. */
    private static final class ProcessRuntime {
        private static final Pool POOL = new Pool(MAX_OUTSTANDING, "earth-provider-preparation");
    }

    public static <T> Ticket<T> submit(Stamp stamp, Source<T> source) {
        return ProcessRuntime.POOL.submit(stamp, source);
    }

    public static Diagnostics processDiagnostics() { return ProcessRuntime.POOL.diagnostics(); }

    /** Epoch and request number must come from the owner's current lifecycle, never the result. */
    public record Stamp(long epoch, long requestId) {
        public Stamp {
            if (epoch < 0 || requestId < 0) throw new IllegalArgumentException("Negative preparation identity");
        }
    }

    public enum State { PENDING, READY, FAILED, CANCELLED, CLAIMED }

    @FunctionalInterface
    public interface Source<T> {
        T prepare(Cancellation cancellation) throws Exception;
    }

    public static final class Cancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private Cancellation() { }
        public boolean isCancelled() { return cancelled.get(); }
        public void throwIfCancelled() {
            if (isCancelled()) throw new CancellationException("Provider preparation no longer requested");
        }
    }

    /** completedUnclaimed includes both ready results and failures retained pending owner close. */
    public record Diagnostics(int maximumOutstanding, int running, int outstanding,
                              int completedUnclaimed, int queued, long submitted, long settled,
                              long failed, long cancelled, long rejected, boolean closed) { }

    /**
     * Explicit isolated runtime, primarily for tests. Close is nonblocking and never closes the
     * process runtime. Worker and outstanding limits cannot exceed the process budget of two.
     */
    public static final class Pool implements AutoCloseable {
        private final Object lock = new Object();
        private final int maximumOutstanding;
        private final ThreadPoolExecutor executor;
        private final Set<Ticket<?>> outstanding = new HashSet<>();
        private int running;
        private long submitted, settled, failed, cancelled, rejected;
        private boolean closed;

        public Pool(int maximumOutstanding, String threadPrefix) {
            this(maximumOutstanding, daemonFactory(threadPrefix));
        }

        /** Isolated test seam for worker-start/rejection races; never used by the process pool. */
        Pool(int maximumOutstanding, ThreadFactory threadFactory) {
            if (maximumOutstanding < 1 || maximumOutstanding > MAX_OUTSTANDING)
                throw new IllegalArgumentException("Preparation budget must be one or two");
            this.maximumOutstanding = maximumOutstanding;
            Objects.requireNonNull(threadFactory, "threadFactory");
            executor = new ThreadPoolExecutor(maximumOutstanding, maximumOutstanding, 30,
                    TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
                        Thread worker = Objects.requireNonNull(threadFactory.newThread(task), "Null worker");
                        worker.setDaemon(true);
                        return worker;
                    }, new ThreadPoolExecutor.AbortPolicy());
            executor.allowCoreThreadTimeOut(true);
        }

        /**
         * Immediate admission or rejection. A transient rejection is possible while a settled
         * worker returns to its handoff point; callers can retry on a later owner tick. Rejection
         * retains neither a ticket nor a source, and never falls back to caller-thread execution.
         */
        public <T> Ticket<T> submit(Stamp stamp, Source<T> source) {
            Objects.requireNonNull(stamp, "stamp");
            Objects.requireNonNull(source, "source");
            synchronized (lock) {
                if (closed || outstanding.size() >= maximumOutstanding) {
                    rejected++;
                    throw new RejectedExecutionException(closed ? "Preparation pool closed" : "Preparation budget exhausted");
                }
                Ticket<T> ticket = new Ticket<>(this, stamp);
                outstanding.add(ticket);
                Thread submitter = Thread.currentThread();
                try {
                    executor.execute(() -> invoke(ticket, source, submitter));
                    submitted++;
                    return ticket;
                } catch (RuntimeException | Error error) {
                    // Workers also take lock before invoking user code. Even a faulty thread
                    // factory that starts then throws cannot let a rejected source escape.
                    ticket.state = State.CANCELLED;
                    ticket.cancellation.cancelled.set(true);
                    ticket.completionAcknowledged = true;
                    ticket.physicalSettled = true;
                    ticket.result = null;
                    ticket.failure = null;
                    outstanding.remove(ticket);
                    rejected++;
                    throw error;
                }
            }
        }

        private <T> void invoke(Ticket<T> ticket, Source<T> source, Thread submitter) {
            synchronized (lock) {
                if (ticket.physicalSettled) return;
                if (ticket.state == State.CANCELLED) {
                    settle(ticket, null, null);
                    return;
                }
                if (Thread.currentThread() == submitter) {
                    settle(ticket, null, new RejectedExecutionException("Inline provider preparation prohibited"));
                    return;
                }
                running++;
            }
            T result = null;
            Throwable error = null;
            try {
                ticket.cancellation.throwIfCancelled();
                result = Objects.requireNonNull(source.prepare(ticket.cancellation), "Null prepared provider");
                ticket.cancellation.throwIfCancelled();
            } catch (Throwable failure) {
                error = failure;
            } finally {
                synchronized (lock) {
                    running--;
                    settle(ticket, result, error);
                }
            }
        }

        /** Called only with lock held, after source exit or before any source invocation. */
        private <T> void settle(Ticket<T> ticket, T result, Throwable error) {
            if (ticket.physicalSettled) return;
            ticket.physicalSettled = true;
            settled++;
            if (ticket.state != State.CANCELLED) {
                if (error != null) {
                    ticket.failure = error;
                    ticket.state = State.FAILED;
                    failed++;
                } else {
                    ticket.result = result;
                    ticket.state = State.READY;
                }
            }
            if (ticket.completionAcknowledged) outstanding.remove(ticket);
        }

        public Diagnostics diagnostics() {
            synchronized (lock) {
                int completedUnclaimed = 0;
                for (Ticket<?> ticket : outstanding)
                    if (ticket.physicalSettled && !ticket.completionAcknowledged) completedUnclaimed++;
                return new Diagnostics(maximumOutstanding, running, outstanding.size(), completedUnclaimed,
                        executor.getQueue().size(), submitted, settled, failed, cancelled, rejected, closed);
            }
        }

        @Override public void close() {
            synchronized (lock) {
                if (closed) return;
                closed = true;
                for (Ticket<?> ticket : List.copyOf(outstanding)) ticket.closeLocked();
            }
            // No interrupt-based Future cancellation and no awaitTermination: dispatched work
            // must still enter invoke/settle, including a worker that has not started yet.
            executor.shutdown();
        }
    }

    public static final class Ticket<T> implements AutoCloseable {
        private final Pool pool;
        private final Stamp stamp;
        private final Cancellation cancellation = new Cancellation();
        // All following fields are guarded by pool.lock. Neither result nor source is in a future.
        private State state = State.PENDING;
        private T result;
        private Throwable failure;
        private boolean physicalSettled;
        private boolean completionAcknowledged;

        private Ticket(Pool pool, Stamp stamp) { this.pool = pool; this.stamp = stamp; }

        public Stamp stamp() { return stamp; }
        public State state() { synchronized (pool.lock) { return state; } }
        public boolean physicalSettled() { synchronized (pool.lock) { return physicalSettled; } }
        public boolean completionAcknowledged() { synchronized (pool.lock) { return completionAcknowledged; } }
        public boolean cancellationRequested() { return cancellation.isCancelled(); }

        /** Failure inspection does not release its retained slot; close acknowledges/discards it. */
        public Optional<Throwable> failure() { synchronized (pool.lock) { return Optional.ofNullable(failure); } }

        /**
         * Atomically validates both epoch and request identity and transfers a ready result once.
         * Wrong identity, pending/failure/cancellation and repeated claims return empty unchanged.
         * Owners pass their CURRENT stamp, not ticket.stamp() merely to bypass stale-owner checks.
         */
        public Optional<T> takeReady(Stamp currentStamp) {
            Objects.requireNonNull(currentStamp, "currentStamp");
            synchronized (pool.lock) {
                if (!stamp.equals(currentStamp) || state != State.READY) return Optional.empty();
                T claimed = result;
                result = null;
                state = State.CLAIMED;
                completionAcknowledged = true;
                pool.outstanding.remove(this);
                return Optional.of(claimed);
            }
        }

        @Override public void close() { synchronized (pool.lock) { closeLocked(); } }

        private void closeLocked() {
            if (completionAcknowledged) return;
            cancellation.cancelled.set(true);
            completionAcknowledged = true;
            state = State.CANCELLED;
            result = null;
            failure = null;
            pool.cancelled++;
            if (physicalSettled) pool.outstanding.remove(this);
        }
    }

    private static ThreadFactory daemonFactory(String prefix) {
        Objects.requireNonNull(prefix, "threadPrefix");
        if (prefix.isBlank()) throw new IllegalArgumentException("Blank preparation thread prefix");
        AtomicInteger sequence = new AtomicInteger();
        return task -> new Thread(task, prefix + "-" + sequence.incrementAndGet());
    }
}
