package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.CubeProvider;
import io.earthplatform.core.CubeTickets;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * A deliberately fixed-interest first runtime gate. Its private thread is the host's sole owner;
 * callers only exchange immutable values. Neither opening, editing, polling nor closing waits for
 * disk/provider work on the caller. Closing survives a stopped server tick and an abandoned consumer.
 * This is not a moving-interest controller or a Minecraft Level replacement.
 */
public final class NativeCubeActor implements AutoCloseable {
    public static final int RESIDENT_CUBES = 125, RENDER_TARGETS = 27;
    public static final int MAX_RESULT_EVENTS = 4, MAX_EVENTS_PER_POLL = 2, MAX_PENDING_EDITS = 1;
    public static final int MAX_ACTORS = NativeCubicHost.MAX_OWNED_HOSTS;
    private static final AtomicInteger ACTORS = new AtomicInteger();
    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(2);

    public enum Admission { QUEUED, BUSY, CLOSING, NOT_READY }
    public enum EditStatus {
        APPLIED, WORLD_MISMATCH, STALE_EPOCH, UNKNOWN_CUBE, STALE_LEASE, STALE_REVISION,
        POSITION_MISMATCH, INVALID_STATE, NOT_READY, SAVE_FAILED, CLOSING
    }
    public enum Lifecycle { OPENING, OPEN, CLOSING, CLOSED }
    public sealed interface Event permits ReadyMetadata, Snapshot, EditAck, Failure, Closed {}
    public record CubeLease(CubePos position, long leaseGeneration) {
        public long generation() { return leaseGeneration; }
    }
    public record ReadyMetadata(WorldIdentity world, long epoch, CubePos center,
                                List<CubeLease> leases, List<CubePos> renderTargets) implements Event {
        public ReadyMetadata { leases = List.copyOf(leases); renderTargets = List.copyOf(renderTargets); }
    }
    public record Snapshot(EngineCubeSnapshot snapshot) implements Event {}
    public record EditRequest(WorldIdentity world, long epoch, CubePos position, long leaseGeneration,
                              long expectedRevision, long x, long y, long z, int stateId) {
        public EditRequest { Objects.requireNonNull(world); Objects.requireNonNull(position); }
    }
    /** APPLIED is issued only after the durable future and its replacement Snapshot event. */
    public record EditAck(EditRequest request, EditStatus status, long revision, String detail) implements Event {
        public boolean applied() { return status == EditStatus.APPLIED; }
    }
    public record Failure(String detail) implements Event {}
    public record Closed(boolean successful, String detail) implements Event {}
    public record Diagnostics(Lifecycle lifecycle, int queuedEvents, int queuedCommands,
                              boolean pendingEdit, int publishedSnapshots, int admittedEdits) {}

    private final Path directory;
    private final WorldIdentity world;
    private final CubeProvider provider;
    private final EnginePaletteContext palette;
    private final long epoch;
    private final CubePos center;
    private final List<CubeLease> leases;
    private final List<CubePos> renderTargets;
    private final Map<CubePos, Long> leaseByPosition;
    private final ArrayBlockingQueue<EditRequest> commands = new ArrayBlockingQueue<>(MAX_PENDING_EDITS);
    private final ArrayBlockingQueue<Event> results = new ArrayBlockingQueue<>(MAX_RESULT_EVENTS);
    private final ClosingFuture completion = new ClosingFuture();
    private final Object admissionLock = new Object();
    private final Thread actor;
    private final ThreadPoolExecutor bootstrap;
    private volatile boolean stopRequested, pendingEdit;
    private volatile Lifecycle lifecycle = Lifecycle.OPENING;
    private volatile int publishedSnapshots, admittedEdits;

    // Actor-thread-only state. Never expose host, resident views, native sections or save futures.
    private NativeCubicHost host;
    private CompletableFuture<NativeCubicHost> opening;
    private int nextSnapshot;
    private boolean metadataPublished, closeOutputPrepared, hostClosing, failurePublished;
    private EditRequest editing;
    private CompletableFuture<Long> saving;
    private EditAck acknowledgement;
    private boolean replacementPublished;
    private Throwable failure;

    private NativeCubeActor(Path directory, WorldIdentity world, CubeProvider provider,
                            EnginePaletteContext palette, long epoch, CubePos center) {
        this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
        this.world = Objects.requireNonNull(world); this.provider = Objects.requireNonNull(provider);
        this.palette = Objects.requireNonNull(palette); this.center = Objects.requireNonNull(center);
        if (epoch < 0) throw new IllegalArgumentException("Nonnegative actor epoch required");
        this.epoch = epoch;
        List<CubePos> positions = CubeTickets.box(center, 2, 2, RESIDENT_CUBES).stream().sorted().toList();
        List<CubeLease> assigned = new ArrayList<>(RESIDENT_CUBES);
        Map<CubePos, Long> byPosition = new HashMap<>();
        for (int index = 0; index < positions.size(); index++) {
            long lease = index + 1L;
            assigned.add(new CubeLease(positions.get(index), lease)); byPosition.put(positions.get(index), lease);
        }
        leases = List.copyOf(assigned); leaseByPosition = Map.copyOf(byPosition);
        renderTargets = CubeTickets.box(center, 1, 1, RENDER_TARGETS).stream().sorted().toList();
        bootstrap = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), task -> {
            Thread worker = new Thread(task, "earth-native-actor-bootstrap"); worker.setDaemon(true); return worker;
        }, new ThreadPoolExecutor.AbortPolicy());
        actor = new Thread(this::run, "earth-native-cube-actor");
        actor.setDaemon(true);
    }

    /** Starts one bounded actor; Minecraft registries must already be frozen. No filesystem work here. */
    public static NativeCubeActor open(Path directory, WorldIdentity world, CubeProvider provider,
                                       EnginePaletteContext palette, long epoch, CubePos center) {
        for (;;) {
            int count = ACTORS.get();
            if (count >= MAX_ACTORS) throw new RejectedExecutionException("Native actor limit reached");
            if (ACTORS.compareAndSet(count, count + 1)) break;
        }
        NativeCubeActor result = null;
        try {
            result = new NativeCubeActor(directory, world, provider, palette, epoch, center);
            result.actor.start(); return result;
        } catch (Throwable failed) {
            if (result != null) result.bootstrap.shutdown();
            ACTORS.decrementAndGet(); throw failed;
        }
    }

    /** Call once per server tick. Even an excessive requested maximum cannot publish over two events. */
    public List<Event> pollEvents(int maximum) {
        if (maximum < 0) throw new IllegalArgumentException("Nonnegative delivery maximum required");
        List<Event> batch = new ArrayList<>(Math.min(maximum, MAX_EVENTS_PER_POLL));
        for (int count = 0; count < Math.min(maximum, MAX_EVENTS_PER_POLL); count++) {
            Event event = results.poll();
            if (event == null) break;
            batch.add(event);
            if (event instanceof EditAck) {
                synchronized (admissionLock) { pendingEdit = false; }
            }
        }
        LockSupport.unpark(actor);
        return List.copyOf(batch);
    }

    /** QUEUED is queue admission, not edit success. Semantic validation and durability are asynchronous. */
    public Admission tryEdit(EditRequest request) {
        Objects.requireNonNull(request);
        synchronized (admissionLock) {
            if (stopRequested) return Admission.CLOSING;
            if (lifecycle != Lifecycle.OPEN) return Admission.NOT_READY;
            if (pendingEdit) return Admission.BUSY;
            pendingEdit = true;
            if (!commands.offer(request)) { pendingEdit = false; return Admission.BUSY; }
        }
        LockSupport.unpark(actor);
        return Admission.QUEUED;
    }

    /** Only immutable diagnostic counters; obtaining them does not poll or access mutable host state. */
    public Diagnostics diagnostics() {
        return new Diagnostics(lifecycle, results.size(), commands.size(), pendingEdit, publishedSnapshots, admittedEdits);
    }
    /**
     * Stable read-only completion: repeated tick polling creates no retained dependent futures.
     * Cancellation and external completion cannot abandon opening, a save, or the owner's close drain.
     * Consumers wanting an independently cancellable/timeout observation can explicitly take copy().
     */
    public CompletableFuture<Closed> closed() { return completion; }

    /** Nonblocking stop signal. The actor continues pollClose until providers and durable I/O settle. */
    @Override public void close() {
        synchronized (admissionLock) {
            stopRequested = true;
            if (lifecycle != Lifecycle.CLOSED) lifecycle = Lifecycle.CLOSING;
        }
        LockSupport.unpark(actor);
    }

    private void run() {
        try {
            try {
                opening = NativeCubicHost.openAsync(directory, world, provider, palette,
                        new NativeCubicHost.Limits(RESIDENT_CUBES, 1, 2, MAX_PENDING_EDITS), bootstrap);
            } catch (Throwable problem) { fail(problem); }
            while (!completion.isDone()) {
                try { step(); }
                catch (Throwable problem) { fail(problem); }
                if (!completion.isDone()) {
                    // Interruption never abandons a host. It requests the same owner-driven drain.
                    if (Thread.interrupted()) close();
                    LockSupport.parkNanos(POLL_NANOS);
                }
            }
        } finally { bootstrap.shutdown(); ACTORS.decrementAndGet(); }
    }

    private void step() {
        if (host == null && opening != null) {
            if (!opening.isDone()) return;
            CompletableFuture<NativeCubicHost> settled = opening; opening = null;
            host = settled.join();
            if (!stopRequested) host.updateTicket("fixed-native-proof", center, 2, 2);
        }
        if (stopRequested) { drainClose(); return; }
        if (host == null) throw new IllegalStateException("Actor has no opened host");
        if (!metadataPublished) {
            if (!results.offer(new ReadyMetadata(world, epoch, center, leases, renderTargets))) return;
            metadataPublished = true; lifecycle = Lifecycle.OPEN;
        }
        if (editing == null && acknowledgement == null && results.remainingCapacity() > 0) {
            EditRequest request = commands.poll();
            if (request != null) startEdit(request);
        }
        advanceEdit();
        if (editing != null || acknowledgement != null) return;
        // Never materialize a snapshot without a free result slot. At most two conversions per pass.
        for (int count = 0; count < MAX_EVENTS_PER_POLL && nextSnapshot < leases.size()
                && results.remainingCapacity() > 0; count++) {
            CubeLease lease = leases.get(nextSnapshot);
            var result = host.pollSnapshot(lease.position(), epoch, lease.leaseGeneration());
            if (result.status() == NativeCubicHost.Status.LOADING) return;
            if (result.status() != NativeCubicHost.Status.READY)
                throw new IllegalStateException("Native cube unavailable: " + lease.position(), result.failure());
            publishSnapshot(result.snapshot()); nextSnapshot++;
        }
    }

    private void startEdit(EditRequest request) {
        editing = request;
        if (!world.equals(request.world())) { reject(EditStatus.WORLD_MISMATCH, -1, "World identity mismatch"); return; }
        if (epoch != request.epoch()) { reject(EditStatus.STALE_EPOCH, -1, "Stale epoch"); return; }
        Long lease = leaseByPosition.get(request.position());
        if (lease == null) { reject(EditStatus.UNKNOWN_CUBE, -1, "Cube is outside fixed interest"); return; }
        if (lease != request.leaseGeneration()) { reject(EditStatus.STALE_LEASE, -1, "Stale lease"); return; }
        try {
            if (!request.position().equals(CubePos.fromBlock(request.x(), request.y(), request.z()))) {
                reject(EditStatus.POSITION_MISMATCH, -1, "Block coordinate is outside the requested cube"); return;
            }
        } catch (ArithmeticException invalid) {
            reject(EditStatus.POSITION_MISMATCH, -1, "Block coordinate exceeds the cube address domain"); return;
        }
        try { NativeCubeCodec.registeredState(request.stateId()); }
        catch (IllegalArgumentException invalid) { reject(EditStatus.INVALID_STATE, -1, "Unknown native state ID"); return; }
        var current = host.pollSnapshot(request.position(), epoch, lease);
        if (current.status() != NativeCubicHost.Status.READY) {
            reject(EditStatus.NOT_READY, -1, "Authoritative cube is not ready"); return;
        }
        long revision = current.snapshot().revision();
        if (revision != request.expectedRevision()) {
            reject(EditStatus.STALE_REVISION, revision, "Expected revision differs from durable revision"); return;
        }
        synchronized (admissionLock) {
            if (stopRequested) { reject(EditStatus.CLOSING, revision, "Actor is closing"); return; }
            try {
                saving = host.setBlockId(request.x(), request.y(), request.z(), request.stateId());
                admittedEdits++;
            }
            catch (RuntimeException problem) { reject(EditStatus.SAVE_FAILED, revision, detail(problem)); }
        }
    }

    private void advanceEdit() {
        if (saving != null) {
            if (!saving.isDone()) return;
            CompletableFuture<Long> settled = saving; saving = null;
            try {
                long revision = settled.join();
                acknowledgement = new EditAck(editing, EditStatus.APPLIED, revision, "Durable save completed");
            } catch (CompletionException problem) { reject(EditStatus.SAVE_FAILED, -1, detail(problem)); }
        }
        if (acknowledgement == null) return;
        if (acknowledgement.applied() && !replacementPublished) {
            if (results.remainingCapacity() == 0) return;
            var changed = host.pollSnapshot(editing.position(), epoch, editing.leaseGeneration());
            if (changed.status() != NativeCubicHost.Status.READY
                    || changed.snapshot().revision() != acknowledgement.revision())
                throw new IllegalStateException("Durable edit did not produce its exact authoritative snapshot");
            publishSnapshot(changed.snapshot()); replacementPublished = true;
        }
        if (!results.offer(acknowledgement)) return;
        editing = null; acknowledgement = null; replacementPublished = false;
        // pendingEdit stays true until the consumer sees the acknowledgement.
    }

    private void reject(EditStatus status, long revision, String message) {
        acknowledgement = new EditAck(editing, status, revision, message);
    }

    private void publishSnapshot(EngineCubeSnapshot snapshot) {
        if (!results.offer(new Snapshot(snapshot))) throw new IllegalStateException("Actor result bound violated");
        publishedSnapshots++;
    }

    private void fail(Throwable problem) {
        if (failure == null) failure = unwrap(problem);
        stopRequested = true;
    }

    private void drainClose() {
        lifecycle = Lifecycle.CLOSING;
        if (!closeOutputPrepared) {
            // Discard undelivered scene data, reserving room for a final edit snapshot/ack and Closed.
            // A consumer that has quit must never retain the writer lock or stall durable I/O.
            results.clear(); closeOutputPrepared = true;
            EditRequest queued = commands.poll();
            if (queued != null) results.offer(new EditAck(queued, EditStatus.CLOSING, -1, "Actor closed before edit admission"));
        }
        if (failure != null && !failurePublished) {
            // Report a failure even when an uncooperative provider keeps the close drain open.
            if (results.remainingCapacity() == 0) results.poll();
            results.offer(new Failure(detail(failure))); failurePublished = true;
        }
        if (host == null) { finish(); return; }
        if (!hostClosing) {
            if (failure == null) {
                advanceEdit();
                if (saving != null || acknowledgement != null) return;
            }
            // Even fatal snapshot/conversion failures drain a previously accepted save through the host.
            host.beginClose(); hostClosing = true;
        }
        var status = host.pollClose();
        if (!status.complete()) return;
        if (status.failure() != null && failure == null) failure = status.failure();
        finish();
    }

    private void finish() {
        if (failure != null && !failurePublished) {
            while (results.remainingCapacity() < 2) results.poll();
            results.offer(new Failure(detail(failure))); failurePublished = true;
        } else if (results.remainingCapacity() == 0) results.poll();
        Closed closed = new Closed(failure == null, failure == null ? "Native host drained and closed" : detail(failure));
        results.offer(closed); lifecycle = Lifecycle.CLOSED; completion.publish(closed);
    }

    private static Throwable unwrap(Throwable problem) {
        return problem instanceof CompletionException && problem.getCause() != null ? problem.getCause() : problem;
    }
    private static String detail(Throwable problem) {
        Throwable cause = unwrap(problem);
        String message = cause.getMessage();
        String description = cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        return description.length() <= 512 ? description : description.substring(0, 512);
    }

    private static final class ClosingFuture extends CompletableFuture<Closed> {
        private void publish(Closed result) { super.complete(result); }
        @Override public boolean cancel(boolean interrupt) { return false; }
        @Override public boolean complete(Closed result) { return false; }
        @Override public boolean completeExceptionally(Throwable failure) { return false; }
        @Override public void obtrudeValue(Closed result) { throw readOnly(); }
        @Override public void obtrudeException(Throwable failure) { throw readOnly(); }
        @Override public CompletableFuture<Closed> completeAsync(Supplier<? extends Closed> supplier) { throw readOnly(); }
        @Override public CompletableFuture<Closed> completeAsync(Supplier<? extends Closed> supplier, Executor executor) { throw readOnly(); }
        @Override public CompletableFuture<Closed> orTimeout(long timeout, TimeUnit unit) { throw readOnly(); }
        @Override public CompletableFuture<Closed> completeOnTimeout(Closed result, long timeout, TimeUnit unit) { throw readOnly(); }
        private static UnsupportedOperationException readOnly() {
            return new UnsupportedOperationException("Actor owns completion; time out or cancel an explicit observation copy instead");
        }
    }
}
