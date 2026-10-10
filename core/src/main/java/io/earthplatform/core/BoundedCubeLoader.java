package io.earthplatform.core;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Deduplicated, bounded asynchronous work. Leases cancel independently; the provider is cancelled
 * only after its final lease closes. A cancelled running request still occupies its slot until the
 * provider settles, so uncooperative providers cannot create unbounded orphan work.
 * The supplied executor is caller-owned and must not execute tasks inline on the game tick thread.
 */
public final class BoundedCubeLoader implements AutoCloseable {
    private final CubeProvider provider;
    private final Executor executor;
    private final int maxRunning, maxQueued, maxLeasesPerCube;
    private final Object lock = new Object();
    private final Map<CubePos, Entry> entries = new HashMap<>();
    private final Set<Entry> runningEntries = new HashSet<>();
    private final ArrayDeque<Entry> queue = new ArrayDeque<>();
    private final ConcurrentLinkedQueue<Entry> dispatchQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean dispatching = new AtomicBoolean();
    private boolean closed;
    private long completed, failed, cancelled;

    public BoundedCubeLoader(CubeProvider provider, Executor executor, int maxRunning, int maxQueued, int maxLeasesPerCube) {
        this.provider = Objects.requireNonNull(provider); this.executor = Objects.requireNonNull(executor);
        if (maxRunning < 1 || maxQueued < 0 || maxLeasesPerCube < 1) throw new IllegalArgumentException("Invalid loader limits");
        Math.addExact(maxRunning, maxQueued);
        this.maxRunning = maxRunning; this.maxQueued = maxQueued; this.maxLeasesPerCube = maxLeasesPerCube;
    }
    public int capacity() { return maxRunning + maxQueued; }
    public Lease request(CubePos position) {
        Objects.requireNonNull(position);
        Entry start = null;
        Lease lease;
        synchronized (lock) {
            if (closed) throw new RejectedExecutionException("Loader closed");
            Entry entry = entries.get(position);
            if (entry == null) {
                if (runningEntries.size() >= maxRunning && queue.size() >= maxQueued)
                    throw new RejectedExecutionException("Cube request budget exhausted");
                entry = new Entry(position); entries.put(position, entry);
                if (runningEntries.size() < maxRunning) { runningEntries.add(entry); start = entry; }
                else queue.addLast(entry);
            }
            if (entry.leases.size() >= maxLeasesPerCube) throw new RejectedExecutionException("Cube subscriber limit");
            lease = new Lease(entry); entry.leases.add(lease);
        }
        if (start != null) dispatch(start);
        return lease;
    }
    private void dispatch(Entry entry) {
        dispatchQueue.add(entry);
        if (!dispatching.compareAndSet(false, true)) return;
        do {
            Entry next;
            while ((next = dispatchQueue.poll()) != null) {
                Entry task = next;
                try { executor.execute(() -> invoke(task)); }
                catch (RuntimeException error) { finish(task, null, error); }
            }
            dispatching.set(false);
        } while (!dispatchQueue.isEmpty() && dispatching.compareAndSet(false, true));
    }
    private void invoke(Entry entry) {
        try {
            entry.token.throwIfCancelled();
            CompletableFuture<ImmutableCube> source = Objects.requireNonNull(provider.load(entry.position, entry.token), "Null provider future");
            source.whenComplete((cube, error) -> finish(entry, cube, error));
        } catch (Throwable error) { finish(entry, null, error); }
    }
    private void finish(Entry entry, ImmutableCube cube, Throwable error) {
        List<Lease> notify;
        List<Entry> starts = new ArrayList<>();
        if (error == null && cube == null) error = new NullPointerException("Provider returned null cube");
        synchronized (lock) {
            if (!runningEntries.remove(entry)) return;
            entries.remove(entry.position, entry);
            notify = List.copyOf(entry.leases); entry.leases.clear();
            if (entry.token.isCancelled()) cancelled++; else if (error != null) failed++; else completed++;
            while (!closed && runningEntries.size() < maxRunning && !queue.isEmpty()) {
                Entry next = queue.removeFirst(); runningEntries.add(next); starts.add(next);
            }
        }
        for (Lease lease : notify) {
            if (error == null) lease.future.complete(cube); else lease.future.completeExceptionally(error);
        }
        starts.forEach(this::dispatch);
    }
    private void release(Lease lease) {
        synchronized (lock) {
            Entry entry = lease.entry;
            if (entry.leases.remove(lease) && entry.leases.isEmpty()) {
                entry.token.cancel(); entries.remove(entry.position, entry);
                if (queue.remove(entry)) cancelled++;
            }
        }
        lease.future.cancel(false);
    }
    public Stats stats() {
        synchronized (lock) { return new Stats(runningEntries.size(), queue.size(), completed, failed, cancelled, closed); }
    }
    @Override public void close() {
        List<Lease> release = new ArrayList<>();
        synchronized (lock) {
            if (closed) return;
            closed = true;
            for (Entry entry : runningEntries) { entry.token.cancel(); release.addAll(entry.leases); entry.leases.clear(); }
            for (Entry entry : queue) { entry.token.cancel(); release.addAll(entry.leases); entry.leases.clear(); cancelled++; }
            queue.clear(); entries.clear();
        }
        for (Lease lease : release) lease.future.cancel(false);
    }
    public record Stats(int running, int queued, long completed, long failed, long cancelled, boolean closed) {}
    private static final class Entry {
        private final CubePos position;
        private final CancellationToken token = new CancellationToken();
        private final Set<Lease> leases = new LinkedHashSet<>();
        private Entry(CubePos position) { this.position = position; }
    }
    public final class Lease implements AutoCloseable {
        private final Entry entry;
        private final AtomicBoolean released = new AtomicBoolean();
        private final CompletableFuture<ImmutableCube> future = new CompletableFuture<>();
        private Lease(Entry entry) {
            this.entry = entry;
            future.whenComplete((ignored, error) -> { if (future.isCancelled()) close(); });
        }
        public CubePos position() { return entry.position; }
        public CompletableFuture<ImmutableCube> future() { return future; }
        @Override public void close() { if (released.compareAndSet(false, true)) release(this); }
    }
}
