package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.CubeProvider;
import io.earthplatform.core.CubeTickets;
import io.earthplatform.core.CubicWorld;
import io.earthplatform.core.FileEditStore;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
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
import java.util.function.Supplier;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Owner-thread bridge to the existing bounded cubic core, with real durable overlays.
 * This is a host primitive, not a Level, network handler, renderer, or player-interaction hook.
 * No method after open performs filesystem work or waits for worker completion on its caller.
 * Native sections are temporary owner-thread objects; only immutable snapshots escape.
 */
public final class NativeCubicHost {
    /* FileEditStore releases its lock before an asynchronous provider necessarily settles. Keep
       the canonical directory reserved until the owner has observed ALL close conditions. */
    private static final Map<Path, DirectoryOwnership> OWNED_DIRECTORIES = new HashMap<>();
    /** Includes opening, active, draining, and uncertain failed-close ownership in this process. */
    public static final int MAX_OWNED_HOSTS = 4;
    public static final int MAX_RESIDENT_CUBES = 256;
    public static final int MAX_TICKETS = 16;
    public static final int MAX_GENERATION_WORKERS = 2;
    public static final int MAX_PENDING_EDITS = 64;

    public enum Status { UNKNOWN, LOADING, READY, FAILED }
    public enum Lifecycle { OPEN, CLOSING, CLOSED, CLOSE_FAILED }

    public record Limits(int residentCubes, int tickets, int generationWorkers, int pendingEdits) {
        public Limits {
            if (residentCubes < 1 || residentCubes > MAX_RESIDENT_CUBES || tickets < 1 || tickets > MAX_TICKETS
                    || generationWorkers < 1 || generationWorkers > MAX_GENERATION_WORKERS
                    || generationWorkers > residentCubes || pendingEdits < 1 || pendingEdits > MAX_PENDING_EDITS)
                throw new IllegalArgumentException("Positive, bounded native host limits required");
            Math.addExact(residentCubes, pendingEdits);
            Math.addExact(residentCubes, generationWorkers);
        }
        public static Limits defaults() { return new Limits(256, 8, 2, 64); }
    }

    /** UNKNOWN and LOADING have neither payload nor failure. Unknown is never an air cube. */
    public record SnapshotResult(CubePos position, Status status, EngineCubeSnapshot snapshot, Throwable failure) {
        public SnapshotResult {
            Objects.requireNonNull(position); Objects.requireNonNull(status);
            if ((status == Status.READY) != (snapshot != null)
                    || (status == Status.FAILED) != (failure != null))
                throw new IllegalArgumentException("Status, snapshot, and failure disagree");
            if (snapshot != null && !position.equals(snapshot.position()))
                throw new IllegalArgumentException("Snapshot position mismatch");
        }
    }

    public record CloseStatus(Lifecycle lifecycle, boolean storeSettled, boolean generationTerminated,
                              int runningProviders, Throwable failure) {
        public boolean complete() { return lifecycle == Lifecycle.CLOSED || lifecycle == Lifecycle.CLOSE_FAILED; }
    }

    private final Thread owner;
    private final Path directory;
    private final DirectoryOwnership directoryOwnership;
    private final Limits limits;
    private final NativeCubeCodec codec;
    private final CubicWorld world;
    private final ThreadPoolExecutor generation;
    private final BlockState[] pinnedStates;
    private final String replacementStoreIdentity;
    private final ReplacementRevisionLedger replacementLedger;
    private final ArenaRevisionLedger arenaLedger;
    private final Map<CubePos, Resident> residents = new HashMap<>();
    private final CompletableFuture<Void> closed = new CompletableFuture<>();
    private CompletableFuture<Void> storeClose;
    private Lifecycle lifecycle = Lifecycle.OPEN;
    private Throwable closeFailure;

    private NativeCubicHost(Thread owner, Path directory, DirectoryOwnership directoryOwnership, Limits limits,
                            NativeCubeCodec codec, CubicWorld world, ThreadPoolExecutor generation,
                            BlockState[] pinnedStates, String replacementStoreIdentity, ReplacementRevisionLedger replacementLedger, ArenaRevisionLedger arenaLedger) {
        this.owner = owner; this.directory = directory; this.directoryOwnership = directoryOwnership;
        this.limits = limits; this.codec = codec; this.world = world; this.generation = generation;
        this.pinnedStates = pinnedStates; this.replacementStoreIdentity = replacementStoreIdentity;
        this.replacementLedger = replacementLedger; this.arenaLedger = arenaLedger;
    }

    /**
     * Call on the eventual owner thread after native registries have frozen. File creation, manifest
     * checks, exclusive locking, and fsync happen only on the supplied caller-owned bounded bootstrap
     * pool. Rejection fails the returned future. An inline/rejection fallback is forbidden.
     *
     * Opening is deliberately non-cancellable and externally non-completable: retain its result and close a successfully opened
     * host. Calling join/get here would still block the caller; runtime code must poll isDone first.
     * The bootstrap pool is never shut down by this host.
     */
    public static CompletableFuture<NativeCubicHost> openAsync(Path directory, WorldIdentity identity,
            CubeProvider provider, EnginePaletteContext palette, Limits limits, ThreadPoolExecutor bootstrap) {
        return openAsync(directory, identity, provider, palette, limits, bootstrap, null);
    }

    /** Optional bounded original-region index is initialized on the same locked bootstrap worker. */
    public static CompletableFuture<NativeCubicHost> openAsync(Path directory, WorldIdentity identity,
            CubeProvider provider, EnginePaletteContext palette, Limits limits, ThreadPoolExecutor bootstrap,
            CubePos replacementOriginal) {
        return openAsync(directory, identity, provider, palette, limits, bootstrap, replacementOriginal, null);
    }

    static CompletableFuture<NativeCubicHost> openAsync(Path directory, WorldIdentity identity,
            CubeProvider provider, EnginePaletteContext palette, Limits limits, ThreadPoolExecutor bootstrap,
            CubePos replacementOriginal, ReplacementWorkspaces replacementWorkspaces) {
        return openAsync(directory, identity, provider, palette, limits, bootstrap, replacementOriginal, replacementWorkspaces, null);
    }

    static CompletableFuture<NativeCubicHost> openAsync(Path directory, WorldIdentity identity,
            CubeProvider provider, EnginePaletteContext palette, Limits limits, ThreadPoolExecutor bootstrap,
            CubePos replacementOriginal, ReplacementWorkspaces replacementWorkspaces, String indexedScope) {
        return openAsync(directory, identity, provider, palette, limits, bootstrap, replacementOriginal, replacementWorkspaces, indexedScope, null, null, null);
    }

    static CompletableFuture<NativeCubicHost> openAsync(Path directory, WorldIdentity identity,
            CubeProvider provider, EnginePaletteContext palette, Limits limits, ThreadPoolExecutor bootstrap,
            CubePos replacementOriginal, ReplacementWorkspaces replacementWorkspaces, String indexedScope,
            ArenaDefinition arena, String arenaScope, String arenaLayout) {
        if (arena != null && (replacementOriginal != null || replacementWorkspaces != null || indexedScope != null || arenaScope == null || arenaLayout == null))
            throw new IllegalArgumentException("Arena requires its separate versioned store capability");
        if (indexedScope != null && (replacementWorkspaces == null || !replacementWorkspaces.isAdjacent() || replacementOriginal != null))
            throw new IllegalArgumentException("Indexed store requires explicit adjacent replacement mode");
        if (replacementOriginal != null && replacementWorkspaces != null)
            throw new IllegalArgumentException("Choose legacy original-region or explicit two-workspace store");
        Objects.requireNonNull(directory); Objects.requireNonNull(provider); Objects.requireNonNull(limits);
        Objects.requireNonNull(bootstrap);
        if (bootstrap.getMaximumPoolSize() == Integer.MAX_VALUE
                || (long) bootstrap.getQueue().size() + bootstrap.getQueue().remainingCapacity() >= Integer.MAX_VALUE
                || !(bootstrap.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.AbortPolicy))
            throw new IllegalArgumentException("Bootstrap must have bounded workers/queue and AbortPolicy");
        Thread owner = Thread.currentThread();
        // Registry work is CPU-only and remains on the owner. No Minecraft mutable section is retained.
        NativeCubeCodec codec = new NativeCubeCodec(identity, palette);
        BlockState[] states = new BlockState[Block.BLOCK_STATE_REGISTRY.size()];
        for (int id = 0; id < states.length; id++) states[id] = NativeCubeCodec.registeredState(id);
        Path requested = directory.toAbsolutePath().normalize();
        OpeningFuture result = new OpeningFuture();
        try {
            bootstrap.execute(() -> {
                Path canonical = null;
                DirectoryOwnership ownership = new DirectoryOwnership();
                FileEditStore store = null;
                ThreadPoolExecutor generation = null;
                try {
                    if (Thread.currentThread() == owner)
                        throw new IllegalStateException("Bootstrap executor must not perform disk setup on the owner thread");
                    Files.createDirectories(requested);
                    canonical = requested.toRealPath();
                    synchronized (OWNED_DIRECTORIES) {
                        if (OWNED_DIRECTORIES.containsKey(canonical))
                            throw new IllegalStateException("Native host already open or closing for " + canonical);
                        if (OWNED_DIRECTORIES.size() >= MAX_OWNED_HOSTS)
                            throw new RejectedExecutionException("Native host ownership limit " + MAX_OWNED_HOSTS
                                    + " reached; active, closing, and failed-close hosts retain their slots");
                        OWNED_DIRECTORIES.put(canonical, ownership);
                    }
                    if (arena == null) try (var files = Files.newDirectoryStream(canonical)) {
                        for (Path file : files) if (file.getFileName().toString().startsWith("arena-paged-v1")
                                || file.getFileName().toString().startsWith(".arena-paged-v1"))
                            throw new java.io.IOException("Arena stores require the explicit arena capability; no legacy adoption");
                    }
                    store = new FileEditStore(canonical, identity);
                    ownership.store = store;
                    ReplacementRevisionLedger ledger = replacementWorkspaces == null ? null
                            : indexedScope == null ? ReplacementRevisionLedger.open(canonical, replacementWorkspaces)
                            : ReplacementRevisionLedger.openIndexed(canonical, replacementWorkspaces, indexedScope);
                    ArenaRevisionLedger arenaLedger = arena == null ? null : ArenaRevisionLedger.open(canonical, arena, arenaScope, arenaLayout);
                    String replacementStore = arenaLedger != null ? arenaLedger.storeIdentity() : ledger != null ? ledger.identity().storeIdentity()
                            : replacementOriginal == null ? null : ReplacementStoreIdentity.open(canonical, replacementOriginal);
                    generation = new ThreadPoolExecutor(limits.generationWorkers(), limits.generationWorkers(),
                            0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(limits.residentCubes()), task -> {
                                Thread worker = new Thread(task, "earth-native-cube-generation");
                                worker.setDaemon(true); return worker;
                            }, new ThreadPoolExecutor.AbortPolicy());
                    CubicWorld world = new CubicWorld(identity, provider, store, generation,
                            limits.residentCubes(), limits.tickets(), limits.generationWorkers(), limits.pendingEdits());
                    NativeCubicHost host = new NativeCubicHost(owner, canonical, ownership, limits, codec,
                            world, generation, states, replacementStore, ledger, arenaLedger);
                    ownership.host = host;
                    result.publish(host);
                } catch (Throwable failure) {
                    if (generation != null) generation.shutdown();
                    boolean cleanupSucceeded = true;
                    if (store != null) {
                        try { store.close(); }
                        catch (Throwable close) {
                            failure.addSuppressed(close); ownership.cleanupFailure = close; cleanupSucceeded = false;
                        }
                    }
                    if (canonical != null && cleanupSucceeded) releaseDirectory(canonical, ownership);
                    result.fail(failure);
                }
            });
        } catch (RuntimeException rejection) { result.fail(rejection); }
        return result;
    }

    public WorldIdentity identity() { checkOwner(); return codec.world(); }
    public Path directory() { checkOwner(); return directory; }
    public String replacementStoreIdentity() { checkOwner(); return replacementStoreIdentity; }
    ReplacementRevisionLedger replacementLedger() { checkOwner(); return replacementLedger; }
    ArenaRevisionLedger arenaLedger() { checkOwner(); return arenaLedger; }
    int encodedSnapshotCacheCount() { checkOwner(); int count=0;for(var resident:residents.values())if(resident.snapshot!=null)count++;return count; }
    public Limits limits() { checkOwner(); return limits; }
    public Lifecycle lifecycle() { checkOwner(); return lifecycle; }
    public CubicWorld.Stats stats() { checkOwner(); return world.stats(); }
    public Set<CubePos> residentPositions() { ensureOpen(); return Set.copyOf(residents.keySet()); }

    /** Atomic bounded box interest, retaining the core's overlap/ticket/cancellation semantics. */
    public CubeTickets.Delta updateTicket(String id, CubePos center, int horizontal, int vertical) {
        ensureOpen();
        // Validate before changing core residency: the native snapshot protocol uses section keys.
        // Reuse core's checked bounded box rather than ever enumerating an unbounded height column.
        for (CubePos position : CubeTickets.box(center, horizontal, vertical, limits.residentCubes()))
            checkNativePosition(position);
        CubeTickets.Delta delta = world.updateTicket(id, center, horizontal, vertical);
        applyDelta(delta);
        return delta;
    }

    public CubeTickets.Delta removeTicket(String id) {
        ensureOpen();
        CubeTickets.Delta delta = world.removeTicket(id);
        applyDelta(delta);
        return delta;
    }

    private void applyDelta(CubeTickets.Delta delta) {
        delta.removed().forEach(residents::remove);
        for (CubePos position : delta.added()) residents.put(position, new Resident(world.ready(position)));
    }

    /**
     * Memory-only polling. Epoch/lease are supplied by the session admitting this snapshot.
     * At most one encoded native snapshot is cached per resident cube; revision changes invalidate it.
     * Failed generation, overlay reads, and native state conversion remain explicit failures.
     */
    public SnapshotResult pollSnapshot(CubePos position, long epoch, long leaseGeneration) {
        ensureOpen(); Objects.requireNonNull(position);
        if (epoch < 0 || leaseGeneration <= 0) throw new IllegalArgumentException("Invalid snapshot epoch/lease");
        Resident resident = residents.get(position);
        if (resident == null) return new SnapshotResult(position, Status.UNKNOWN, null, null);
        if (!resident.ready.isDone()) return new SnapshotResult(position, Status.LOADING, null, null);
        if (resident.ready.isCompletedExceptionally())
            return new SnapshotResult(position, Status.FAILED, null, completedFailure(resident.ready));
        CubicWorld.CubeView view = world.view(position).orElseThrow();
        long revision = view.edits().revision();
        if (resident.conversionFailure != null && resident.failedRevision == revision)
            return new SnapshotResult(position, Status.FAILED, null, resident.conversionFailure);
        if (resident.snapshot == null || resident.snapshot.epoch() != epoch
                || resident.snapshot.leaseGeneration() != leaseGeneration || resident.snapshot.revision() != revision) {
            try {
                resident.snapshot = codec.materialize(view, epoch, leaseGeneration);
                resident.conversionFailure = null;
            } catch (RuntimeException failure) {
                resident.snapshot = null; resident.conversionFailure = failure; resident.failedRevision = revision;
                return new SnapshotResult(position, Status.FAILED, null, failure);
            }
        }
        return new SnapshotResult(position, Status.READY, resident.snapshot, null);
    }

    /** Retry a failed core read/generation explicitly. A successful core value cannot be regenerated here. */
    public void retry(CubePos position) {
        ensureOpen();
        Resident resident = requireResident(position);
        if (!resident.ready.isCompletedExceptionally())
            throw new IllegalStateException("Core cube has not failed; conversion errors require corrected data/palette");
        residents.put(position, new Resident(world.retry(position)));
    }

    /** Success means FileEditStore's strict save/fsync policy completed; no optimistic revision is exposed. */
    public CompletableFuture<Long> setBlock(long x, long y, long z, BlockState state) {
        ensureOpen(); Objects.requireNonNull(state);
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        validateStateId(id);
        if (pinnedStates[id] != state) throw new IllegalArgumentException("Unregistered native block state");
        return setBlockId(x, y, z, id);
    }

    /** Numeric IDs are accepted only in this host's explicitly pinned native palette. */
    public CompletableFuture<Long> setBlockId(long x, long y, long z, int stateId) {
        ensureOpen(); validateStateId(stateId);
        requireResident(CubePos.fromBlock(x, y, z));
        return world.setBlock(x, y, z, stateId);
    }

    /** Owner-only scalar read for bounded common capture; no snapshot, view or callback escapes. */
    int blockStateId(long x, long y, long z) {
        ensureOpen(); CubePos position = CubePos.fromBlock(x, y, z); requireResident(position);
        var view = world.view(position).orElseThrow(() -> new IllegalStateException("Replacement dependency is not READY"));
        int id = view.get(CubePos.index((int) Math.floorMod(x, CubePos.SIZE), (int) Math.floorMod(y, CubePos.SIZE),
                (int) Math.floorMod(z, CubePos.SIZE)));
        validateStateId(id); return id;
    }

    private void validateStateId(int id) {
        if (Block.BLOCK_STATE_REGISTRY.size() != pinnedStates.length)
            throw new IllegalStateException("Native registry changed after host open");
        if (id < 0 || id >= pinnedStates.length || NativeCubeCodec.registeredState(id) != pinnedStates[id])
            throw new IllegalArgumentException("Unknown or changed native block state ID: " + id);
    }

    /**
     * Stops admission and signals cancellation without waiting or starting a polling worker.
     * Keep this host and pollClose on its owner until complete; this future only completes in pollClose.
     * Neither cancellation of a returned copy nor repeated beginClose abandons admitted edits/providers.
     */
    public CompletableFuture<Void> beginClose() {
        checkOwner();
        if (lifecycle == Lifecycle.OPEN) {
            lifecycle = Lifecycle.CLOSING;
            residents.clear();
            try { storeClose = world.closeAsync(); }
            catch (Throwable failure) { storeClose = CompletableFuture.failedFuture(failure); }
            // Do not shutdownNow: queued invoke tasks must run their cancellation checks and retire
            // loader reservations. An asynchronous provider may outlive all executor threads.
            generation.shutdown();
        }
        return closed.copy();
    }

    /** Nonblocking owner-thread drain barrier. An uncooperative source retains ownership indefinitely. */
    public CloseStatus pollClose() {
        checkOwner();
        if (lifecycle == Lifecycle.OPEN) throw new IllegalStateException("Call beginClose first");
        boolean storeSettled = storeClose.isDone();
        boolean generationTerminated = generation.isTerminated();
        int runningProviders = world.stats().loader().running();
        if (storeSettled && storeClose.isCompletedExceptionally() && closeFailure == null)
            closeFailure = completedFailure(storeClose);
        if (lifecycle == Lifecycle.CLOSING && storeSettled && generationTerminated && runningProviders == 0) {
            lifecycle = closeFailure == null ? Lifecycle.CLOSED : Lifecycle.CLOSE_FAILED;
            // A failed FileEditStore.close may leave a file resource alive. Retain the strong
            // ownership record on failure rather than allow an unsafe replacement host.
            if (closeFailure == null) {
                releaseDirectory(directory, directoryOwnership);
                closed.complete(null);
            } else {
                directoryOwnership.cleanupFailure = closeFailure;
                closed.completeExceptionally(closeFailure);
            }
        }
        return new CloseStatus(lifecycle, storeSettled, generationTerminated, runningProviders, closeFailure);
    }

    private Resident requireResident(CubePos position) {
        Resident resident = residents.get(position);
        if (resident == null) throw new IllegalArgumentException("Cube has no native residency ticket: " + position);
        return resident;
    }

    private void checkOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Native host accessed from non-owner thread");
    }
    private void ensureOpen() {
        checkOwner();
        if (lifecycle != Lifecycle.OPEN) throw new IllegalStateException("Native host is " + lifecycle);
    }
    private static void checkNativePosition(CubePos position) {
        long key = SectionPos.asLong(position.x(), position.y(), position.z());
        if (SectionPos.x(key) != position.x() || SectionPos.y(key) != position.y() || SectionPos.z(key) != position.z())
            throw new IllegalArgumentException("Cube exceeds lossless native section-key range: " + position);
    }
    private static Throwable completedFailure(CompletableFuture<?> future) {
        if (!future.isDone()) throw new IllegalStateException("Future is not complete");
        try { future.join(); throw new IllegalStateException("Future did not fail"); }
        catch (CompletionException failure) { return failure.getCause(); }
        catch (java.util.concurrent.CancellationException failure) { return failure; }
    }
    private static void releaseDirectory(Path directory, DirectoryOwnership ownership) {
        synchronized (OWNED_DIRECTORIES) { OWNED_DIRECTORIES.remove(directory, ownership); }
    }
    /** Strongly retain resource ownership, including uncertain failed cleanup, until successful close. */
    private static final class DirectoryOwnership {
        private FileEditStore store;
        private NativeCubicHost host;
        private Throwable cleanupFailure;
    }

    /** Completion is internal. Observe with dependent stages; timeouts on a copy cannot abandon ownership. */
    private static final class OpeningFuture extends CompletableFuture<NativeCubicHost> {
        private void publish(NativeCubicHost host) { super.complete(host); }
        private void fail(Throwable failure) { super.completeExceptionally(failure); }
        @Override public boolean cancel(boolean interrupt) { return false; }
        @Override public boolean complete(NativeCubicHost host) { return false; }
        @Override public boolean completeExceptionally(Throwable failure) { return false; }
        @Override public void obtrudeValue(NativeCubicHost host) { throw readOnly(); }
        @Override public void obtrudeException(Throwable failure) { throw readOnly(); }
        @Override public CompletableFuture<NativeCubicHost> completeAsync(Supplier<? extends NativeCubicHost> supplier) { throw readOnly(); }
        @Override public CompletableFuture<NativeCubicHost> completeAsync(Supplier<? extends NativeCubicHost> supplier, Executor executor) { throw readOnly(); }
        @Override public CompletableFuture<NativeCubicHost> orTimeout(long timeout, TimeUnit unit) { throw readOnly(); }
        @Override public CompletableFuture<NativeCubicHost> completeOnTimeout(NativeCubicHost host, long timeout, TimeUnit unit) { throw readOnly(); }
        private static UnsupportedOperationException readOnly() {
            return new UnsupportedOperationException("Opening completion is host-owned; observe or time out a dependent copy instead");
        }
    }

    private static final class Resident {
        private final CompletableFuture<CubicWorld.CubeView> ready;
        private EngineCubeSnapshot snapshot;
        private Throwable conversionFailure;
        private long failedRevision = -1;
        private Resident(CompletableFuture<CubicWorld.CubeView> ready) { this.ready = ready; }
    }
}
