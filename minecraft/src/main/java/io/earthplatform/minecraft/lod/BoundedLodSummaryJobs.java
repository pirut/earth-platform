package io.earthplatform.minecraft.lod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Bounded, deduplicated CPU summaries with independent subscriber leases and a weighted LRU.
 * No graphics objects or renderer-specific addressing belong here. The executor is caller-owned;
 * inline execution (including CallerRunsPolicy) is rejected before source work is invoked.
 * Sources must settle their stage only after their actual work stops. Cancellation deliberately
 * does not cancel that stage: a cancellation-ignoring source retains its running slot until then.
 *
 * <p>Byte limits are declared payload sizes, not measured JVM heap usage. A source must enforce
 * maxResultBytes while constructing its immutable result, not allocate an unbounded result first.
 * Cache bytes and items are hard limits; at most maxRunning sources may each construct one bounded
 * result. Queued jobs contain keys/tokens/leases, not results. JVM headers, keys, futures, source
 * scratch space, and results retained by callers are outside the declared-byte accounting.
 * In-flight subscriber counts are bounded too. There is no unbounded completed-result/error queue:
 * once settled, only the bounded cache and caller-owned leases retain payloads/errors.
 *
 * <p>A future's completion is not permission to publish. Use Lease.publishIfCurrent with the current
 * owner stamp for an atomic fence against cancellation, invalidation, close, and ABA ownership.
 */
public final class BoundedLodSummaryJobs<P extends BoundedLodSummaryJobs.Payload> implements AutoCloseable {
    /** Implementations must be deeply immutable and CPU-only; declaredBytes must be stable and O(1). */
    public interface Payload {
        long declaredBytes();
    }

    @FunctionalInterface
    public interface Source<P extends Payload> {
        CompletionStage<P> load(LodSummaryKey key, Cancellation cancellation, long maxResultBytes);
    }

    public static final class Cancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        public boolean isCancelled() { return cancelled.get(); }
        public void throwIfCancelled() {
            if (isCancelled()) throw new CancellationException("LOD summary no longer requested");
        }
        private void cancel() { cancelled.set(true); }
    }

    public record Limits(int maxRunning, int maxQueued, int maxLeasesPerKey,
                         int maxCachedItems, long maxCachedBytes, long maxResultBytes) {
        public Limits {
            if (maxRunning < 1 || maxQueued < 0 || maxLeasesPerKey < 1 || maxCachedItems < 0
                    || maxCachedBytes < 0 || maxResultBytes < 1)
                throw new IllegalArgumentException("Invalid LOD summary budgets");
            Math.addExact(maxRunning, maxQueued);
            Math.addExact(maxCachedBytes, Math.multiplyExact((long) maxRunning, maxResultBytes));
        }
    }

    public record Result<P extends Payload>(LodSummaryKey key, LodJobStamp stamp, long generation, P payload) {
        public Result {
            Objects.requireNonNull(key); Objects.requireNonNull(stamp); Objects.requireNonNull(payload);
        }
    }

    public record Stats(int running, int queued, int cachedItems, long cachedBytes, long generation,
                        long completed, long failed, long cancelled, long cacheHits,
                        long deduplicated, long rejected, long evicted, boolean closed) { }

    private record Cached<P>(P payload, long bytes) { }

    private final Source<P> source;
    private final Executor executor;
    private final Limits limits;
    private final boolean strictPhysicalSlots;
    private final Object lock = new Object();
    private final Map<LodSummaryKey, Entry> entries = new HashMap<>();
    private final Set<Entry> running = new HashSet<>();
    private final Set<Entry> physicalSlots = new HashSet<>();
    private final ArrayDeque<Entry> queued = new ArrayDeque<>();
    private final LinkedHashMap<LodSummaryKey, Cached<P>> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final ConcurrentLinkedQueue<Entry> dispatchQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean dispatching = new AtomicBoolean();
    private long cachedBytes, generation, completed, failed, cancelled, cacheHits, deduplicated, rejected, evicted;
    private boolean closed;
    // Completion/cancellation of a subscriber is not physical source or callback drain.
    private int activeInvocations, activeCompletions;

    public BoundedLodSummaryJobs(Source<P> source, Executor executor, Limits limits) {
        this(source,executor,limits,false);
    }
    /** Additive result-budget mode: successors wait for actual source and completion stacks. */
    public BoundedLodSummaryJobs(Source<P> source,Executor executor,Limits limits,boolean strictPhysicalSlots){
        this.strictPhysicalSlots=strictPhysicalSlots;
        this.source = Objects.requireNonNull(source);
        this.executor = Objects.requireNonNull(executor);
        this.limits = Objects.requireNonNull(limits);
    }

    /** Fails immediately on admission pressure; never blocks for capacity or does source work inline. */
    public Lease request(LodSummaryKey key, LodJobStamp stamp) {
        Objects.requireNonNull(key); Objects.requireNonNull(stamp);
        Entry start = null;
        Lease lease;
        synchronized (lock) {
            if (closed) throw reject("LOD summary jobs closed");
            Cached<P> cached = cache.get(key);
            if (cached != null) {
                cacheHits++;
                lease = new Lease(null, key, stamp, generation);
                lease.result = new Result<>(key, stamp, generation, cached.payload());
            } else {
                Entry entry = entries.get(key);
                if (entry == null) {
                    if (occupiedSlots() >= limits.maxRunning() && queued.size() >= limits.maxQueued())
                        throw reject("LOD summary work budget exhausted");
                    entry = new Entry(key, generation);
                    entries.put(key, entry);
                    if (occupiedSlots() < limits.maxRunning()) { running.add(entry);physicalSlots.add(entry); start = entry; }
                    else queued.addLast(entry);
                } else {
                    if (entry.leases.size() >= limits.maxLeasesPerKey())
                        throw reject("LOD summary subscriber budget exhausted");
                    deduplicated++;
                }
                lease = new Lease(entry, key, stamp, generation);
                entry.leases.add(lease);
            }
        }
        if (start != null) dispatch(start);
        Result<P> ready = lease.result;
        if (ready != null) lease.future.complete(ready);
        return lease;
    }

    private RejectedExecutionException reject(String message) {
        rejected++;
        return new RejectedExecutionException(message);
    }

    private void dispatch(Entry entry) {
        dispatchQueue.add(entry);
        if (!dispatching.compareAndSet(false, true)) return;
        do {
            Entry next;
            while ((next = dispatchQueue.poll()) != null) {
                Entry task = next;
                Thread submitter = Thread.currentThread();
                AtomicBoolean returned = new AtomicBoolean();
                AtomicBoolean claimed = new AtomicBoolean();
                try {
                    executor.execute(() -> {
                        if (!claimed.compareAndSet(false, true)) return;
                        if (Thread.currentThread() == submitter && !returned.get())
                            finish(task, null, new RejectedExecutionException("Inline LOD source execution prohibited"));
                        else invoke(task);
                    });
                } catch (RuntimeException error) {
                    // Even an executor that starts work and then throws must not free a live slot.
                    if (claimed.compareAndSet(false, true)) finish(task, null, error);
                } finally { returned.set(true); }
            }
            dispatching.set(false);
        } while (!dispatchQueue.isEmpty() && dispatching.compareAndSet(false, true));
    }

    private void invoke(Entry entry) {
        synchronized(lock){activeInvocations++;entry.invocations++;}
        try {
            entry.cancellation.throwIfCancelled();
            CompletionStage<P> stage = Objects.requireNonNull(
                    source.load(entry.key, entry.cancellation, limits.maxResultBytes()), "Null LOD source stage");
            stage.whenComplete((payload, error) -> finish(entry, payload, error));
        } catch (Throwable error) { finish(entry, null, error); }
        finally { synchronized(lock){activeInvocations--;entry.invocations--;} settlePhysical(entry); }
    }

    private void finish(Entry entry, P payload, Throwable error) {
        synchronized(lock){activeCompletions++;entry.completions++;}
        try { finishInternal(entry,payload,error); }
        finally { synchronized(lock){activeCompletions--;entry.completions--;} settlePhysical(entry); }
    }

    private void finishInternal(Entry entry, P payload, Throwable error) {
        long bytes = 0;
        if (error == null) {
            try {
                bytes = Objects.requireNonNull(payload, "Null LOD source payload").declaredBytes();
                if (bytes < 0 || bytes > limits.maxResultBytes())
                    throw new IllegalArgumentException("LOD payload exceeds declared result budget: " + bytes);
            } catch (Throwable invalid) { error = invalid; }
        }
        List<Lease> notify;
        List<Entry> starts = new ArrayList<>();
        synchronized (lock) {
            if (!running.remove(entry)) return;
            entries.remove(entry.key, entry);
            notify = List.copyOf(entry.leases);
            entry.leases.clear();
            if (closed || entry.generation != generation || entry.cancellation.isCancelled()) {
                cancelled++;
                error = new CancellationException("Stale LOD summary");
            } else if (error != null) {
                failed++;
            } else {
                completed++;
                cache(entry.key, payload, bytes);
                for (Lease lease : notify)
                    lease.result = new Result<>(entry.key, lease.stamp, entry.generation, payload);
            }
            if(!strictPhysicalSlots) promote(starts);
        }
        // Schedule successors before any client continuation can block this completion thread.
        starts.forEach(this::dispatch);
        // No client continuations run under the scheduler monitor.
        for (Lease lease : notify) {
            if (error == null) {
                Result<P> ready = lease.result;
                if (ready != null) lease.future.complete(ready);
                else lease.future.cancel(false);
            } else lease.future.completeExceptionally(error);
        }
    }

    private int occupiedSlots(){return strictPhysicalSlots?physicalSlots.size():running.size();}
    private void promote(List<Entry> starts){
        while(!closed&&occupiedSlots()<limits.maxRunning()&&!queued.isEmpty()){
            Entry next=queued.removeFirst();running.add(next);physicalSlots.add(next);starts.add(next);
        }
    }
    private void settlePhysical(Entry entry){
        List<Entry> starts=new ArrayList<>();
        synchronized(lock){
            if(!running.contains(entry)&&entry.invocations==0&&entry.completions==0)physicalSlots.remove(entry);
            if(strictPhysicalSlots)promote(starts);
        }
        starts.forEach(this::dispatch);
    }

    private void cache(LodSummaryKey key, P payload, long bytes) {
        if (limits.maxCachedItems() == 0 || bytes > limits.maxCachedBytes()) return;
        while (cache.size() >= limits.maxCachedItems() || bytes > limits.maxCachedBytes() - cachedBytes) {
            var oldest = cache.entrySet().iterator();
            var removed = oldest.next();
            cachedBytes -= removed.getValue().bytes();
            oldest.remove();
            evicted++;
        }
        cache.put(key, new Cached<>(payload, bytes));
        cachedBytes += bytes;
    }

    private void release(Lease lease) {
        synchronized (lock) {
            Entry entry = lease.entry;
            if (entry != null && entry.leases.remove(lease) && entry.leases.isEmpty()) {
                entry.cancellation.cancel();
                entries.remove(entry.key, entry);
                if (queued.remove(entry)) cancelled++;
            }
            lease.result = null;
        }
        lease.future.cancel(false);
    }

    /** Clears canonical cache and fences every previous lease; running slots remain charged. */
    public void invalidateAll() { invalidate(false); }

    @Override public void close() { invalidate(true); }

    private void invalidate(boolean closing) {
        List<Lease> notify = new ArrayList<>();
        synchronized (lock) {
            if (closed) return;
            generation = Math.incrementExact(generation);
            closed = closing;
            cache.clear(); cachedBytes = 0;
            for (Entry entry : running) cancelEntry(entry, notify);
            for (Entry entry : queued) { cancelEntry(entry, notify); cancelled++; }
            queued.clear(); entries.clear();
        }
        for (Lease lease : notify) lease.future.cancel(false);
    }

    private void cancelEntry(Entry entry, List<Lease> notify) {
        entry.cancellation.cancel();
        for (Lease lease : entry.leases) {
            lease.released.set(true);
            lease.result = null;
            notify.add(lease);
        }
        entry.leases.clear();
    }

    /** Includes source stacks and synchronous completion consumers after running-slot removal. */
    public boolean physicalWorkPending(){
        synchronized(lock){return !physicalSlots.isEmpty()||!running.isEmpty()||!queued.isEmpty()||activeInvocations!=0||activeCompletions!=0;}
    }

    /** Stable after close; cancellation alone never establishes this barrier. */
    public boolean physicalDrainComplete(){
        synchronized(lock){return closed&&!physicalWorkPending()&&cache.isEmpty()&&entries.isEmpty();}
    }

    public Stats stats() {
        synchronized (lock) {
            return new Stats(running.size(), queued.size(), cache.size(), cachedBytes, generation,
                    completed, failed, cancelled, cacheHits, deduplicated, rejected, evicted, closed);
        }
    }

    private final class Entry {
        final LodSummaryKey key;
        final long generation;
        final Cancellation cancellation = new Cancellation();
        final Set<Lease> leases = new LinkedHashSet<>();
        int invocations,completions;
        Entry(LodSummaryKey key, long generation) { this.key = key; this.generation = generation; }
    }

    public final class Lease implements AutoCloseable {
        private final Entry entry;
        private final LodSummaryKey key;
        private final LodJobStamp stamp;
        private final long generation;
        private final AtomicBoolean released = new AtomicBoolean();
        private final CompletableFuture<Result<P>> future = new CompletableFuture<>();
        private volatile Result<P> result;

        private Lease(Entry entry, LodSummaryKey key, LodJobStamp stamp, long generation) {
            this.entry = entry; this.key = key; this.stamp = stamp; this.generation = generation;
            future.whenComplete((ignored, error) -> { if (future.isCancelled()) close(); });
        }

        public LodSummaryKey key() { return key; }
        public LodJobStamp stamp() { return stamp; }
        public CompletableFuture<Result<P>> future() { return future; }

        /** A snapshot only; use publishIfCurrent for a check-and-publish operation. */
        public boolean isCurrent(LodJobStamp currentStamp) {
            Objects.requireNonNull(currentStamp);
            synchronized (lock) { return current(currentStamp); }
        }

        private boolean current(LodJobStamp currentStamp) {
            return !closed && !released.get() && generation == BoundedLodSummaryJobs.this.generation
                    && stamp.equals(currentStamp);
        }

        /**
         * Runs a short caller-supplied publication under the invalidation fence only when ready/current.
         * The caller must also serialize changes to its current stamp with publication (normally on the
         * render thread). Do not block or await scheduler work inside publication. This executes no
         * source work and is safe to call from the render thread. Exceptions from publication propagate.
         */
        public boolean publishIfCurrent(LodJobStamp currentStamp, Consumer<? super P> publication) {
            Objects.requireNonNull(currentStamp); Objects.requireNonNull(publication);
            synchronized (lock) {
                if (!current(currentStamp) || result == null) return false;
                publication.accept(result.payload());
                return true;
            }
        }

        @Override public void close() {
            if (released.compareAndSet(false, true)) release(this);
        }
    }
}
