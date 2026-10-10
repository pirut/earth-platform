package io.earthplatform.core;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/**
 * Engine-independent residency and durable edit facade. Only ticketed cubes have resident payloads.
 * Generation is asynchronous. Disk reads and write-through edits run on one bounded, owned I/O worker.
 * Successful setBlock completion means the overlay is durable; eviction cannot discard it.
 * Readiness reestablishes primary-overlay durability before exposing an earlier uncertain write.
 * close drains accepted disk work and therefore must not run on a game tick or this world's I/O thread.
 */
public final class CubicWorld implements AutoCloseable {
    private final WorldIdentity identity;
    private final CubeTickets tickets;
    private final BoundedCubeLoader loader;
    private final EditStore store;
    private final ThreadPoolExecutor io;
    private final int maxPendingEdits;
    private final CompletableFuture<Void> shutdown = new CompletableFuture<>();
    private volatile Thread ioThread;
    private final Object lock = new Object();
    private final Map<CubePos, Resident> residents = new HashMap<>();
    private boolean closed;
    private int pendingEdits;

    public CubicWorld(WorldIdentity identity, CubeProvider provider, EditStore store, Executor generationExecutor,
                      int residentBudget, int maxTickets, int maxConcurrentGeneration, int maxPendingEdits) {
        this.identity = Objects.requireNonNull(identity); this.store = Objects.requireNonNull(store);
        if (store instanceof FileEditStore files && !files.identity().equals(identity)) throw new IllegalArgumentException("Store identity mismatch");
        if (maxPendingEdits < 1) throw new IllegalArgumentException("Positive edit queue limit required");
        this.maxPendingEdits = maxPendingEdits;
        tickets = new CubeTickets(residentBudget, maxTickets);
        // The entire new desired set fits while old cancelled generation still settles after teleportation.
        loader = new BoundedCubeLoader(provider, generationExecutor, maxConcurrentGeneration, residentBudget, 1);
        io = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.addExact(residentBudget, maxPendingEdits)), task -> {
                    Thread thread = new Thread(task, "earth-overlay-io"); thread.setDaemon(true); ioThread = thread; return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }
    public WorldIdentity identity() { return identity; }
    public CubeTickets.Delta updateTicket(String player, CubePos center, int horizontal, int vertical) {
        CubeTickets.Delta delta; List<Runnable> actions;
        synchronized (lock) { ensureOpen(); delta = tickets.update(player, center, horizontal, vertical); actions = stage(delta); }
        actions.forEach(Runnable::run); return delta;
    }
    public CubeTickets.Delta removeTicket(String player) {
        CubeTickets.Delta delta; List<Runnable> actions;
        synchronized (lock) { ensureOpen(); delta = tickets.remove(player); actions = stage(delta); }
        actions.forEach(Runnable::run); return delta;
    }
    /** Mutate the entire residency set before publishing futures that can reenter this world. */
    private List<Runnable> stage(CubeTickets.Delta delta) {
        List<Runnable> actions = new ArrayList<>();
        for (CubePos position : delta.removed()) {
            Resident removed = residents.remove(position);
            if (removed != null) {
                if (removed.loadTask != null) io.remove(removed.loadTask);
                // These private lease callbacks suppress publication for stale residents.
                // Retire reservations now, before any later transition may admit replacement work.
                if (removed.lease != null) removed.lease.close();
                actions.add(() -> removed.ready.cancel(false));
            }
        }
        for (CubePos position : new TreeSet<>(delta.added())) {
            Resident resident = new Resident(position); residents.put(position, resident);
            actions.add(() -> start(resident));
        }
        return actions;
    }
    private void start(Resident resident) {
        CubePos position = resident.position; Throwable requestFailure = null;
        synchronized (lock) {
            if (closed || residents.get(position) != resident) return;
            try { resident.lease = loader.request(position); }
            catch (RuntimeException failure) { requestFailure = failure; }
        }
        if (requestFailure != null) { resident.ready.completeExceptionally(requestFailure); return; }
        resident.lease.future().whenComplete((base, error) -> {
            if (error != null) {
                synchronized (lock) { if (closed || residents.get(position) != resident) return; }
                resident.ready.completeExceptionally(error); return;
            }
            Runnable readTask = () -> {
                try {
                    synchronized (lock) { if (closed || residents.get(position) != resident) return; }
                    EditStore.LoadedEdits loaded = store.load(position);
                    if (loaded.status() == EditStore.Status.RECOVERED_BACKUP)
                        throw new IOException(loaded.warning()); // No silent downgrade of a player's edits.
                    // A failed save may have renamed its primary before directory fsync failed.
                    // Reading those bytes is not a durability acknowledgement. An equal save repairs
                    // the barrier without a new revision, also after eviction or reopening the store.
                    if (loaded.status() == EditStore.Status.PRIMARY) store.save(loaded.snapshot());
                    CubeView view = new CubeView(position, base, loaded.snapshot());
                    synchronized (lock) {
                        if (closed || residents.get(position) != resident) return;
                        resident.view = view; resident.loadTask = null;
                    }
                    resident.ready.complete(view);
                } catch (Throwable failure) { resident.ready.completeExceptionally(failure); }
            };
            Throwable failure = null;
            synchronized (lock) {
                if (closed || residents.get(position) != resident) return;
                resident.loadTask = readTask;
                try { io.execute(readTask); }
                catch (RejectedExecutionException rejected) { resident.loadTask = null; failure = rejected; }
            }
            if (failure != null) resident.ready.completeExceptionally(failure);
        });
    }
    /** Explicit bounded retry after a provider or disk-read failure; never rewrites saved edits. */
    public CompletableFuture<CubeView> retry(CubePos position) {
        List<Runnable> actions; CompletableFuture<CubeView> result;
        synchronized (lock) {
            ensureOpen(); Resident failed = requireResident(position);
            if (!failed.ready.isCompletedExceptionally()) throw new IllegalStateException("Cube has not failed");
            actions = stage(new CubeTickets.Delta(Set.of(position), Set.of(position)));
            result = residents.get(position).ready.copy();
        }
        actions.forEach(Runnable::run); return result;
    }
    /** Completion snapshot; use view() for the latest edits after this first-ready snapshot. */
    public CompletableFuture<CubeView> ready(CubePos position) {
        synchronized (lock) { ensureOpen(); return requireResident(position).ready.copy(); }
    }
    public Optional<CubeView> view(CubePos position) {
        synchronized (lock) { ensureOpen(); Resident resident = residents.get(position); return Optional.ofNullable(resident == null ? null : resident.view); }
    }
    public int getBlock(long x, long y, long z) {
        synchronized (lock) {
            ensureOpen(); Resident resident = requireResident(CubePos.fromBlock(x, y, z));
            if (resident.view == null) throw new IllegalStateException("Cube is not ready");
            return resident.view.get(CubePos.indexOfBlock(x, y, z));
        }
    }
    public CompletableFuture<Long> setBlock(long x, long y, long z, int state) {
        ImmutableCube.checkState(state); CubePos position = CubePos.fromBlock(x, y, z); int index = CubePos.indexOfBlock(x, y, z);
        CompletableFuture<Long> result = new CompletableFuture<>();
        synchronized (lock) {
            ensureOpen();
            if (requireResident(position).view == null) throw new IllegalStateException("Cube is not ready");
            if (pendingEdits >= maxPendingEdits) throw new RejectedExecutionException("Pending edit budget exhausted");
            pendingEdits++;
            try {
                io.execute(() -> {
                    Long revision = null;
                    Throwable error = null;
                    try {
                        // Reload in serial I/O order so eviction/reentry cannot race a previously accepted edit.
                        EditStore.LoadedEdits latest = store.load(position);
                        if (latest.status() == EditStore.Status.RECOVERED_BACKUP) throw new IOException(latest.warning());
                        EditSnapshot replacement = latest.snapshot().withBlock(index, state);
                        store.save(replacement);
                        synchronized (lock) {
                            Resident current = residents.get(position);
                            if (current != null && current.view != null)
                                current.view = new CubeView(position, current.view.base(), replacement);
                        }
                        revision = replacement.revision();
                    } catch (Throwable failure) { error = failure; }
                    finally { synchronized (lock) { pendingEdits--; } }
                    if (error == null) result.complete(revision); else result.completeExceptionally(error);
                });
            } catch (RejectedExecutionException failure) { pendingEdits--; throw failure; }
        }
        return result;
    }
    public Stats stats() {
        synchronized (lock) {
            long ready = residents.values().stream().filter(r -> r.view != null).count();
            return new Stats(residents.size(), (int) ready, pendingEdits, tickets.ticketCount(), loader.stats());
        }
    }
    private Resident requireResident(CubePos position) {
        Resident resident = residents.get(position);
        if (resident == null) throw new IllegalArgumentException("Cube has no residency ticket: " + position);
        return resident;
    }
    private void ensureOpen() { if (closed) throw new IllegalStateException("World closed"); }
    /** Stop admitting work and close the store automatically after all accepted disk work drains. */
    public CompletableFuture<Void> closeAsync() {
        List<Resident> release;
        synchronized (lock) {
            if (closed) return shutdown.copy();
            closed = true; release = List.copyOf(residents.values()); residents.clear();
            for (Resident resident : release) if (resident.loadTask != null) io.remove(resident.loadTask);
            // Removing resident reads reserves a queue slot beyond the bounded admitted edits.
            // This FIFO barrier runs on the worker even for a never-used world, never on the caller.
            io.execute(() -> {
                try { store.close(); shutdown.complete(null); }
                catch (Throwable error) { shutdown.completeExceptionally(error); }
            });
            io.shutdown();
        }
        // Initiate disk shutdown before completing any public residency promises.
        loader.close();
        for (Resident resident : release) resident.ready.cancel(false);
        return shutdown.copy();
    }
    @Override public void close() throws IOException {
        if (Thread.currentThread() == ioThread)
            throw new IllegalStateException("Use closeAsync from an I/O completion callback; blocking here would deadlock");
        try { closeAsync().get(60, TimeUnit.SECONDS); }
        catch (TimeoutException error) { throw new IOException("Accepted edits still draining; store will close automatically afterward", error); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("Interrupted waiting; store will close automatically after accepted edits drain", error); }
        catch (ExecutionException error) { throw new IOException("Store closure failed", error.getCause()); }
    }
    public record CubeView(CubePos position, ImmutableCube base, EditSnapshot edits) {
        public CubeView {
            Objects.requireNonNull(position); Objects.requireNonNull(base); Objects.requireNonNull(edits);
            if (!position.equals(edits.position())) throw new IllegalArgumentException("Overlay position mismatch");
        }
        public int get(int index) { return edits.read(base, index); }
        public ImmutableCube materialize() { return edits.applyTo(base); }
    }
    public record Stats(int residentCubes, int readyCubes, int pendingEdits, int tickets, BoundedCubeLoader.Stats loader) {}
    private static final class Resident {
        private final CubePos position;
        private final CompletableFuture<CubeView> ready = new CompletableFuture<>();
        private BoundedCubeLoader.Lease lease;
        private Runnable loadTask;
        private CubeView view;
        private Resident(CubePos position) { this.position = Objects.requireNonNull(position); }
    }
}
