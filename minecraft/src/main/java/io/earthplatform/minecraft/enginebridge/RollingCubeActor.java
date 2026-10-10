package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.CubeProvider;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 * One owner-thread extension of the existing durable native host. A candidate is streamed while the
 * committed ticket is still held, and only an explicit, exact-generation commit retires that ticket.
 * The caller must verify its native/DH pair acknowledgement before calling {@link #commitPrepared}.
 * Goals coalesce in one slot; they cannot cancel or replace a partially streamed candidate.
 *
 * <p>The 213-coordinate bound is not a bound on all object copies. The host has at most 213 core
 * residents and encoded snapshot caches, the output queue has four snapshot/event reference slots,
 * and current/candidate version maps have at most 304 entries total. No additional stage snapshot
 * map exists. Two physical provider reservations remain owned until their futures settle, including
 * cancellation during close. Consumers own and must bound any values retained after polling.</p>
 */
public final class RollingCubeActor implements AutoCloseable {
    public static final int MAX_RESIDENT_CUBES = RollingCubePlan.MAX_STAGED_OWNERS;
    public static final int MAX_COMMITTED_CUBES = RollingCubePlan.MAX_COMMITTED_OWNERS;
    public static final int MAX_RESULT_EVENTS = 4, MAX_EVENTS_PER_POLL = 2;
    public static final int MAX_PENDING_EDITS = 1, MAX_PENDING_COMMITS = 1, MAX_PENDING_GOALS = 1;
    public static final int MAX_GENERATION_WORKERS = 2, MAX_TICKETS = 3;
    public static final int MAX_VERSION_SLOTS = 2 * MAX_COMMITTED_CUBES;
    public static final int MAX_SNAPSHOT_REFERENCE_SLOTS = MAX_RESIDENT_CUBES + MAX_RESULT_EVENTS;
    public static final int MAX_ACTORS = NativeCubicHost.MAX_OWNED_HOSTS;
    private static final AtomicInteger ACTORS = new AtomicInteger();
    private static final long POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(2);

    public enum Admission { QUEUED, BUSY, CLOSING, NOT_READY, STALE_GENERATION }
    public enum EditStatus {
        APPLIED, WORLD_MISMATCH, STALE_EPOCH, UNKNOWN_CUBE, STALE_LEASE, STALE_REVISION,
        POSITION_MISMATCH, IMMUTABLE_BOUNDARY, INVALID_STATE, NOT_READY, SAVE_FAILED, CLOSING
    }
    public enum Lifecycle { OPENING, STAGING, OPEN, CLOSING, CLOSED }
    public sealed interface Event permits StageMetadata, Snapshot, StageReady, Committed, EditAck, Failure, Closed {}
    public record CubeLease(CubePos position, long leaseGeneration) {
        public CubeLease {
            Objects.requireNonNull(position);
            if (leaseGeneration <= 0) throw new IllegalArgumentException("Positive lease generation required");
        }
        public long generation() { return leaseGeneration; }
    }
    public record CubeVersion(CubePos position, long leaseGeneration, long revision) {
        public CubeVersion {
            Objects.requireNonNull(position);
            if (leaseGeneration <= 0 || revision < 0) throw new IllegalArgumentException("Invalid cube version");
        }
    }
    /**
     * currentLeases and candidateLeases include the permanent pin dependencies. newPositions is
     * candidate minus current, retiredPositions is current minus candidate. renderTargets is only
     * the moving 27-target view; the original editable target is pinCenter and has 27 dependencies.
     * Initial currentLeases is empty, currentCenter equals candidateCenter, and every candidate is new.
     */
    public record StageMetadata(WorldIdentity world, long epoch, long generation, boolean initial,
                                CubePos pinCenter, CubePos currentCenter, CubePos candidateCenter,
                                List<CubeLease> currentLeases, List<CubeLease> candidateLeases,
                                List<CubePos> newPositions, List<CubePos> retiredPositions,
                                List<CubePos> renderTargets) implements Event {
        public StageMetadata {
            Objects.requireNonNull(world); Objects.requireNonNull(pinCenter);
            Objects.requireNonNull(currentCenter); Objects.requireNonNull(candidateCenter);
            if (epoch < 0 || generation <= 0) throw new IllegalArgumentException("Invalid stage identity");
            currentLeases = boundedCopy(currentLeases, MAX_COMMITTED_CUBES);
            candidateLeases = boundedCopy(candidateLeases, MAX_COMMITTED_CUBES);
            newPositions = boundedCopy(newPositions, MAX_COMMITTED_CUBES);
            retiredPositions = boundedCopy(retiredPositions, MAX_COMMITTED_CUBES);
            renderTargets = boundedCopy(renderTargets, 27);
        }
    }
    public record Snapshot(long generation, EngineCubeSnapshot snapshot) implements Event {
        public Snapshot {
            if (generation <= 0) throw new IllegalArgumentException("Positive plan generation required");
            Objects.requireNonNull(snapshot);
        }
    }
    /** Exact revision manifest, candidate125 plus fixed27 dependencies (at most152 distinct). */
    public record StageReady(WorldIdentity world, long epoch, long generation, CubePos candidateCenter,
                             List<CubeVersion> versions) implements Event {
        public StageReady {
            Objects.requireNonNull(world); Objects.requireNonNull(candidateCenter);
            if (epoch < 0 || generation <= 0) throw new IllegalArgumentException("Invalid ready identity");
            versions = boundedCopy(versions, MAX_COMMITTED_CUBES);
        }
    }
    public record Committed(WorldIdentity world, long epoch, long generation, CubePos center) implements Event {}
    public record EditRequest(WorldIdentity world, long epoch, CubePos position, long leaseGeneration,
                              long expectedRevision, long x, long y, long z, int stateId) {
        public EditRequest { Objects.requireNonNull(world); Objects.requireNonNull(position); }
    }
    /** APPLIED follows both durable completion and the replacement Snapshot in FIFO output order. */
    public record EditAck(EditRequest request, EditStatus status, long revision, String detail) implements Event {
        public boolean applied() { return status == EditStatus.APPLIED; }
    }
    public record Failure(String detail) implements Event {}
    public record Closed(boolean successful, String detail) implements Event {}
    public record Diagnostics(Lifecycle lifecycle, int queuedEvents, int queuedSnapshots,
                              int queuedEdits, int queuedCommits, int queuedGoals, boolean pendingEdit,
                              long committedGeneration, long stagedGeneration, CubePos currentCenter,
                              CubePos candidateCenter, int residentCubes, int readyCubes, int tickets,
                              int encodedSnapshotCaches, int retainedVersionSlots,
                              int physicalProviders, int queuedGeneration, long cancelledGeneration,
                              int stagedSnapshotsPublished, long publishedSnapshots, long admittedEdits) {
        public int retainedSnapshotReferenceSlots() { return encodedSnapshotCaches + queuedSnapshots; }
        public int queuedCommands() { return queuedEdits + queuedCommits + queuedGoals; }
    }

    private final Path directory;
    private final WorldIdentity world;
    private final CubeProvider provider;
    private final EnginePaletteContext palette;
    private final long epoch;
    private final CubePos pinCenter;
    private final ArrayBlockingQueue<EditRequest> edits = new ArrayBlockingQueue<>(MAX_PENDING_EDITS);
    private final ArrayBlockingQueue<Long> commits = new ArrayBlockingQueue<>(MAX_PENDING_COMMITS);
    private final ArrayBlockingQueue<Event> results = new ArrayBlockingQueue<>(MAX_RESULT_EVENTS);
    private final ClosingFuture completion = new ClosingFuture();
    private final Object admissionLock = new Object();
    private final Thread actor;
    private final ThreadPoolExecutor bootstrap;
    private volatile boolean stopRequested, pendingEdit;
    private volatile Lifecycle lifecycle = Lifecycle.OPENING;
    // Only mutated while holding admissionLock. The owner is the only consumer of these slots.
    private CubePos latestGoal;
    private long activeStageGeneration, deliveredReadyGeneration;
    private boolean pendingCommit;
    private volatile Counters counters;

    // Owner-thread-only: no host, native section, mutable resident view or save future escapes.
    private NativeCubicHost host;
    private CompletableFuture<NativeCubicHost> opening;
    private CubePos currentCenter;
    private String currentTicket;
    private long committedGeneration, nextGeneration = 1, nextLease = 1;
    private Map<CubePos, CubeVersion> currentVersions = Map.of();
    private final Set<CubePos> encodedSnapshots = new HashSet<>();
    private Stage stage;
    private EditRequest editing;
    private CompletableFuture<Long> saving;
    private EditAck acknowledgement;
    private boolean replacementPublished, closeOutputPrepared, hostClosing, failurePublished;
    private long publishedSnapshots, admittedEdits;
    private Throwable failure;

    private RollingCubeActor(Path directory, WorldIdentity world, CubeProvider provider,
                             EnginePaletteContext palette, long epoch, CubePos center) {
        this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
        this.world = Objects.requireNonNull(world); this.provider = Objects.requireNonNull(provider);
        this.palette = Objects.requireNonNull(palette); this.pinCenter = Objects.requireNonNull(center);
        if (epoch < 0) throw new IllegalArgumentException("Nonnegative actor epoch required");
        RollingCubePlan.validateWindowCenter(center);
        this.epoch = epoch; this.currentCenter = center;
        counters = new Counters(0, 0, center, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        bootstrap = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), task -> {
            Thread worker = new Thread(task, "earth-rolling-actor-bootstrap"); worker.setDaemon(true); return worker;
        }, new ThreadPoolExecutor.AbortPolicy());
        actor = new Thread(this::run, "earth-rolling-cube-actor"); actor.setDaemon(true);
    }

    /** Registries must be frozen. Does not access files, wait for providers or block the caller. */
    public static RollingCubeActor open(Path directory, WorldIdentity world, CubeProvider provider,
                                        EnginePaletteContext palette, long epoch, CubePos initialCenter) {
        for (;;) {
            int count = ACTORS.get();
            if (count >= MAX_ACTORS) throw new RejectedExecutionException("Rolling actor limit reached");
            if (ACTORS.compareAndSet(count, count + 1)) break;
        }
        RollingCubeActor result = null;
        try {
            result = new RollingCubeActor(directory, world, provider, palette, epoch, initialCenter);
            result.actor.start(); return result;
        } catch (Throwable failed) {
            if (result != null) result.bootstrap.shutdown();
            ACTORS.decrementAndGet(); throw failed;
        }
    }

    public List<Event> pollEvents(int maximum) {
        if (maximum < 0) throw new IllegalArgumentException("Nonnegative delivery maximum required");
        List<Event> batch = new ArrayList<>(Math.min(maximum, MAX_EVENTS_PER_POLL));
        for (int count = 0; count < Math.min(maximum, MAX_EVENTS_PER_POLL); count++) {
            Event event = results.poll();
            if (event == null) break;
            batch.add(event);
            synchronized (admissionLock) {
                if (event instanceof EditAck) pendingEdit = false;
                if (event instanceof StageReady ready && ready.generation() == activeStageGeneration)
                    deliveredReadyGeneration = ready.generation();
            }
        }
        LockSupport.unpark(actor); return List.copyOf(batch);
    }

    /** One latest goal, including while opening or staging; every eventual step is adjacent. */
    public Admission requestCenter(CubePos goal) {
        Objects.requireNonNull(goal); RollingCubePlan.validateWindowCenter(goal);
        synchronized (admissionLock) {
            if (stopRequested) return Admission.CLOSING;
            latestGoal = goal;
        }
        LockSupport.unpark(actor); return Admission.QUEUED;
    }

    /**
     * A trusted controller calls this only after verifying the exact paired native/DH client ACK.
     * Generation is actor-local and monotonic; the caller also fences world, epoch, leases and
     * revisions with StageReady. Delivery of metadata or snapshots alone never authorizes commit.
     */
    public Admission commitPrepared(long generation) {
        synchronized (admissionLock) {
            if (stopRequested) return Admission.CLOSING;
            if (generation <= 0 || generation != activeStageGeneration) return Admission.STALE_GENERATION;
            if (deliveredReadyGeneration != generation) return Admission.NOT_READY;
            if (pendingCommit) return Admission.BUSY;
            if (!commits.offer(generation)) return Admission.BUSY;
            pendingCommit = true;
        }
        LockSupport.unpark(actor); return Admission.QUEUED;
    }

    public Admission tryEdit(EditRequest request) {
        Objects.requireNonNull(request);
        synchronized (admissionLock) {
            if (stopRequested) return Admission.CLOSING;
            if (lifecycle != Lifecycle.OPEN || activeStageGeneration != 0) return Admission.NOT_READY;
            if (pendingEdit) return Admission.BUSY;
            pendingEdit = true;
            if (!edits.offer(request)) { pendingEdit = false; return Admission.BUSY; }
        }
        LockSupport.unpark(actor); return Admission.QUEUED;
    }

    /** Reads immutable owner-published counters and bounded queue state, never the host. */
    public Diagnostics diagnostics() {
        Counters c = counters;
        int snapshots = 0;
        for (Event event : results) if (event instanceof Snapshot) snapshots++;
        synchronized (admissionLock) {
            return new Diagnostics(lifecycle, results.size(), snapshots, edits.size(), commits.size(),
                    latestGoal == null ? 0 : 1, pendingEdit, c.committed, c.staged, c.current, c.candidate,
                    c.residents, c.ready, c.tickets, c.encoded, c.versions, c.running, c.queued,
                    c.cancelled, c.stageSnapshots, c.published, c.edits);
        }
    }
    public CompletableFuture<Closed> closed() { return completion; }
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
                        new NativeCubicHost.Limits(MAX_RESIDENT_CUBES, MAX_TICKETS,
                                MAX_GENERATION_WORKERS, MAX_PENDING_EDITS), bootstrap);
            } catch (Throwable problem) { fail(problem); }
            while (!completion.isDone()) {
                try { step(); }
                catch (Throwable problem) { fail(problem); }
                publishCounters();
                if (!completion.isDone()) {
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
            if (!stopRequested) {
                host.updateTicket("rolling-pin", pinCenter, 1, 1);
                beginStage(currentCenter, true);
            }
        }
        if (stopRequested) { drainClose(); return; }
        if (host == null) throw new IllegalStateException("Actor has no opened host");
        if (stage != null) { advanceStage(); return; }
        if (editing == null && acknowledgement == null && results.remainingCapacity() > 0) {
            EditRequest request = edits.poll();
            if (request != null) startEdit(request);
        }
        advanceEdit();
        synchronized (admissionLock) {
            if (pendingEdit || stopRequested || results.remainingCapacity() == 0) return;
            if (latestGoal != null && latestGoal.equals(currentCenter)) latestGoal = null;
            if (latestGoal != null) beginStage(RollingCubePlan.nextStep(currentCenter, latestGoal), false);
        }
    }

    private void beginStage(CubePos candidate, boolean initial) {
        if (stage != null) throw new IllegalStateException("Only one physical staged transition is allowed");
        RollingCubePlan plan = initial ? RollingCubePlan.stationary(pinCenter, candidate)
                : RollingCubePlan.create(pinCenter, currentCenter, candidate);
        List<CubePos> nextOwners = initial ? plan.committedOwners() : plan.nextCommittedOwners();
        long generation = nextGeneration; nextGeneration = Math.incrementExact(nextGeneration);
        String ticket = "rolling-a".equals(currentTicket) ? "rolling-b" : "rolling-a";
        List<CubeLease> nextLeases = new ArrayList<>(nextOwners.size());
        Map<CubePos, CubeVersion> nextVersions = new HashMap<>();
        List<CubePos> added = new ArrayList<>();
        // The same canonical ascending addition order as CubeStreamAdmission.replaceInterest.
        for (CubePos position : nextOwners) {
            CubeVersion existing = currentVersions.get(position);
            long lease;
            if (existing == null) {
                lease = nextLease; nextLease = Math.incrementExact(nextLease); added.add(position);
            } else { lease = existing.leaseGeneration(); nextVersions.put(position, existing); }
            nextLeases.add(new CubeLease(position, lease));
        }
        List<CubePos> retired = currentVersions.keySet().stream().filter(position -> !nextOwners.contains(position)).sorted().toList();
        StageMetadata metadata = new StageMetadata(world, epoch, generation, initial, pinCenter,
                currentCenter, candidate, leases(currentVersions), nextLeases, added, retired,
                initial ? plan.currentTargets() : plan.candidateTargets());
        synchronized (admissionLock) {
            if (stopRequested) return;
            activeStageGeneration = generation; deliveredReadyGeneration = 0;
            lifecycle = Lifecycle.STAGING;
        }
        host.updateTicket(ticket, candidate, 2, 2);
        stage = new Stage(metadata, ticket, nextVersions);
        if (host.stats().residentCubes() > MAX_RESIDENT_CUBES)
            throw new IllegalStateException("Staged ticket-coordinate bound exceeded");
    }

    private void advanceStage() {
        Stage s = stage;
        if (!s.metadataPublished) {
            if (!results.offer(s.metadata)) return;
            s.metadataPublished = true;
        }
        for (int count = 0; count < MAX_EVENTS_PER_POLL && s.nextSnapshot < s.metadata.newPositions().size()
                && results.remainingCapacity() > 0; count++) {
            CubePos position = s.metadata.newPositions().get(s.nextSnapshot);
            long lease = s.leaseByPosition.get(position);
            var result = host.pollSnapshot(position, epoch, lease);
            if (result.status() == NativeCubicHost.Status.LOADING) return;
            if (result.status() != NativeCubicHost.Status.READY)
                throw new IllegalStateException("Native cube unavailable: " + position, result.failure());
            EngineCubeSnapshot snapshot = result.snapshot();
            s.versions.put(position, new CubeVersion(position, lease, snapshot.revision()));
            publishSnapshot(s.metadata.generation(), snapshot); s.nextSnapshot++;
        }
        if (s.nextSnapshot != s.metadata.newPositions().size()) return;
        if (!s.readyPublished) {
            if (results.remainingCapacity() == 0) return;
            List<CubeVersion> manifest = s.metadata.candidateLeases().stream()
                    .map(lease -> Objects.requireNonNull(s.versions.get(lease.position()))).toList();
            if (!results.offer(new StageReady(world, epoch, s.metadata.generation(), s.metadata.candidateCenter(), manifest)))
                throw new IllegalStateException("StageReady output reservation lost");
            s.readyPublished = true;
        }
        if (results.remainingCapacity() == 0) return;
        Long commit = commits.poll();
        if (commit == null) return;
        if (commit != s.metadata.generation() || !s.readyPublished)
            throw new IllegalStateException("Unexpected stale commit command");
        // Never remove the last old ticket before the verified external paired ACK reaches here.
        if (currentTicket != null) host.removeTicket(currentTicket);
        currentTicket = s.ticket; currentCenter = s.metadata.candidateCenter();
        currentVersions = Map.copyOf(s.versions); committedGeneration = s.metadata.generation();
        encodedSnapshots.retainAll(currentVersions.keySet());
        if (host.stats().residentCubes() > MAX_COMMITTED_CUBES)
            throw new IllegalStateException("Committed ticket-coordinate bound exceeded");
        stage = null;
        synchronized (admissionLock) {
            activeStageGeneration = 0; deliveredReadyGeneration = 0; pendingCommit = false;
            if (!stopRequested) lifecycle = Lifecycle.OPEN;
            // Publish only after admissions observe the committed state. A consumer may act on
            // this event immediately, even before the actor gets its next scheduling quantum.
            if (!results.offer(new Committed(world, epoch, committedGeneration, currentCenter)))
                throw new IllegalStateException("Committed output reservation lost");
        }
    }

    private void startEdit(EditRequest request) {
        editing = request;
        if (!world.equals(request.world())) { reject(EditStatus.WORLD_MISMATCH, -1, "World identity mismatch"); return; }
        if (epoch != request.epoch()) { reject(EditStatus.STALE_EPOCH, -1, "Stale epoch"); return; }
        if (!pinCenter.equals(request.position())) { reject(EditStatus.UNKNOWN_CUBE, -1, "Only the original editable cube is writable"); return; }
        CubeVersion version = currentVersions.get(request.position());
        if (version == null) { reject(EditStatus.NOT_READY, -1, "Original editable cube is not committed"); return; }
        if (version.leaseGeneration() != request.leaseGeneration()) { reject(EditStatus.STALE_LEASE, -1, "Stale lease"); return; }
        try {
            if (!request.position().equals(CubePos.fromBlock(request.x(), request.y(), request.z()))) {
                reject(EditStatus.POSITION_MISMATCH, -1, "Block coordinate is outside the requested cube"); return;
            }
        } catch (ArithmeticException invalid) {
            reject(EditStatus.POSITION_MISMATCH, -1, "Block coordinate exceeds the cube address domain"); return;
        }
        if (!RollingCubePlan.isEditableInterior(pinCenter, request.x(), request.y(), request.z())) {
            reject(EditStatus.IMMUTABLE_BOUNDARY, -1, "The original cube's one-block boundary shell is immutable"); return;
        }
        try { NativeCubeCodec.registeredState(request.stateId()); }
        catch (IllegalArgumentException invalid) { reject(EditStatus.INVALID_STATE, -1, "Unknown native state ID"); return; }
        var current = host.pollSnapshot(request.position(), epoch, request.leaseGeneration());
        if (current.status() != NativeCubicHost.Status.READY) {
            reject(EditStatus.NOT_READY, -1, "Authoritative cube is not ready"); return;
        }
        long revision = current.snapshot().revision();
        if (revision != request.expectedRevision()) { reject(EditStatus.STALE_REVISION, revision, "Expected revision differs from durable revision"); return; }
        synchronized (admissionLock) {
            if (stopRequested) { reject(EditStatus.CLOSING, revision, "Actor is closing"); return; }
            try { saving = host.setBlockId(request.x(), request.y(), request.z(), request.stateId()); admittedEdits++; }
            catch (RuntimeException problem) { reject(EditStatus.SAVE_FAILED, revision, detail(problem)); }
        }
    }

    private void advanceEdit() {
        if (saving != null) {
            if (!saving.isDone()) return;
            CompletableFuture<Long> settled = saving; saving = null;
            try { acknowledgement = new EditAck(editing, EditStatus.APPLIED, settled.join(), "Durable save completed"); }
            catch (CompletionException problem) { reject(EditStatus.SAVE_FAILED, -1, detail(problem)); }
        }
        if (acknowledgement == null) return;
        if (acknowledgement.applied() && !replacementPublished) {
            if (results.remainingCapacity() == 0) return;
            var changed = host.pollSnapshot(editing.position(), epoch, editing.leaseGeneration());
            if (changed.status() != NativeCubicHost.Status.READY || changed.snapshot().revision() != acknowledgement.revision())
                throw new IllegalStateException("Durable edit did not produce its exact authoritative snapshot");
            publishSnapshot(committedGeneration, changed.snapshot()); replacementPublished = true;
            Map<CubePos, CubeVersion> replaced = new HashMap<>(currentVersions);
            replaced.put(editing.position(), new CubeVersion(editing.position(), editing.leaseGeneration(), acknowledgement.revision()));
            currentVersions = Map.copyOf(replaced);
        }
        if (!results.offer(acknowledgement)) return;
        editing = null; acknowledgement = null; replacementPublished = false;
        // Admission remains occupied until the acknowledgement is consumed.
    }
    private void reject(EditStatus status, long revision, String message) {
        acknowledgement = new EditAck(editing, status, revision, message);
    }
    private void publishSnapshot(long generation, EngineCubeSnapshot snapshot) {
        if (!results.offer(new Snapshot(generation, snapshot))) throw new IllegalStateException("Actor result bound violated");
        encodedSnapshots.add(snapshot.position()); publishedSnapshots++;
    }
    private void fail(Throwable problem) {
        if (failure == null) failure = unwrap(problem);
        synchronized (admissionLock) { stopRequested = true; lifecycle = Lifecycle.CLOSING; }
    }

    private void drainClose() {
        lifecycle = Lifecycle.CLOSING;
        if (!closeOutputPrepared) {
            // An abandoned consumer must never hold the writer lock or a physical provider slot.
            results.clear(); closeOutputPrepared = true;
            synchronized (admissionLock) { latestGoal = null; commits.clear(); pendingCommit = false; deliveredReadyGeneration = 0; }
            EditRequest queued = edits.poll();
            if (queued != null) results.offer(new EditAck(queued, EditStatus.CLOSING, -1, "Actor closed before edit admission"));
        }
        if (failure != null && !failurePublished) {
            if (results.remainingCapacity() == 0) results.poll();
            results.offer(new Failure(detail(failure))); failurePublished = true;
        }
        if (host == null) { finish(); return; }
        if (!hostClosing) {
            if (failure == null) {
                advanceEdit();
                if (saving != null || acknowledgement != null) return;
            }
            host.beginClose(); hostClosing = true;
            stage = null; currentVersions = Map.of(); encodedSnapshots.clear();
            synchronized (admissionLock) { activeStageGeneration = 0; }
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
        Closed closed = new Closed(failure == null, failure == null ? "Rolling native host drained and closed" : detail(failure));
        results.offer(closed); lifecycle = Lifecycle.CLOSED; completion.publish(closed);
    }
    private void publishCounters() {
        var stats = host == null ? null : host.stats();
        counters = new Counters(committedGeneration, stage == null ? 0 : stage.metadata.generation(),
                currentCenter, stage == null ? null : stage.metadata.candidateCenter(),
                stats == null ? 0 : stats.residentCubes(), stats == null ? 0 : stats.readyCubes(),
                stats == null ? 0 : stats.tickets(), encodedSnapshots.size(),
                currentVersions.size() + (stage == null ? 0 : stage.versions.size()),
                stats == null ? 0 : stats.loader().running(), stats == null ? 0 : stats.loader().queued(),
                stats == null ? 0 : stats.loader().cancelled(), stage == null ? 0 : stage.nextSnapshot,
                publishedSnapshots, admittedEdits);
    }
    private record Counters(long committed, long staged, CubePos current, CubePos candidate,
                            int residents, int ready, int tickets, int encoded, int versions,
                            int running, int queued, long cancelled, int stageSnapshots, long published, long edits) {}
    private static final class Stage {
        private final StageMetadata metadata;
        private final String ticket;
        private final Map<CubePos, CubeVersion> versions;
        private final Map<CubePos, Long> leaseByPosition;
        private int nextSnapshot;
        private boolean metadataPublished, readyPublished;
        private Stage(StageMetadata metadata, String ticket, Map<CubePos, CubeVersion> versions) {
            this.metadata = metadata; this.ticket = ticket; this.versions = versions;
            Map<CubePos, Long> leases = new HashMap<>();
            metadata.candidateLeases().forEach(lease -> leases.put(lease.position(), lease.leaseGeneration()));
            leaseByPosition = Map.copyOf(leases);
        }
    }
    private static List<CubeLease> leases(Map<CubePos, CubeVersion> versions) {
        return versions.values().stream().sorted(java.util.Comparator.comparing(CubeVersion::position))
                .map(version -> new CubeLease(version.position(), version.leaseGeneration())).toList();
    }
    private static <T> List<T> boundedCopy(List<T> values, int maximum) {
        Objects.requireNonNull(values);
        if (values.size() > maximum) throw new IllegalArgumentException("Actor protocol list exceeds " + maximum);
        return List.copyOf(values);
    }
    private static Throwable unwrap(Throwable problem) {
        return problem instanceof CompletionException && problem.getCause() != null ? problem.getCause() : problem;
    }
    private static String detail(Throwable problem) {
        Throwable cause = unwrap(problem); String message = cause.getMessage();
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
            return new UnsupportedOperationException("Actor owns completion; cancel or time out an observation copy instead");
        }
    }
}
