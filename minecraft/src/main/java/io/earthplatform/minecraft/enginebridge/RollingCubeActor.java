package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.CubeProvider;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.lod.DhAnalyticFlatPolicy;
import io.earthplatform.minecraft.lod.DhAnalyticSetAdmission;
import net.minecraft.world.level.block.Blocks;
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
 *
 * <p>The explicit two-workspace gate reconstructs two canonical27 halos serially before opening
 * the initial125 window. It retains54 certificate-version values and two compact palette captures,
 * with two zero-copy one-cell prospective overlays, never a second native pin. Startup capture
 * copying and serial post-durability compaction both peak at three8,000-byte owned arrays.
 * Overlays are discarded on refusal/failure; committed captures remain immutable. Wire/client retention is separately owned and bounded.</p>
 *
 * <p>The separate arena capability reconstructs its64 dependencies one resident at a time into a
 * single11,664-byte sealed packed capture, before the first moving125 window. It retains one64-value
 * historical dependency vector, not a residency pin. Prospective edits use a metadata-only overlay;
 * compaction peaks at old+new23,328 capture bytes. Every edit needs the complete64 dependencies already
 * resident in the moving125 body and a pure exact source/layout-bound native+far census.</p>
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

    /** Immutable optional authority policy. Full-cube edits require this replacement capability. */
    public record ReplacementPolicy(String providerIdentity, String materialPolicy, String detailPolicy,
                                    long resourceGeneration, RollingEditPolicy editPolicy) {
        /** Existing authority callers retain their original interior-only admission. */
        public ReplacementPolicy(String providerIdentity, String materialPolicy, String detailPolicy,
                                 long resourceGeneration) {
            this(providerIdentity, materialPolicy, detailPolicy, resourceGeneration, RollingEditPolicy.INTERIOR_V1);
        }
        public ReplacementPolicy {
            ReplacementCertificate.bounded(providerIdentity); ReplacementCertificate.bounded(materialPolicy);
            ReplacementCertificate.bounded(detailPolicy);
            if (resourceGeneration <= 0) throw new IllegalArgumentException("Positive resource generation required");
            Objects.requireNonNull(editPolicy, "editPolicy");
        }
    }

    public enum Admission { QUEUED, BUSY, CLOSING, NOT_READY, STALE_GENERATION }
    public enum EditStatus {
        APPLIED, WORLD_MISMATCH, STALE_EPOCH, UNKNOWN_CUBE, STALE_LEASE, STALE_REVISION,
        POSITION_MISMATCH, IMMUTABLE_BOUNDARY, INVALID_STATE, NOT_READY, SAVE_FAILED, CLOSING,
        UNSUPPORTED_REPLACEMENT, REPLACEMENT_BUDGET, UNSUPPORTED_NATIVE, NATIVE_BUDGET
    }
    public enum Lifecycle { OPENING, STAGING, OPEN, CLOSING, CLOSED }
    public sealed interface Event permits StageMetadata, Snapshot, EarthBootstrapSnapshot, StageReady, Committed, EditAck, Failure, Closed {}
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
     * currentLeases and candidateLeases include pin dependencies only when their explicit mode is enabled. newPositions is
     * candidate minus current, retiredPositions is current minus candidate. renderTargets is only
     * the moving27-target view. pinCenter is candidateFocus; currentFocus binds the old editable
     * target. A changed focus is a same-center unpinned-to-pinned transaction, never a second pin.
     * Initial currentLeases is empty, currentCenter equals candidateCenter, and every candidate is new.
     */
    public record StageMetadata(WorldIdentity world, long epoch, long generation, boolean initial,
                                CubePos pinCenter, CubePos currentCenter, CubePos candidateCenter,
                                List<CubeLease> currentLeases, List<CubeLease> candidateLeases,
                                List<CubePos> newPositions, List<CubePos> retiredPositions,
                                List<CubePos> renderTargets, boolean currentPinned, boolean candidatePinned,
                                CubePos currentFocus) implements Event {
        public StageMetadata(WorldIdentity world, long epoch, long generation, boolean initial,
                CubePos pinCenter, CubePos currentCenter, CubePos candidateCenter,
                List<CubeLease> currentLeases, List<CubeLease> candidateLeases,
                List<CubePos> newPositions, List<CubePos> retiredPositions,
                List<CubePos> renderTargets, boolean currentPinned, boolean candidatePinned) {
            this(world, epoch, generation, initial, pinCenter, currentCenter, candidateCenter,
                    currentLeases, candidateLeases, newPositions, retiredPositions, renderTargets,
                    currentPinned, candidatePinned, pinCenter);
        }
        public StageMetadata(WorldIdentity world, long epoch, long generation, boolean initial,
                CubePos pinCenter, CubePos currentCenter, CubePos candidateCenter,
                List<CubeLease> currentLeases, List<CubeLease> candidateLeases,
                List<CubePos> newPositions, List<CubePos> retiredPositions, List<CubePos> renderTargets) {
            this(world, epoch, generation, initial, pinCenter, currentCenter, candidateCenter,
                    currentLeases, candidateLeases, newPositions, retiredPositions, renderTargets, true, true);
        }
        public StageMetadata {
            Objects.requireNonNull(world); Objects.requireNonNull(pinCenter); Objects.requireNonNull(currentFocus);
            Objects.requireNonNull(currentCenter); Objects.requireNonNull(candidateCenter);
            if (epoch < 0 || generation <= 0) throw new IllegalArgumentException("Invalid stage identity");
            if (!currentFocus.equals(pinCenter) && (initial || !candidatePinned
                    || !currentCenter.equals(candidateCenter) || !RollingCubePlan.adjacent(pinCenter, currentCenter)
                    || currentPinned && (Math.abs((long) currentFocus.x() - pinCenter.x()) != 1
                        || currentFocus.y() != pinCenter.y() || currentFocus.z() != pinCenter.z()
                        || !RollingCubePlan.adjacent(currentFocus, currentCenter)
                        || !currentLeases.equals(candidateLeases) || !newPositions.isEmpty() || !retiredPositions.isEmpty())))
                throw new IllegalArgumentException("Focus switch requires exact same-center resident halos and a bounded pin handoff");
            currentLeases = boundedCopy(currentLeases, MAX_COMMITTED_CUBES);
            candidateLeases = boundedCopy(candidateLeases, MAX_COMMITTED_CUBES);
            newPositions = boundedCopy(newPositions, MAX_COMMITTED_CUBES);
            retiredPositions = boundedCopy(retiredPositions, MAX_COMMITTED_CUBES);
            renderTargets = boundedCopy(renderTargets, 27);
        }
        public CubePos candidateFocus() { return pinCenter; }
    }
    public record EarthBootstrapSnapshot(long generation, EngineCubeSnapshot snapshot) implements Event {}
    public record Snapshot(long generation, EngineCubeSnapshot snapshot) implements Event {
        public Snapshot {
            if (generation <= 0) throw new IllegalArgumentException("Positive plan generation required");
            Objects.requireNonNull(snapshot);
        }
    }
    /** Exact revision manifest: candidate125 plus optional fixed27 dependencies (at most152 distinct). */
    public record StageReady(WorldIdentity world, long epoch, long generation, CubePos candidateCenter,
                             List<CubeVersion> versions, ReplacementCertificate replacementCertificate, boolean pinned,
                             CubePos activeFocus, ReplacementSetCertificate replacementSet,
                             List<NativeReplacementCapture> replacementCaptures, ArenaCertificate arenaCertificate, ArenaCapture arenaCapture, EarthReplacementCapture earthReplacementCapture) implements Event {
        public StageReady(WorldIdentity world, long epoch, long generation, CubePos candidateCenter,
                List<CubeVersion> versions, ReplacementCertificate replacementCertificate, boolean pinned,
                CubePos activeFocus, ReplacementSetCertificate replacementSet, List<NativeReplacementCapture> replacementCaptures,
                ArenaCertificate arenaCertificate, ArenaCapture arenaCapture) {
            this(world,epoch,generation,candidateCenter,versions,replacementCertificate,pinned,activeFocus,replacementSet,replacementCaptures,arenaCertificate,arenaCapture,null);
        }
        public StageReady(WorldIdentity world, long epoch, long generation, CubePos candidateCenter,
                List<CubeVersion> versions, ReplacementCertificate replacementCertificate, boolean pinned,
                CubePos activeFocus, ReplacementSetCertificate replacementSet, List<NativeReplacementCapture> replacementCaptures) {
            this(world,epoch,generation,candidateCenter,versions,replacementCertificate,pinned,activeFocus,replacementSet,replacementCaptures,null,null);
        }
        public StageReady(WorldIdentity world, long epoch, long generation, CubePos candidateCenter,
                List<CubeVersion> versions, ReplacementCertificate replacementCertificate, boolean pinned) {
            this(world, epoch, generation, candidateCenter, versions, replacementCertificate, pinned, null, null, List.of());
        }
        public StageReady(WorldIdentity world, long epoch, long generation, CubePos candidateCenter,
                          List<CubeVersion> versions) {
            this(world, epoch, generation, candidateCenter, versions, null, true);
        }
        public StageReady(WorldIdentity world, long epoch, long generation, CubePos candidateCenter,
                          List<CubeVersion> versions, ReplacementCertificate replacementCertificate) {
            this(world, epoch, generation, candidateCenter, versions, replacementCertificate, true);
        }
        public StageReady {
            Objects.requireNonNull(world); Objects.requireNonNull(candidateCenter);
            if (epoch < 0 || generation <= 0) throw new IllegalArgumentException("Invalid ready identity");
            versions = boundedCopy(versions, MAX_COMMITTED_CUBES);
            replacementCaptures = boundedCopy(replacementCaptures, ReplacementWorkspaces.COUNT);
            if (replacementCertificate != null && (!world.equals(replacementCertificate.world())
                    || epoch != replacementCertificate.epoch()))
                throw new IllegalArgumentException("Replacement certificate belongs to another session");
            if (replacementSet != null) {
                if (replacementCertificate != null || !world.equals(replacementSet.world()) || epoch != replacementSet.epoch()
                        || activeFocus == null || !replacementSet.workspaces().contains(activeFocus)
                        || replacementCaptures.size() != ReplacementWorkspaces.COUNT)
                    throw new IllegalArgumentException("Complete scoped replacement set and active focus required");
                replacementSet.verifyCaptures(replacementCaptures);
            } else if (activeFocus != null || !replacementCaptures.isEmpty()) {
                throw new IllegalArgumentException("Two-workspace focus/captures require complete set authority");
            }
            if ((arenaCertificate == null) != (arenaCapture == null)) throw new IllegalArgumentException("Complete arena certificate/capture pair required");
            if (arenaCertificate != null) {
                if (replacementCertificate != null || replacementSet != null || pinned || activeFocus != null
                        || !world.equals(arenaCertificate.world()) || epoch != arenaCertificate.epoch())
                    throw new IllegalArgumentException("Arena has one separate unpinned union authority");
                arenaCertificate.verifyCapture(arenaCapture);
            }
            if (earthReplacementCapture != null) EarthReplacementAdmission.verifyCapture(replacementCertificate,earthReplacementCapture);
            if (replacementCertificate != null && EarthReplacementAdmission.MATERIAL_POLICY.equals(replacementCertificate.materialPolicy()) && earthReplacementCapture == null)
                throw new IllegalArgumentException("Earth ready requires its exact immutable capture");
            if (!pinned && replacementCertificate == null && replacementSet == null && arenaCertificate == null)
                throw new IllegalArgumentException("Unpinned ready requires replacement authority");
        }
    }
    public record Committed(WorldIdentity world, long epoch, long generation, CubePos center) implements Event {}
    public record EditRequest(WorldIdentity world, long epoch, CubePos position, long leaseGeneration,
                              long expectedRevision, long x, long y, long z, int stateId) {
        public EditRequest { Objects.requireNonNull(world); Objects.requireNonNull(position); }
    }
    /** APPLIED follows both durable completion and the replacement Snapshot in FIFO output order. */
    public record EditAck(EditRequest request, EditStatus status, long revision, String detail,
                          ReplacementCertificate replacementCertificate, ReplacementSetCertificate replacementSet,
                          List<NativeReplacementCapture> replacementCaptures, ArenaCertificate arenaCertificate, ArenaCapture arenaCapture, EarthReplacementCapture earthReplacementCapture) implements Event {
        public EditAck(EditRequest request, EditStatus status, long revision, String detail,
                ReplacementCertificate replacementCertificate, ReplacementSetCertificate replacementSet,
                List<NativeReplacementCapture> replacementCaptures, ArenaCertificate arenaCertificate, ArenaCapture arenaCapture) {
            this(request,status,revision,detail,replacementCertificate,replacementSet,replacementCaptures,arenaCertificate,arenaCapture,null);
        }
        public EditAck(EditRequest request, EditStatus status, long revision, String detail,
                ReplacementCertificate replacementCertificate, ReplacementSetCertificate replacementSet,
                List<NativeReplacementCapture> replacementCaptures) {
            this(request,status,revision,detail,replacementCertificate,replacementSet,replacementCaptures,null,null);
        }
        public EditAck(EditRequest request, EditStatus status, long revision, String detail,
                ReplacementCertificate replacementCertificate) {
            this(request, status, revision, detail, replacementCertificate, null, List.of());
        }
        public EditAck(EditRequest request, EditStatus status, long revision, String detail) {
            this(request, status, revision, detail, null);
        }
        public EditAck {
            replacementCaptures = boundedCopy(replacementCaptures, ReplacementWorkspaces.COUNT);
            if (replacementCertificate != null && (status != EditStatus.APPLIED
                    || !request.world().equals(replacementCertificate.world())
                    || request.epoch() != replacementCertificate.epoch()
                    || !request.position().equals(replacementCertificate.originalCube())
                    || revision != replacementCertificate.originalRevision()))
                throw new IllegalArgumentException("Edit certificate does not acknowledge this durable edit");
            if (replacementSet != null) {
                if (replacementCertificate != null || status != EditStatus.APPLIED
                        || !request.world().equals(replacementSet.world()) || request.epoch() != replacementSet.epoch()
                        || !replacementSet.workspaces().contains(request.position())
                        || revision != replacementSet.region(request.position()).revision()
                        || replacementCaptures.size() != ReplacementWorkspaces.COUNT)
                    throw new IllegalArgumentException("Edit set does not acknowledge this durable edit");
                replacementSet.verifyCaptures(replacementCaptures);
            } else if (!replacementCaptures.isEmpty()) throw new IllegalArgumentException("Captures require complete set authority");
            if (earthReplacementCapture != null) EarthReplacementAdmission.verifyCapture(replacementCertificate,earthReplacementCapture);
            if (replacementCertificate != null && EarthReplacementAdmission.MATERIAL_POLICY.equals(replacementCertificate.materialPolicy()) && earthReplacementCapture == null)
                throw new IllegalArgumentException("Earth acknowledgement requires its exact immutable capture");
            if ((arenaCertificate == null) != (arenaCapture == null)) throw new IllegalArgumentException("Complete arena acknowledgement required");
            if (arenaCertificate != null) {
                if (replacementCertificate != null || replacementSet != null || status != EditStatus.APPLIED
                        || !request.world().equals(arenaCertificate.world()) || request.epoch() != arenaCertificate.epoch()
                        || !arenaCertificate.definition().contains(request.position()) || revision != arenaCertificate.revision(request.position()))
                    throw new IllegalArgumentException("Arena certificate does not acknowledge this durable edit");
                arenaCertificate.verifyCapture(arenaCapture);
            }
        }
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
                              int stagedSnapshotsPublished, long publishedSnapshots, long admittedEdits,
                              boolean currentPinned, boolean candidatePinned) {
        public int retainedSnapshotReferenceSlots() { return encodedSnapshotCaches + queuedSnapshots; }
        public int queuedCommands() { return queuedEdits + queuedCommits + queuedGoals; }
    }

    private final Path directory;
    private final WorldIdentity world;
    private final CubeProvider provider;
    private final EnginePaletteContext palette;
    private final long epoch;
    private volatile CubePos pinCenter;
    private final CubePos initialCenter;
    private final boolean earthResumeBootstrap;
    private final io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Reservation earthBootstrapReservation;
    private final java.util.ArrayDeque<EngineCubeSnapshot> earthBootstrapSnapshots=new java.util.ArrayDeque<>(27);
    private final Map<CubePos,CubeVersion> earthBootstrapVersions=new java.util.LinkedHashMap<>(27);
    private List<CubeLease> earthBootstrapLeases;
    private int earthBootstrapIndex;
    private final ReplacementPolicy replacementPolicy;
    private final RollingEditPolicy editPolicy;
    private final ReplacementWorkspaces workspaces;
    private final DhAnalyticFlatPolicy flatPolicy;
    private final String indexedScope;
    private final ArenaDefinition arena;
    private final String arenaSourceIdentity, arenaLayoutIdentity, arenaScope;
    private final ArenaAdmission arenaAdmission;
    private final boolean earthNativeEdits;
    private final EarthReplacementAdmission earthAdmission;
    private final String earthSourceIdentity, earthLayoutIdentity;
    private EarthReplacementCapture earthCapture, prospectiveEarth;
    private long earthPeakCaptureBytes;
    public record EarthDiagnostics(long capturePayloadBytes,long peakCaptureArrayBytes,int retainedDependencyVersions) {}
    private volatile EarthDiagnostics earthCounters=new EarthDiagnostics(0,0,0);
    public EarthDiagnostics earthDiagnostics(){return earthCounters;}
    private ArenaRevisionLedger arenaLedger;
    private volatile NativeArenaAuthority arenaAuthority;
    private ArenaCapture arenaCapture, prospectiveArena;
    private ArenaCapture.Builder arenaBuilder;
    private int arenaBootstrapPeakOwners, arenaBootstrapPeakCaches;
    private long arenaPeakCaptureBytes;
    public record ArenaDiagnostics(int bootstrapPeakOwners,int bootstrapPeakSnapshotCaches,long capturePayloadBytes,long peakCaptureArrayBytes,int retainedDependencyVersions) {}
    private volatile ArenaDiagnostics arenaCounters=new ArenaDiagnostics(0,0,0,0,0);
    public ArenaDiagnostics arenaDiagnostics(){return arenaCounters;}
    private final List<CubeVersion> arenaBootstrapVersions = new ArrayList<>(64);
    private int arenaBootstrapIndex;
    private CubeLease arenaBootstrapLease;
    private CompletableFuture<Void> bootstrapSeal;
    private volatile NativeReplacementSetAuthority replacementSetAuthority;
    private ReplacementRevisionLedger replacementLedger;
    private final Map<CubePos, NativeReplacementCapture> replacementCaptures = new HashMap<>();
    private BootstrapCapture bootstrapCapture;
    private int bootstrapRegion;
    private boolean bootstrapComplete, bootstrapAdmissionChecked, replacementSaveFailed;
    private CubePos requestedFocus;
    private String requestedFocusIdentity;
    private volatile NativeReplacementAuthority replacementAuthority;
    private volatile boolean currentPinned = true;
    private boolean desiredPinned = true, restorePinRequested;
    private long deliveredCommittedGeneration;
    private final ArrayBlockingQueue<EditRequest> edits = new ArrayBlockingQueue<>(MAX_PENDING_EDITS);
    private final ArrayBlockingQueue<CommitCommand> commits = new ArrayBlockingQueue<>(MAX_PENDING_COMMITS);
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
    private boolean pendingCommit, activeCandidatePinned = true;
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
    private CompletableFuture<Void> journal;
    private boolean journalConfirmed;
    private long savedRevision;
    private final Map<CubePos, NativeReplacementCapture> prospectiveCaptures = new HashMap<>();
    private String prospectivePriorIdentity, prospectiveContentIdentity;
    private EditAck acknowledgement;
    private boolean replacementPublished, closeOutputPrepared, hostClosing, failurePublished;
    private long publishedSnapshots, admittedEdits;
    private Throwable failure;

    private RollingCubeActor(Path directory, WorldIdentity world, CubeProvider provider,
                             EnginePaletteContext palette, long epoch, CubePos center, ReplacementPolicy replacementPolicy,
                             DhAnalyticFlatPolicy flatPolicy, ReplacementWorkspaces workspaces, boolean indexed) {
        this(directory,world,provider,palette,epoch,center,replacementPolicy,flatPolicy,workspaces,indexed,null,null,null,null);
    }
    private RollingCubeActor(Path directory, WorldIdentity world, CubeProvider provider,
            EnginePaletteContext palette,long epoch,CubePos center,ReplacementPolicy replacementPolicy,
            DhAnalyticFlatPolicy flatPolicy,ReplacementWorkspaces workspaces,boolean indexed,
            ArenaDefinition arena,String sourceIdentity,String layoutIdentity,ArenaAdmission arenaAdmission) {
        this(directory,world,provider,palette,epoch,center,replacementPolicy,flatPolicy,workspaces,indexed,
                arena,sourceIdentity,layoutIdentity,arenaAdmission,false);
    }
    private RollingCubeActor(Path directory, WorldIdentity world, CubeProvider provider,
            EnginePaletteContext palette,long epoch,CubePos center,ReplacementPolicy replacementPolicy,
            DhAnalyticFlatPolicy flatPolicy,ReplacementWorkspaces workspaces,boolean indexed,
            ArenaDefinition arena,String sourceIdentity,String layoutIdentity,ArenaAdmission arenaAdmission,boolean earthNativeEdits) {
        this(directory,world,provider,palette,epoch,center,replacementPolicy,flatPolicy,workspaces,indexed,
                arena,sourceIdentity,layoutIdentity,arenaAdmission,earthNativeEdits,null);
    }
    private RollingCubeActor(Path directory, WorldIdentity world, CubeProvider provider,
            EnginePaletteContext palette,long epoch,CubePos center,ReplacementPolicy replacementPolicy,
            DhAnalyticFlatPolicy flatPolicy,ReplacementWorkspaces workspaces,boolean indexed,
            ArenaDefinition arena,String sourceIdentity,String layoutIdentity,ArenaAdmission arenaAdmission,
            boolean earthNativeEdits,EarthReplacementAdmission earthAdmission) {
        this(directory,world,provider,palette,epoch,center,replacementPolicy,flatPolicy,workspaces,indexed,
                arena,sourceIdentity,layoutIdentity,arenaAdmission,earthNativeEdits,earthAdmission,center);
    }
    private RollingCubeActor(Path directory,WorldIdentity world,CubeProvider provider,EnginePaletteContext palette,
            long epoch,CubePos center,ReplacementPolicy replacementPolicy,DhAnalyticFlatPolicy flatPolicy,
            ReplacementWorkspaces workspaces,boolean indexed,ArenaDefinition arena,String sourceIdentity,
            String layoutIdentity,ArenaAdmission arenaAdmission,boolean earthNativeEdits,
            EarthReplacementAdmission earthAdmission,CubePos originalPin) {
        if(!center.equals(originalPin)&&earthAdmission==null)throw new IllegalArgumentException("Only exact Earth authority permits a distinct resume center");
        if(earthNativeEdits&&(flatPolicy!=null||workspaces!=null||indexed||arena!=null||replacementPolicy!=null&&earthAdmission==null))
            throw new IllegalArgumentException("Earth census requires its separate interior-only capability");
        if(earthAdmission==null && replacementPolicy!=null && EarthReplacementAdmission.MATERIAL_POLICY.equals(replacementPolicy.materialPolicy()))
            throw new IllegalArgumentException("Earth policy requires the exact Earth admission capability");
        if(earthAdmission!=null){
            if(!earthNativeEdits||replacementPolicy==null)throw new IllegalArgumentException("Earth replacement requires native census and authority");
            EarthReplacementAdmission.verifyPolicy(replacementPolicy);
            if(!replacementPolicy.providerIdentity().equals(sourceIdentity)||!world.datasetVersion().equals(sourceIdentity))
                throw new IllegalArgumentException("Earth replacement source must bind the exact world and provider policy");
            if(layoutIdentity==null||layoutIdentity.isBlank()||layoutIdentity.length()>1024)
                throw new IllegalArgumentException("Bounded exact Earth layout identity required");
        }
        this.earthNativeEdits=earthNativeEdits;this.earthAdmission=earthAdmission;
        earthSourceIdentity=earthAdmission==null?null:sourceIdentity;earthLayoutIdentity=earthAdmission==null?null:layoutIdentity;
        this.arena=arena;this.arenaSourceIdentity=sourceIdentity;this.arenaLayoutIdentity=layoutIdentity;this.arenaAdmission=arenaAdmission;
        if(arena!=null){
            arena.validateCenter(center);
            if(!(provider instanceof NativeArenaCubeProvider nativeProvider)||!world.equals(nativeProvider.identity())
                    ||!sourceIdentity.equals(nativeProvider.source().identity())||!arena.identity().equals(nativeProvider.source().definition().identity())
                    ||replacementPolicy==null||arenaAdmission==null||layoutIdentity==null||flatPolicy!=null||workspaces!=null||indexed)
                throw new IllegalArgumentException("Exact dedicated arena source and admission capability required");
            currentPinned=false;desiredPinned=false;activeCandidatePinned=false;
        }
        arenaScope=arena==null?null:ArenaQueryAttestation.policyIdentity(world,palette.fingerprint(),replacementPolicy,arena,sourceIdentity,layoutIdentity);
        this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
        this.world = Objects.requireNonNull(world); this.provider = Objects.requireNonNull(provider);
        this.palette = Objects.requireNonNull(palette); this.pinCenter = arena == null ? Objects.requireNonNull(originalPin) : arena.origin();
        this.initialCenter = center;
        this.earthResumeBootstrap=earthAdmission!=null&&!RollingCubePlan.adjacent(pinCenter,center);
        if(earthResumeBootstrap){currentPinned=false;desiredPinned=false;activeCandidatePinned=false;}
        if (epoch < 0) throw new IllegalArgumentException("Nonnegative actor epoch required");
        RollingCubePlan.validateWindowCenter(center);
        this.epoch = epoch; this.currentCenter = center; this.replacementPolicy = replacementPolicy;
        this.flatPolicy = flatPolicy; this.workspaces = workspaces;
        if ((flatPolicy == null) != (workspaces == null) || workspaces != null && !workspaces.original().equals(center))
            throw new IllegalArgumentException("Explicit workspace mode must match analytic policy and initial center");
        if (flatPolicy != null && (replacementPolicy == null || !world.equals(flatPolicy.providerIdentity())))
            throw new IllegalArgumentException("Explicit matching analytic provider and replacement policy required");
        if (indexed && (workspaces == null || !workspaces.isAdjacent()))
            throw new IllegalArgumentException("Indexed mode requires the exact adjacent two-workspace gate");
        this.indexedScope = indexed ? ReplacementQueryAttestation.policyIdentity(world, palette.fingerprint(),
                replacementPolicy, flatPolicy, workspaces) : null;
        this.editPolicy = replacementPolicy == null ? RollingEditPolicy.INTERIOR_V1 : replacementPolicy.editPolicy();
        counters = new Counters(0, 0, center, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, true, true);
        bootstrap = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), task -> {
            Thread worker = new Thread(task, "earth-rolling-actor-bootstrap"); worker.setDaemon(true); return worker;
        }, new ThreadPoolExecutor.AbortPolicy());
        actor = new Thread(this::run, "earth-rolling-cube-actor"); actor.setDaemon(true);
        earthBootstrapReservation=earthResumeBootstrap?io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.global().reserve(
                io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.OWNER_METADATA,27L*(EngineCubeSnapshot.MAX_SECTION_BYTES+512L)):null;
    }

    /** Registries must be frozen. Does not access files, wait for providers or block the caller. */
    public static RollingCubeActor open(Path directory, WorldIdentity world, CubeProvider provider,
                                        EnginePaletteContext palette, long epoch, CubePos initialCenter) {
        return open(directory, world, provider, palette, epoch, initialCenter, null);
    }

    /** Native-only checkpoint capability for compatibility tests; production Earth uses the authority overload below. */
    public static RollingCubeActor openEarth(Path directory,WorldIdentity world,CubeProvider provider,
            EnginePaletteContext palette,long epoch,CubePos initialCenter) {
        for(;;){int count=ACTORS.get();if(count>=MAX_ACTORS)throw new RejectedExecutionException("Rolling actor limit reached");if(ACTORS.compareAndSet(count,count+1))break;}
        RollingCubeActor result=null;
        try{
            result=new RollingCubeActor(directory,world,provider,palette,epoch,initialCenter,null,null,null,false,null,null,null,null,true);
            result.actor.start();return result;
        }catch(Throwable failed){if(result!=null)result.closeUnstarted();ACTORS.decrementAndGet();throw failed;}
    }

    /** Exact Earth far authority, admitted before first READY and before every durable mutation. */
    public static RollingCubeActor openEarth(Path directory,WorldIdentity world,CubeProvider provider,
            EnginePaletteContext palette,long epoch,CubePos initialCenter,ReplacementPolicy replacementPolicy,
            String sourceIdentity,String layoutIdentity,EarthReplacementAdmission admission) {
        Objects.requireNonNull(admission);
        for(;;){int count=ACTORS.get();if(count>=MAX_ACTORS)throw new RejectedExecutionException("Rolling actor limit reached");if(ACTORS.compareAndSet(count,count+1))break;}
        RollingCubeActor result=null;
        try{result=new RollingCubeActor(directory,world,provider,palette,epoch,initialCenter,replacementPolicy,null,null,false,null,sourceIdentity,layoutIdentity,null,true,admission);result.actor.start();return result;}
        catch(Throwable failed){if(result!=null)result.closeUnstarted();ACTORS.decrementAndGet();throw failed;}
    }

    /** Exact durable pin is independent of the held native window. Far resumes reconstruct original27 first. */
    public static RollingCubeActor resumeEarth(Path directory,WorldIdentity world,CubeProvider provider,
            EnginePaletteContext palette,long epoch,CubePos originalPin,CubePos initialCenter,ReplacementPolicy policy,
            String sourceIdentity,String layoutIdentity,EarthReplacementAdmission admission) {
        Objects.requireNonNull(admission);Objects.requireNonNull(originalPin);Objects.requireNonNull(initialCenter);
        if(ACTORS.incrementAndGet()>MAX_ACTORS){ACTORS.decrementAndGet();throw new RejectedExecutionException("Rolling actor cap reached while old actor drains");}
        RollingCubeActor result=null;
        try{result=new RollingCubeActor(directory,world,provider,palette,epoch,initialCenter,policy,null,null,false,null,
                sourceIdentity,layoutIdentity,null,true,admission,originalPin);result.actor.start();return result;}
        catch(Throwable failed){if(result!=null)result.closeUnstarted();ACTORS.decrementAndGet();throw failed;}
    }

    public static RollingCubeActor open(Path directory, WorldIdentity world, CubeProvider provider,
            EnginePaletteContext palette, long epoch, CubePos initialCenter, ReplacementPolicy replacementPolicy) {
        return open(directory, world, provider, palette, epoch, initialCenter, replacementPolicy, null);
    }

    public static RollingCubeActor open(Path directory, WorldIdentity world, CubeProvider provider,
            EnginePaletteContext palette, long epoch, CubePos initialCenter, ReplacementPolicy replacementPolicy,
            DhAnalyticFlatPolicy flatPolicy) {
        return open(directory, world, provider, palette, epoch, initialCenter, replacementPolicy, flatPolicy,
                flatPolicy == null ? null : ReplacementWorkspaces.two(initialCenter));
    }

    public static RollingCubeActor open(Path directory, WorldIdentity world, CubeProvider provider,
            EnginePaletteContext palette, long epoch, CubePos initialCenter, ReplacementPolicy replacementPolicy,
            DhAnalyticFlatPolicy flatPolicy, ReplacementWorkspaces workspaces) {
        return openInternal(directory, world, provider, palette, epoch, initialCenter, replacementPolicy, flatPolicy, workspaces, false);
    }

    /** Explicit opt-in; existing stores and legacy certificate identities are never adopted. */
    public static RollingCubeActor openIndexed(Path directory, WorldIdentity world, CubeProvider provider,
            EnginePaletteContext palette, long epoch, CubePos initialCenter, ReplacementPolicy replacementPolicy,
            DhAnalyticFlatPolicy flatPolicy, ReplacementWorkspaces workspaces) {
        return openInternal(directory, world, provider, palette, epoch, initialCenter, replacementPolicy, flatPolicy, workspaces, true);
    }

    private static RollingCubeActor openInternal(Path directory, WorldIdentity world, CubeProvider provider,
            EnginePaletteContext palette, long epoch, CubePos initialCenter, ReplacementPolicy replacementPolicy,
            DhAnalyticFlatPolicy flatPolicy, ReplacementWorkspaces workspaces, boolean indexed) {
        for (;;) {
            int count = ACTORS.get();
            if (count >= MAX_ACTORS) throw new RejectedExecutionException("Rolling actor limit reached");
            if (ACTORS.compareAndSet(count, count + 1)) break;
        }
        RollingCubeActor result = null;
        try {
            result = new RollingCubeActor(directory, world, provider, palette, epoch, initialCenter, replacementPolicy, flatPolicy, workspaces, indexed);
            result.actor.start(); return result;
        } catch (Throwable failed) {
            if (result != null) result.closeUnstarted();
            ACTORS.decrementAndGet(); throw failed;
        }
    }

    /** Explicit arena capability. Never reads or adopts a legacy two-region store. */
    public static RollingCubeActor openArena(Path directory,WorldIdentity world,CubeProvider provider,
            EnginePaletteContext palette,long epoch,CubePos initialCenter,ReplacementPolicy replacementPolicy,
            ArenaDefinition definition,String sourceIdentity,String layoutIdentity,ArenaAdmission admission) {
        Objects.requireNonNull(definition);Objects.requireNonNull(sourceIdentity);Objects.requireNonNull(layoutIdentity);Objects.requireNonNull(admission);
        for(;;){int count=ACTORS.get();if(count>=MAX_ACTORS)throw new RejectedExecutionException("Rolling actor limit reached");if(ACTORS.compareAndSet(count,count+1))break;}
        RollingCubeActor result=null;
        try{result=new RollingCubeActor(directory,world,provider,palette,epoch,initialCenter,replacementPolicy,null,null,false,definition,sourceIdentity,layoutIdentity,admission);result.actor.start();return result;}
        catch(Throwable failed){if(result!=null)result.closeUnstarted();ACTORS.decrementAndGet();throw failed;}
    }

    /** Constructor completed but the actor thread could not start: no physical writer ever owned this charge. */
    private void closeUnstarted(){
        if(actor.getState()!=Thread.State.NEW){close();return;}
        bootstrap.shutdown();
        if(earthBootstrapReservation!=null)earthBootstrapReservation.releaseAfterPhysicalDrain();
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
                if (event instanceof Committed committed) deliveredCommittedGeneration = committed.generation();
                if (event instanceof StageReady ready && ready.generation() == activeStageGeneration)
                    deliveredReadyGeneration = ready.generation();
            }
        }
        LockSupport.unpark(actor); return List.copyOf(batch);
    }

    /** One latest goal, including while opening or staging; every eventual step is adjacent. */
    public Admission requestCenter(CubePos goal) {
        Objects.requireNonNull(goal); RollingCubePlan.validateWindowCenter(goal); if (arena != null) arena.validateCenter(goal);
        synchronized (admissionLock) {
            if (stopRequested) return Admission.CLOSING;
            if (replacementSaveFailed) return Admission.NOT_READY;
            if (requestedFocus != null) return Admission.BUSY;
            latestGoal = goal;
        }
        LockSupport.unpark(actor); return Admission.QUEUED;
    }

    public CubePos currentFocus() { return pinCenter; }
    public CubePos activeFocus() { return pinCenter; }
    /** Revalidate issued-or-committed exact authority immediately before server ALLOW/publication. */
    public boolean replacementCurrent(String identity) {
        synchronized (admissionLock) {
            return !stopRequested && !replacementSaveFailed && canCommitReplacement(identity);
        }
    }

    /** The controller supplies its exact committed pair. No client-selected coordinates are inferred. */
    public Admission requestFocus(CubePos target, long generation, String setIdentity) {
        Objects.requireNonNull(target);
        synchronized (admissionLock) {
            if (stopRequested) return Admission.CLOSING;
            if (generation <= 0 || generation != deliveredCommittedGeneration) return Admission.STALE_GENERATION;
            if (workspaces == null || !workspaces.contains(target) || target.equals(pinCenter)
                    || currentPinned && !residentAdjacentFocus(target) || lifecycle != Lifecycle.OPEN || pendingEdit || activeStageGeneration != 0
                    || replacementSaveFailed || !workspaces.insideWindow(target, currentCenter)
                    || replacementSetAuthority == null || !replacementSetAuthority.current(setIdentity)) return Admission.NOT_READY;
            if (requestedFocus != null) return Admission.BUSY;
            latestGoal = null;
            requestedFocus = target; requestedFocusIdentity = setIdentity;
        }
        LockSupport.unpark(actor); return Admission.QUEUED;
    }

    private boolean residentAdjacentFocus(CubePos target) {
        return workspaces != null && workspaces.isAdjacent() && workspaces.contains(target)
                && workspaces.insideWindow(pinCenter, currentCenter) && workspaces.insideWindow(target, currentCenter);
    }

    /**
     * A trusted controller calls this only after verifying the exact paired native/DH client ACK.
     * Generation is actor-local and monotonic; the caller also fences world, epoch, leases and
     * revisions with StageReady. Delivery of metadata or snapshots alone never authorizes commit.
     */
    public Admission commitPrepared(long generation) { return commitPrepared(generation, null); }

    /** The trusted host passes the certificate identity bound by the exact native/far paired ACK. */
    public Admission commitPrepared(long generation, String certificateIdentity) {
        synchronized (admissionLock) {
            if (stopRequested) return Admission.CLOSING;
            if (generation <= 0 || generation != activeStageGeneration) return Admission.STALE_GENERATION;
            if (deliveredReadyGeneration != generation) return Admission.NOT_READY;
            if (pendingCommit) return Admission.BUSY;
            if (certificateIdentity != null && !canCommitReplacement(certificateIdentity)) return Admission.NOT_READY;
            if ((!activeCandidatePinned || workspaces != null || arena != null || earthAdmission != null) && certificateIdentity == null) return Admission.NOT_READY;
            if (!commits.offer(new CommitCommand(generation, certificateIdentity))) return Admission.BUSY;
            pendingCommit = true;
        }
        LockSupport.unpark(actor); return Admission.QUEUED;
    }

    /**
     * Called only after the matching pair is draw-ready and actor Committed has been consumed.
     * Edit-only publications use the same rolling generation and their newly issued exact ID.
     * Retirement is folded into the next actual move, never performed in this call.
     */
    public Admission authorizePinRetirement(long generation, String certificateIdentity) {
        synchronized (admissionLock) {
            if (stopRequested) return Admission.CLOSING;
            if (generation <= 0 || generation != deliveredCommittedGeneration) return Admission.STALE_GENERATION;
            if (activeStageGeneration != 0 || pendingEdit || lifecycle != Lifecycle.OPEN) return Admission.NOT_READY;
            if (!commitReplacement(certificateIdentity)) return Admission.NOT_READY;
            desiredPinned = false;
        }
        LockSupport.unpark(actor); return Admission.QUEUED;
    }

    /** Exact completed NONE pair fallback consumes the declined publication ID and retains the pin. */
    public Admission retainPinWithoutReplacement(long generation) {
        synchronized (admissionLock) {
            if (stopRequested) return Admission.CLOSING;
            if (generation <= 0 || generation != deliveredCommittedGeneration) return Admission.STALE_GENERATION;
            if (earthAdmission != null || workspaces != null || activeStageGeneration != 0 || pendingEdit || lifecycle != Lifecycle.OPEN || !currentPinned)
                return Admission.NOT_READY;
            if (replacementAuthority != null) replacementAuthority.revoke();
            desiredPinned = true;
        }
        LockSupport.unpark(actor); return Admission.QUEUED;
    }

    /** A same-center staged restoration retains the coherent old pair until a new paired commit. */
    public Admission requestPinRestore() {
        if (arena != null) return Admission.NOT_READY;
        synchronized (admissionLock) {
            if (stopRequested) return Admission.CLOSING;
            if(earthAdmission!=null && (lifecycle!=Lifecycle.OPEN || activeStageGeneration!=0 || pendingEdit
                    || !RollingCubePlan.adjacent(pinCenter,currentCenter)))return Admission.NOT_READY;
            // The certified two-workspace far proof covers exactly the moving27 masks. A pin
            // may be restored only while its target and all27 dependencies are already in that
            // current native window; an in-flight move could otherwise invalidate this check.
            if (workspaces != null && (lifecycle != Lifecycle.OPEN || activeStageGeneration != 0
                    || pendingEdit || requestedFocus != null || !workspaces.insideWindow(pinCenter, currentCenter)))
                return Admission.NOT_READY;
            desiredPinned = true; restorePinRequested = true;
        }
        LockSupport.unpark(actor); return Admission.QUEUED;
    }

    public Admission tryEdit(EditRequest request) {
        Objects.requireNonNull(request);
        synchronized (admissionLock) {
            if (stopRequested) return Admission.CLOSING;
            if (lifecycle != Lifecycle.OPEN || activeStageGeneration != 0 || (arena == null ? !currentPinned : !arena.insideWindow(currentCenter))
                    || requestedFocus != null || replacementSaveFailed) return Admission.NOT_READY;
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
        for (Event event : results) if (event instanceof Snapshot||event instanceof EarthBootstrapSnapshot) snapshots++;
        synchronized (admissionLock) {
            return new Diagnostics(lifecycle, results.size(), snapshots, edits.size(), commits.size(),
                    latestGoal == null && requestedFocus == null ? 0 : 1, pendingEdit, c.committed, c.staged, c.current, c.candidate,
                    c.residents, c.ready, c.tickets, c.encoded, c.versions, c.running, c.queued,
                    c.cancelled, c.stageSnapshots, c.published, c.edits, c.currentPinned, c.candidatePinned);
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
                                MAX_GENERATION_WORKERS, MAX_PENDING_EDITS), bootstrap,
                        replacementPolicy == null || workspaces != null || arena != null ? null : pinCenter, workspaces, indexedScope,
                        arena, arenaScope, arenaLayoutIdentity);
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
            if (arena != null) {
                arenaLedger=Objects.requireNonNull(host.arenaLedger());
                arenaAuthority=new NativeArenaAuthority(world,epoch,arena,palette.fingerprint(),replacementPolicy,arenaLedger);
                arenaBuilder=ArenaCapture.builder(arena);arenaPeakCaptureBytes=ArenaCapture.PACKED_BYTES;
            } else if (workspaces != null) {
                replacementLedger = Objects.requireNonNull(host.replacementLedger());
                replacementSetAuthority = new NativeReplacementSetAuthority(world, epoch, workspaces,
                        host.replacementStoreIdentity(), replacementLedger.identity().indexIdentity(), palette.fingerprint(), replacementPolicy,
                        indexedScope == null ? null : replacementLedger);
            } else {
                if (replacementPolicy != null) replacementAuthority = new NativeReplacementAuthority(world, epoch, pinCenter,
                        host.replacementStoreIdentity(), palette.fingerprint(), replacementPolicy);
                bootstrapComplete = !earthResumeBootstrap;
                if (!stopRequested) {
                    host.updateTicket("rolling-pin", pinCenter, 1, 1);
                    if(earthResumeBootstrap){
                        var leases=new ArrayList<CubeLease>(27);
                        for(var position:io.earthplatform.core.CubeTickets.box(pinCenter,1,1,27).stream().sorted().toList())
                            leases.add(new CubeLease(position,nextLease++));
                        earthBootstrapLeases=List.copyOf(leases);
                    }else beginStage(currentCenter, true);
                }
            }
        }
        if (stopRequested) { drainClose(); return; }
        if (host == null) throw new IllegalStateException("Actor has no opened host");
        if (!bootstrapComplete) { if (earthResumeBootstrap) advanceEarthResumeBootstrap(); else if (arena != null) advanceArenaBootstrap(); else advanceBootstrap(); return; }
        if (stage != null) { advanceStage(); return; }
        if (editing == null && acknowledgement == null && results.remainingCapacity() > 0) {
            EditRequest request = edits.poll();
            if (request != null) startEdit(request);
        }
        advanceEdit();
        synchronized (admissionLock) {
            if (pendingEdit || stopRequested || results.remainingCapacity() == 0 || replacementSaveFailed) return;
            if (requestedFocus != null) {
                CubePos target = requestedFocus; String identity = requestedFocusIdentity;
                requestedFocus = null; requestedFocusIdentity = null;
                if (currentPinned && !residentAdjacentFocus(target) || !replacementSetAuthority.current(identity) || !workspaces.insideWindow(target, currentCenter))
                    throw new IllegalStateException("Focus handoff lost its exact old replacement pair");
                desiredPinned = true; beginStage(currentCenter, false, true, target); return;
            }
            if (restorePinRequested) {
                restorePinRequested = false;
                if (!currentPinned) { beginStage(currentCenter, false, true); return; }
            }
            if (latestGoal != null && latestGoal.equals(currentCenter)) latestGoal = null;
            if (latestGoal != null) {
                CubePos candidate = RollingCubePlan.nextStep(currentCenter, latestGoal);
                // Keep the one coalesced goal, but do not invent an unproved28th disjoint mask.
                // Only the explicit exact-set retirement authorization releases this barrier.
                if (workspaces != null && desiredPinned && !workspaces.insideWindow(pinCenter, candidate)) return;
                if (earthAdmission != null && desiredPinned && !RollingCubePlan.adjacent(pinCenter,candidate)) return;
                beginStage(candidate, false, arena == null && (desiredPinned || RollingCubePlan.adjacent(pinCenter, candidate)));
            }
        }
    }

    private boolean canCommitReplacement(String identity) {
        return arenaAuthority != null ? arenaAuthority.canCommit(identity) : replacementSetAuthority != null ? replacementSetAuthority.canCommit(identity)
                : replacementAuthority != null && replacementAuthority.canCommit(identity);
    }
    private boolean commitReplacement(String identity) {
        return arenaAuthority != null ? arenaAuthority.commit(identity) : replacementSetAuthority != null ? replacementSetAuthority.commit(identity)
                : replacementAuthority != null && replacementAuthority.commit(identity);
    }
    private boolean currentReplacement(String identity) {
        return arenaAuthority != null ? arenaAuthority.current(identity) : replacementSetAuthority != null ? replacementSetAuthority.current(identity)
                : replacementAuthority != null && replacementAuthority.current(identity);
    }
    private boolean hasCommittedReplacement() {
        return arenaAuthority != null ? arenaAuthority.committed() != null : replacementSetAuthority != null ? replacementSetAuthority.committed() != null
                : replacementAuthority != null && replacementAuthority.committed() != null;
    }
    private List<NativeReplacementCapture> captures() {
        return workspaces.positions().stream().map(position -> Objects.requireNonNull(replacementCaptures.get(position))).toList();
    }
    private void verifyLedger(CubeVersion version) {
        try { replacementLedger.verifyObserved(version.position(), version.revision()); }
        catch (java.io.IOException failure) { throw new CompletionException(failure); }
    }

    /** Serial one-cube reconstruction: one native resident/cache plus one transferred packed array. */
    private void advanceArenaBootstrap() {
        if (arenaBootstrapIndex < arena.dependencies().size()) {
            CubePos position=arena.dependencies().get(arenaBootstrapIndex);
            if(arenaBootstrapLease==null){arenaBootstrapLease=new CubeLease(position,nextLease);nextLease=Math.incrementExact(nextLease);host.updateTicket("arena-bootstrap",position,0,0);}
            var result=host.pollSnapshot(position,epoch,arenaBootstrapLease.leaseGeneration());
            if(result.status()==NativeCubicHost.Status.LOADING)return;
            if(result.status()!=NativeCubicHost.Status.READY)throw new IllegalStateException("Arena dependency reconstruction unavailable",result.failure());
            arenaBootstrapPeakOwners=Math.max(arenaBootstrapPeakOwners,host.stats().residentCubes());
            arenaBootstrapPeakCaches=Math.max(arenaBootstrapPeakCaches,host.encodedSnapshotCacheCount());
            if(arenaBootstrapPeakOwners>1||arenaBootstrapPeakCaches>1)throw new IllegalStateException("Serial arena reconstruction exceeded one physical owner/cache");
            CubeVersion version=new CubeVersion(position,arenaBootstrapLease.leaseGeneration(),result.snapshot().revision());
            try{arenaLedger.verifyObserved(position,version.revision());}catch(java.io.IOException failure){throw new CompletionException(failure);}
            for(long y=Math.max(position.minBlockY(),arena.readMinY());y<Math.min(position.minBlockY()+16,arena.readMaxYExclusive());y++)
                for(long z=Math.max(position.minBlockZ(),arena.readMinZ());z<Math.min(position.minBlockZ()+16,arena.readMaxZExclusive());z++)
                    for(long x=Math.max(position.minBlockX(),arena.readMinX());x<Math.min(position.minBlockX()+16,arena.readMaxXExclusive());x++)arenaBuilder.set(x,y,z,replacementMaterial(host.blockStateId(x,y,z)));
            arenaBootstrapVersions.add(version);host.removeTicket("arena-bootstrap");arenaBootstrapLease=null;arenaBootstrapIndex++;return;
        }
        if(arenaCapture==null){arenaCapture=arenaBuilder.seal();arenaBuilder=null;var receipt=arenaAdmission.assess(arenaCapture);receipt.verify(arenaCapture,arenaSourceIdentity,arenaLayoutIdentity);if(!receipt.admitted())throw new IllegalStateException("Arena reconstruction budget refused: "+receipt.reason());}
        if(bootstrapSeal==null){ArenaCapture complete=arenaCapture;bootstrapSeal=journalTask(()->arenaLedger.sealCapture(complete));return;}
        if(!bootstrapSeal.isDone())return;bootstrapSeal.join();
        try{arenaAuthority.bootstrap(arenaBootstrapVersions,arenaCapture);}catch(java.io.IOException failure){throw new CompletionException(failure);}
        arenaBootstrapVersions.clear();bootstrapComplete=true;beginStage(initialCenter,true,false);
    }

    /** Reconstruct the saved original independently before acquiring the held moving window. */
    private void advanceEarthResumeBootstrap(){
        for(int count=0;count<MAX_EVENTS_PER_POLL&&earthBootstrapIndex<earthBootstrapLeases.size();count++){
            var lease=earthBootstrapLeases.get(earthBootstrapIndex);
            var result=host.pollSnapshot(lease.position(),epoch,lease.leaseGeneration());
            if(result.status()==NativeCubicHost.Status.LOADING)return;
            if(result.status()!=NativeCubicHost.Status.READY)throw new IllegalStateException("Earth resume original dependency unavailable",result.failure());
            var snapshot=result.snapshot();var version=new CubeVersion(lease.position(),lease.leaseGeneration(),snapshot.revision());
            replacementAuthority.observed(version);earthBootstrapVersions.put(version.position(),version);
            earthBootstrapSnapshots.addLast(snapshot);earthBootstrapIndex++;
        }
        if(earthBootstrapIndex!=27)return;
        requireEarthNativeDependencies(earthBootstrapVersions);
        var nativeReceipt=EarthNativeEditAdmission.assessCurrent(pinCenter,host::blockStateId);
        if(!nativeReceipt.admitted())throw new IllegalStateException("Saved Earth resume native census refused: "+nativeReceipt.reason());
        earthCapture=captureEarth();earthPeakCaptureBytes=EarthReplacementCapture.CAPTURE_BYTES;
        var receipt=assessEarth(earthCapture);if(!receipt.admitted())throw new IllegalStateException("Saved Earth resume replacement refused: "+receipt.reason());
        var certificate=replacementAuthority.prepare(List.copyOf(earthBootstrapVersions.values()));
        if(certificate==null||!replacementAuthority.commit(certificate.identity()))throw new IllegalStateException("Earth resume durable authority missing");
        host.removeTicket("rolling-pin");earthBootstrapVersions.keySet().forEach(replacementAuthority::retire);
        earthBootstrapVersions.clear();earthBootstrapLeases=null;bootstrapComplete=true;
        beginStage(initialCenter,true,false);
    }

    /** Serial27 reconstruction keeps no source snapshot map and never overlaps a moving125 ticket. */
    private void advanceBootstrap() {
        if (bootstrapCapture == null && bootstrapRegion < ReplacementWorkspaces.COUNT) {
            CubePos position = workspaces.positions().get(bootstrapRegion);
            List<CubeLease> leases = new ArrayList<>(ReplacementCertificate.DEPENDENCIES);
            for (CubePos dependency : workspaces.dependencies(position)) {
                leases.add(new CubeLease(dependency, nextLease)); nextLease = Math.incrementExact(nextLease);
            }
            host.updateTicket("rolling-pin", position, 1, 1);
            bootstrapCapture = new BootstrapCapture(position, List.copyOf(leases));
        }
        if (bootstrapCapture != null) {
            BootstrapCapture capture = bootstrapCapture;
            for (int count = 0; count < MAX_EVENTS_PER_POLL && capture.nextVersion < capture.leases.size(); count++) {
                CubeLease lease = capture.leases.get(capture.nextVersion);
                var result = host.pollSnapshot(lease.position(), epoch, lease.leaseGeneration());
                if (result.status() == NativeCubicHost.Status.LOADING) return;
                if (result.status() != NativeCubicHost.Status.READY)
                    throw new IllegalStateException("Bootstrap replacement dependency unavailable", result.failure());
                CubeVersion version = new CubeVersion(lease.position(), lease.leaseGeneration(), result.snapshot().revision());
                verifyLedger(version); replacementSetAuthority.observed(version);
                capture.versions.add(version); encodedSnapshots.add(version.position()); capture.nextVersion++;
            }
            if (capture.nextVersion != capture.leases.size()) return;
            int end = Math.min(capture.values.length, capture.cursor + 1024);
            while (capture.cursor < end) {
                int cursor = capture.cursor;
                long x = capture.minX + cursor % 20, z = capture.minZ + cursor / 20 % 20,
                        y = capture.minY + cursor / 400;
                capture.values[capture.cursor++] = (byte) replacementMaterial(host.blockStateId(x, y, z));
            }
            if (capture.cursor != capture.values.length) return;
            NativeReplacementCapture completed = new NativeReplacementCapture(capture.position, capture.values);
            var region = new ReplacementSetCertificate.Region(capture.position, capture.versions, completed.contentIdentity());
            try { replacementLedger.verifyCapture(region); }
            catch (java.io.IOException failure) { throw new CompletionException(failure); }
            if (!replacementSetAuthority.captured(region)) throw new IllegalStateException("Bootstrap capture lost exact dependencies");
            replacementCaptures.put(capture.position, completed);
            host.removeTicket("rolling-pin");
            capture.versions.forEach(value -> replacementSetAuthority.retire(value.position()));
            encodedSnapshots.clear(); bootstrapCapture = null; bootstrapRegion++;
            if (bootstrapRegion < ReplacementWorkspaces.COUNT) return;
        }
        if (!bootstrapAdmissionChecked) {
            var receipt = DhAnalyticSetAdmission.assess(flatPolicy, workspaces, captures(), () -> {});
            if (!receipt.admitted()) throw new IllegalStateException("Durable replacement set is not representable: " + receipt.reason());
            bootstrapAdmissionChecked = true;
        }
        if (indexedScope != null) {
            if (bootstrapSeal == null) {
                List<NativeReplacementCapture> completeCaptures = captures();
                bootstrapSeal = journalTask(() -> replacementLedger.sealCaptures(completeCaptures));
                return;
            }
            if (!bootstrapSeal.isDone()) return;
            bootstrapSeal.join();
        }
        try { replacementSetAuthority.sealBootstrap(replacementLedger); }
        catch (java.io.IOException failure) { throw new CompletionException(failure); }
        bootstrapComplete = true;
        host.updateTicket("rolling-pin", pinCenter, 1, 1);
        beginStage(initialCenter, true);
    }

    private static int replacementMaterial(int stateId) {
        var state = NativeCubeCodec.registeredState(stateId);
        if (state == Blocks.AIR.defaultBlockState()) return NativeReplacementCapture.AIR;
        if (state == Blocks.STONE.defaultBlockState()) return NativeReplacementCapture.STONE;
        if (state == Blocks.DIORITE.defaultBlockState()) return NativeReplacementCapture.DIORITE;
        throw new IllegalArgumentException("Replacement supports only exact air, stone and diorite states");
    }

    private void beginStage(CubePos candidate, boolean initial) { beginStage(candidate, initial, true); }
    private void beginStage(CubePos candidate, boolean initial, boolean candidatePinned) {
        beginStage(candidate, initial, candidatePinned, pinCenter);
    }
    private void beginStage(CubePos candidate, boolean initial, boolean candidatePinned, CubePos candidateFocus) {
        // Defend every entry point before allocating any lease, ticket or candidate state.
        if (workspaces != null && candidatePinned && !workspaces.insideWindow(candidateFocus, candidate))
            throw new IllegalStateException("Two-workspace pin must be contained in the candidate moving27 targets");
        if (!candidatePinned && !hasCommittedReplacement() && !(initial && arena != null && arenaAuthority.prepare() != null))
            throw new IllegalStateException("Unpinned stage requires committed replacement authority");
        if(earthAdmission!=null && candidatePinned && !RollingCubePlan.adjacent(pinCenter,candidate))
            throw new IllegalStateException("Earth native pin must be inside the moving27 targets");
        if (stage != null) throw new IllegalStateException("Only one physical staged transition is allowed");
        RollingCubePlan plan = initial ? RollingCubePlan.stationary(candidateFocus, candidate, candidatePinned)
                : RollingCubePlan.create(candidateFocus, currentCenter, candidate, currentPinned, candidatePinned);
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
        StageMetadata metadata = new StageMetadata(world, epoch, generation, initial, candidateFocus,
                currentCenter, candidate, leases(currentVersions), nextLeases, added, retired,
                initial ? plan.currentTargets() : plan.candidateTargets(), currentPinned, candidatePinned, pinCenter);
        synchronized (admissionLock) {
            if (stopRequested) return;
            activeStageGeneration = generation; deliveredReadyGeneration = 0; activeCandidatePinned = candidatePinned;
            lifecycle = Lifecycle.STAGING;
        }
        if (candidatePinned && !currentPinned) host.updateTicket("rolling-pin", candidateFocus, 1, 1);
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
        if(s.metadata.initial()&&!earthBootstrapSnapshots.isEmpty()){
            for(int count=0;count<MAX_EVENTS_PER_POLL&&!earthBootstrapSnapshots.isEmpty()&&results.remainingCapacity()>0;count++)
                if(!results.offer(new EarthBootstrapSnapshot(s.metadata.generation(),earthBootstrapSnapshots.removeFirst())))throw new IllegalStateException("Earth bootstrap output bound");
            return;
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
            CubeVersion version = new CubeVersion(position, lease, snapshot.revision());
            if (replacementAuthority != null) replacementAuthority.observed(version);
            if (replacementSetAuthority != null) { verifyLedger(version); replacementSetAuthority.observed(version); }
            if (arenaLedger != null) try { arenaLedger.verifyObserved(version.position(),version.revision()); } catch(java.io.IOException failure) { throw new CompletionException(failure); }
            s.versions.put(position, version);
            publishSnapshot(s.metadata.generation(), snapshot); s.nextSnapshot++;
        }
        if (s.nextSnapshot != s.metadata.newPositions().size()) return;
        if (!s.readyPublished) {
            if (results.remainingCapacity() == 0) return;
            if(earthNativeEdits&&s.metadata.initial()&&!earthResumeBootstrap){
                // Reopened durable overlays are not trusted merely because their checksum is valid.
                // Reject an over-complex or foreign-material saved pin before the first READY, never
                // erase it, publish a fallback source, or allow entry to its collision presentation.
                requireEarthNativeDependencies(s.versions);
                var receipt=EarthNativeEditAdmission.assessCurrent(pinCenter,host::blockStateId);
                if(!receipt.admitted())throw new IllegalStateException("Saved Earth pin is not natively representable: "+receipt.reason());
                if(earthAdmission!=null){
                    earthCapture=captureEarth();earthPeakCaptureBytes=EarthReplacementCapture.CAPTURE_BYTES;
                    var far=assessEarth(earthCapture);if(!far.admitted())throw new IllegalStateException("Saved Earth replacement is not representable: "+far.reason());
                }
            }
            List<CubeVersion> manifest = s.metadata.candidateLeases().stream()
                    .map(lease -> Objects.requireNonNull(s.versions.get(lease.position()))).toList();
            s.certificate = replacementAuthority == null ? null : replacementAuthority.prepare(manifest);
            s.replacementSet = replacementSetAuthority == null ? null : replacementSetAuthority.prepare();
            s.arenaCertificate = arenaAuthority == null ? null : arenaAuthority.prepare();
            if (arena != null && s.arenaCertificate == null) throw new IllegalStateException("Complete arena authority unavailable");
            if (workspaces != null && s.replacementSet == null) throw new IllegalStateException("Complete durable replacement set unavailable");
            if (!results.offer(new StageReady(world, epoch, s.metadata.generation(), s.metadata.candidateCenter(), manifest,
                    s.certificate, s.metadata.candidatePinned(), workspaces == null ? null : s.metadata.candidateFocus(),
                    s.replacementSet, workspaces == null ? List.of() : captures(), s.arenaCertificate, arenaCapture, earthCapture)))
                throw new IllegalStateException("StageReady output reservation lost");
            s.readyPublished = true;
        }
        if (results.remainingCapacity() == 0) return;
        CommitCommand commit = commits.poll();
        if (commit == null) return;
        if (commit.generation() != s.metadata.generation() || !s.readyPublished)
            throw new IllegalStateException("Unexpected stale commit command");
        String preparedIdentity = s.arenaCertificate != null ? s.arenaCertificate.identity() : s.replacementSet != null ? s.replacementSet.identity() : s.certificate == null ? null : s.certificate.identity();
        if (commit.certificateIdentity() != null && (!commit.certificateIdentity().equals(preparedIdentity)
                || !commitReplacement(commit.certificateIdentity())))
            throw new IllegalStateException("Replacement certificate is not the exact prepared candidate");
        if ((!s.metadata.candidatePinned() || workspaces != null || arena != null || earthAdmission != null) && (commit.certificateIdentity() == null
                || !currentReplacement(commit.certificateIdentity())))
            throw new IllegalStateException("Pin/focus handoff lacks exact paired replacement proof");
        if (commit.certificateIdentity() == null) {
            if (replacementAuthority != null) replacementAuthority.revoke();
            synchronized (admissionLock) { desiredPinned = true; }
        }
        // Never remove the last old ticket or pin before the verified external paired ACK reaches here.
        if (currentTicket != null) host.removeTicket(currentTicket);
        if (currentPinned && !s.metadata.candidatePinned()) host.removeTicket("rolling-pin");
        if (currentPinned && s.metadata.candidatePinned() && !pinCenter.equals(s.metadata.candidateFocus())) {
            if (!residentAdjacentFocus(s.metadata.candidateFocus()))
                throw new IllegalStateException("Adjacent pin handoff lost its committed resident halos");
            host.updateTicket("rolling-pin", s.metadata.candidateFocus(), 1, 1);
        }
        currentPinned = s.metadata.candidatePinned(); pinCenter = s.metadata.candidateFocus();
        if (replacementAuthority != null) s.metadata.retiredPositions().forEach(replacementAuthority::retire);
        if (replacementSetAuthority != null) s.metadata.retiredPositions().forEach(replacementSetAuthority::retire);
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
        // Legacy NONE recovery consumes its publication ID. Earth refusals preserve the exact
        // committed certificate/capture; only an admitted durable mutation revokes it below.
        synchronized (admissionLock) {
            if(earthAdmission==null){desiredPinned = true;if (replacementAuthority != null) replacementAuthority.revoke();}
        }
        if (!world.equals(request.world())) { reject(EditStatus.WORLD_MISMATCH, -1, "World identity mismatch"); return; }
        if (epoch != request.epoch()) { reject(EditStatus.STALE_EPOCH, -1, "Stale epoch"); return; }
        if (arena == null ? !pinCenter.equals(request.position()) : !arena.contains(request.position())) { reject(EditStatus.UNKNOWN_CUBE, -1, "Only the active editable workspace is writable"); return; }
        CubeVersion version = currentVersions.get(request.position());
        if (version == null) { reject(EditStatus.NOT_READY, -1, "Active editable workspace is not committed"); return; }
        if (version.leaseGeneration() != request.leaseGeneration()) { reject(EditStatus.STALE_LEASE, -1, "Stale lease"); return; }
        try {
            if (!request.position().equals(CubePos.fromBlock(request.x(), request.y(), request.z()))) {
                reject(EditStatus.POSITION_MISMATCH, -1, "Block coordinate is outside the requested cube"); return;
            }
        } catch (ArithmeticException invalid) {
            reject(EditStatus.POSITION_MISMATCH, -1, "Block coordinate exceeds the cube address domain"); return;
        }
        if (arena == null ? !editPolicy.permits(pinCenter, request.x(), request.y(), request.z()) : !arena.editable(request.x(),request.y(),request.z())) {
            reject(EditStatus.IMMUTABLE_BOUNDARY, -1, "Block coordinate is excluded by the active workspace's edit policy"); return;
        }
        try { NativeCubeCodec.registeredState(request.stateId()); }
        catch (IllegalArgumentException invalid) { reject(EditStatus.INVALID_STATE, -1, "Unknown native state ID"); return; }
        var current = host.pollSnapshot(request.position(), epoch, request.leaseGeneration());
        if (current.status() != NativeCubicHost.Status.READY) {
            reject(EditStatus.NOT_READY, -1, "Authoritative cube is not ready"); return;
        }
        long revision = current.snapshot().revision();
        if (revision != request.expectedRevision()) { reject(EditStatus.STALE_REVISION, revision, "Expected revision differs from durable revision"); return; }
        if(earthNativeEdits){
            try{requireEarthNativeDependencies(currentVersions);}
            catch(IllegalStateException stale){reject(EditStatus.NOT_READY,revision,stale.getMessage());return;}
            final EarthNativeEditAdmission.Receipt receipt;
            try{receipt=EarthNativeEditAdmission.assessEdit(pinCenter,request.x(),request.y(),request.z(),request.stateId(),host::blockStateId);}
            catch(IllegalArgumentException unsupported){reject(EditStatus.UNSUPPORTED_NATIVE,revision,unsupported.getMessage());return;}
            if(!receipt.admitted()){reject(EditStatus.NATIVE_BUDGET,revision,receipt.reason());return;}
            if(earthAdmission!=null){
                try{
                    prospectiveEarth=Objects.requireNonNull(earthCapture).withMaterial(request.x(),request.y(),request.z(),earthMaterial(request.stateId()));
                    var far=assessEarth(prospectiveEarth);
                    if(!far.admitted()){prospectiveEarth=null;reject(EditStatus.REPLACEMENT_BUDGET,revision,far.reason());return;}
                }catch(IllegalArgumentException invalid){prospectiveEarth=null;reject(EditStatus.UNSUPPORTED_REPLACEMENT,revision,detail(invalid));return;}
            }
        }
        if(arena != null){
            var prior=arenaAuthority.committed();
            if(prior==null||!arenaAuthority.current(prior.identity())||prior.revision(request.position())!=revision||!arena.insideWindow(currentCenter)||!currentVersions.keySet().containsAll(arena.dependencies())){reject(EditStatus.NOT_READY,revision,"Exact committed arena and complete64 resident dependencies required");return;}
            final int material;try{material=replacementMaterial(request.stateId());}catch(IllegalArgumentException unsupported){reject(EditStatus.UNSUPPORTED_REPLACEMENT,revision,unsupported.getMessage());return;}
            prospectiveArena=arenaCapture.withMaterial(request.x(),request.y(),request.z(),material);
            final ArenaAdmission.Receipt receipt;
            try{receipt=Objects.requireNonNull(arenaAdmission.assess(prospectiveArena));receipt.verify(prospectiveArena,arenaSourceIdentity,arenaLayoutIdentity);}catch(RuntimeException invalid){prospectiveArena=null;reject(EditStatus.UNSUPPORTED_REPLACEMENT,revision,"Invalid exact arena census: "+detail(invalid));return;}
            if(!receipt.admitted()){prospectiveArena=null;reject(EditStatus.REPLACEMENT_BUDGET,revision,receipt.reason());return;}
            prospectivePriorIdentity=prior.identity();prospectiveContentIdentity=prospectiveArena.contentIdentity();
        }
        if (workspaces != null) {
            var prior = replacementSetAuthority.committed();
            if (prior == null || prior.region(request.position()).revision() != revision) {
                reject(EditStatus.NOT_READY, revision, "Exact committed replacement set is required before another edit"); return;
            }
            final int material;
            try { material = replacementMaterial(request.stateId()); }
            catch (IllegalArgumentException unsupported) {
                reject(EditStatus.UNSUPPORTED_REPLACEMENT, revision, unsupported.getMessage()); return;
            }
            for (CubePos position : workspaces.positions()) {
                var capture = replacementCaptures.get(position);
                prospectiveCaptures.put(position, capture.containsReadCell(request.x(), request.y(), request.z())
                        ? capture.withReadMaterial(request.x(), request.y(), request.z(), material) : capture);
            }
            List<NativeReplacementCapture> proposed = workspaces.positions().stream().map(prospectiveCaptures::get).toList();
            var receipt = DhAnalyticSetAdmission.assess(flatPolicy, workspaces, proposed, () -> {});
            if (!receipt.admitted()) {
                prospectiveCaptures.clear();
                reject(receipt.census() == null ? EditStatus.UNSUPPORTED_REPLACEMENT : EditStatus.REPLACEMENT_BUDGET,
                        revision, receipt.reason()); return;
            }
            // These immutable actor-owned bindings cannot be changed by a client boolean or stale ALLOW.
            prospectivePriorIdentity = prior.identity(); prospectiveContentIdentity = receipt.contentIdentity();
        }
        synchronized (admissionLock) {
            if (stopRequested) { reject(EditStatus.CLOSING, revision, "Actor is closing"); return; }
            try {
                desiredPinned = true;
                if (replacementAuthority != null) replacementAuthority.beforeEdit(request.position(), request.leaseGeneration());
                if(arenaAuthority != null){
                    if(!arenaAuthority.current(prospectivePriorIdentity))throw new IllegalStateException("Arena lost exact prior publication");
                    arenaAuthority.beforeEdit(request.position());journalConfirmed=false;ArenaCapture complete=prospectiveArena;
                    journal=journalTask(()->arenaLedger.begin(request.position(),revision,complete));
                } else if (replacementSetAuthority != null) {
                    if (!replacementSetAuthority.current(prospectivePriorIdentity))
                        throw new IllegalStateException("Prospective edit lost its exact prior replacement set");
                    replacementSetAuthority.beforeEdit(request.position());
                    journalConfirmed = false;
                    List<NativeReplacementCapture> completeProspective = workspaces.positions().stream().map(prospectiveCaptures::get).toList();
                    journal = journalTask(() -> replacementLedger.begin(request.position(), revision, completeProspective));
                } else {
                    saving = host.setBlockId(request.x(), request.y(), request.z(), request.stateId()); admittedEdits++;
                }
            }
            catch (RuntimeException problem) { reject(EditStatus.SAVE_FAILED, revision, detail(problem)); }
        }
    }

    private static int earthMaterial(int stateId){
        var state=NativeCubeCodec.registeredState(stateId);
        if(state==Blocks.AIR.defaultBlockState())return EarthReplacementCapture.AIR;
        if(state==Blocks.STONE.defaultBlockState())return EarthReplacementCapture.STONE;
        if(state==Blocks.DIORITE.defaultBlockState())return EarthReplacementCapture.DIORITE;
        if(state==Blocks.ANDESITE.defaultBlockState())return EarthReplacementCapture.ANDESITE;
        if(state==Blocks.TUFF.defaultBlockState())return EarthReplacementCapture.TUFF;
        if(state==Blocks.SNOW_BLOCK.defaultBlockState())return EarthReplacementCapture.SNOW_BLOCK;
        throw new IllegalArgumentException("Unsupported exact Earth replacement state");
    }
    private EarthReplacementCapture captureEarth(){
        try(var builder=new EarthReplacementCapture.Builder(pinCenter)){
            for(long y=pinCenter.minBlockY()-2;y<pinCenter.minBlockY()+18;y++)
                for(long z=pinCenter.minBlockZ()-2;z<pinCenter.minBlockZ()+18;z++)
                    for(long x=pinCenter.minBlockX()-2;x<pinCenter.minBlockX()+18;x++)builder.setNext(earthMaterial(host.blockStateId(x,y,z)));
            return builder.finish();
        }
    }
    private EarthReplacementAdmission.Receipt assessEarth(EarthReplacementCapture capture){
        int faces=EarthReplacementAdmission.faces(capture);
        if(faces>EarthReplacementAdmission.MAX_FACES)return new EarthReplacementAdmission.Receipt(false,"Exact Earth owned18 replacement exceeds8192 faces: "+faces,capture.contentIdentity(),earthSourceIdentity,earthLayoutIdentity);
        var receipt=Objects.requireNonNull(earthAdmission.assess(capture));receipt.verify(capture,earthSourceIdentity,earthLayoutIdentity);return receipt;
    }

    /** Actor-thread-only proof; retains no snapshots, decoded arrays or additional dependency map. */
    private void requireEarthNativeDependencies(Map<CubePos,CubeVersion> versions){
        for(int y=-1;y<=1;y++)for(int z=-1;z<=1;z++)for(int x=-1;x<=1;x++){
            CubePos position=pinCenter.offset(x,y,z);CubeVersion expected=versions.get(position);
            if(expected==null)throw new IllegalStateException("Earth native census requires all 27 committed pin dependencies");
            if(earthAdmission!=null && (replacementAuthority==null || !replacementAuthority.observes(expected)))
                throw new IllegalStateException("Earth replacement capture lease/revision is stale at "+position);
            var result=host.pollSnapshot(position,epoch,expected.leaseGeneration());
            if(result.status()!=NativeCubicHost.Status.READY||!world.equals(result.snapshot().world())
                    ||result.snapshot().epoch()!=epoch||!position.equals(result.snapshot().position())
                    ||result.snapshot().leaseGeneration()!=expected.leaseGeneration()||result.snapshot().revision()!=expected.revision())
                throw new IllegalStateException("Earth native census dependency is missing or stale at "+position);
        }
    }

    private void advanceEdit() {
        if (journal != null) {
            if (!journal.isDone()) return;
            CompletableFuture<Void> settled = journal; journal = null;
            try {
                settled.join();
                if (journalConfirmed) {
                    acknowledgement = new EditAck(editing, EditStatus.APPLIED, savedRevision, "Cube save and replacement journal are durable");
                } else {
                    CubeVersion exact = currentVersions.get(editing.position());
                    if (exact == null || exact.leaseGeneration() != editing.leaseGeneration() || exact.revision() != editing.expectedRevision()
                            || (arena == null ? !pinCenter.equals(editing.position()) || prospectiveCaptures.size() != ReplacementWorkspaces.COUNT
                                : !arena.contains(editing.position()) || prospectiveArena == null || !prospectiveArena.contentIdentity().equals(prospectiveContentIdentity)) || prospectiveContentIdentity == null)
                        throw new IllegalStateException("Prospective replacement admission lost its immutable transaction binding");
                    saving = host.setBlockId(editing.x(), editing.y(), editing.z(), editing.stateId()); admittedEdits++;
                }
            } catch (RuntimeException problem) { reject(EditStatus.SAVE_FAILED, -1, detail(problem)); }
        }
        if (saving != null) {
            if (!saving.isDone()) return;
            CompletableFuture<Long> settled = saving; saving = null;
            try {
                savedRevision = settled.join();
                if (arenaLedger != null) {
                    journalConfirmed=true;CubePos position=editing.position();long revision=savedRevision;journal=journalTask(()->arenaLedger.confirm(position,revision));return;
                }
                if (replacementLedger != null) {
                    journalConfirmed = true;
                    CubePos position = editing.position(); long revision = savedRevision;
                    journal = journalTask(() -> replacementLedger.confirm(position, revision)); return;
                }
                acknowledgement = new EditAck(editing, EditStatus.APPLIED, savedRevision, "Durable save completed");
            }
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
            if (replacementAuthority != null) {
                replacementAuthority.durableEdit(currentVersions.get(editing.position()));
                ReplacementCertificate certificate = replacementAuthority.prepare(currentVersions.values().stream()
                        .sorted(java.util.Comparator.comparing(CubeVersion::position)).toList());
                if(earthAdmission!=null){
                    if(prospectiveEarth==null)throw new IllegalStateException("Durable Earth edit lost its admitted capture");
                    if(prospectiveEarth!=earthCapture)earthPeakCaptureBytes=Math.max(earthPeakCaptureBytes,2L*EarthReplacementCapture.CAPTURE_BYTES);
                    earthCapture=prospectiveEarth.compact();prospectiveEarth=null;
                }
                acknowledgement = new EditAck(editing, acknowledgement.status(), acknowledgement.revision(),
                        acknowledgement.detail(), certificate, null, List.of(), null, null, earthCapture);
            }
            if(arenaAuthority != null){
                try{arenaAuthority.completeEdit(currentVersions.get(editing.position()),prospectiveArena);}catch(java.io.IOException failure){throw new CompletionException(failure);}
                if(prospectiveArena!=arenaCapture)arenaPeakCaptureBytes=Math.max(arenaPeakCaptureBytes,2L*ArenaCapture.PACKED_BYTES);
                arenaCapture=prospectiveArena.compact();prospectiveArena=null;
                var certificate=arenaAuthority.prepare();if(certificate==null)throw new IllegalStateException("Durable arena edit lacks complete authority");
                acknowledgement=new EditAck(editing,acknowledgement.status(),acknowledgement.revision(),acknowledgement.detail(),null,null,List.of(),certificate,arenaCapture);
            }
            if (replacementSetAuthority != null) {
                CubeVersion changedVersion = currentVersions.get(editing.position());
                verifyLedger(changedVersion);
                try {
                    replacementSetAuthority.completeEdit(changedVersion,
                            workspaces.positions().stream().map(prospectiveCaptures::get).toList(), replacementLedger);
                } catch (java.io.IOException failure) { throw new CompletionException(failure); }
                // Replace and release each overlay/base before allocating the next compact array.
                // The actor owns two retained arrays plus at most one new array at every step.
                for (CubePos position : workspaces.positions())
                    replacementCaptures.put(position, prospectiveCaptures.remove(position).compact());
                var replacement = replacementSetAuthority.prepare();
                if (replacement == null) throw new IllegalStateException("Durable edit did not produce complete two-region authority");
                acknowledgement = new EditAck(editing, acknowledgement.status(), acknowledgement.revision(),
                        acknowledgement.detail(), null, replacement, captures());
            }
        }
        if (!results.offer(acknowledgement)) return;
        editing = null; acknowledgement = null; replacementPublished = false;
        prospectiveCaptures.clear(); prospectiveArena = null; prospectiveEarth = null; prospectivePriorIdentity = prospectiveContentIdentity = null; journalConfirmed = false;
        // Admission remains occupied until the acknowledgement is consumed.
    }
    private void reject(EditStatus status, long revision, String message) {
        if (status == EditStatus.SAVE_FAILED && (replacementSetAuthority != null || arenaAuthority != null || earthAdmission != null)) {
            replacementSaveFailed = true; if(replacementAuthority != null)replacementAuthority.revoke(); if(replacementSetAuthority != null) replacementSetAuthority.revoke(); if(arenaAuthority != null) arenaAuthority.revoke();
        }
        acknowledgement = new EditAck(editing, status, revision, message);
    }
    @FunctionalInterface private interface JournalAction { void run() throws java.io.IOException; }
    private CompletableFuture<Void> journalTask(JournalAction action) {
        return CompletableFuture.runAsync(() -> {
            try { action.run(); } catch (java.io.IOException failure) { throw new CompletionException(failure); }
        }, bootstrap);
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
            synchronized (admissionLock) { latestGoal = null; requestedFocus = null; requestedFocusIdentity = null;
                commits.clear(); pendingCommit = false; deliveredReadyGeneration = 0; }
            EditRequest queued = edits.poll();
            if (queued != null) results.offer(new EditAck(queued, EditStatus.CLOSING, -1, "Actor closed before edit admission"));
        }
        if (failure != null && !failurePublished) {
            if (results.remainingCapacity() == 0) results.poll();
            results.offer(new Failure(detail(failure))); failurePublished = true;
        }
        if (host == null) { finish(); return; }
        if (!hostClosing) {
            // The initial paged summary seal owns the same store lock until its disk barrier settles.
            if (bootstrapSeal != null) {
                if (!bootstrapSeal.isDone()) return;
                try { bootstrapSeal.join(); }
                catch (RuntimeException problem) { if (failure == null) failure = unwrap(problem); }
                bootstrapSeal = null;
            }
            if (failure == null) {
                advanceEdit();
                if (saving != null || journal != null || acknowledgement != null) return;
            }
            // A failed unrelated owner step cannot abandon an in-flight journal writer or its lock.
            if (journal != null) { if (!journal.isDone()) return; journal = null; }
            if (replacementAuthority != null) replacementAuthority.revoke();
            if (replacementSetAuthority != null) replacementSetAuthority.revoke();
            if(arenaAuthority != null)arenaAuthority.revoke();arenaCapture=null;prospectiveArena=null;arenaBuilder=null;arenaBootstrapVersions.clear();
            host.beginClose(); hostClosing = true;
            stage = null; currentVersions = Map.of(); encodedSnapshots.clear();
            earthBootstrapSnapshots.clear();earthBootstrapVersions.clear();earthBootstrapLeases=null;
            replacementCaptures.clear(); bootstrapCapture = null; prospectiveCaptures.clear();earthCapture=null;prospectiveEarth=null;
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
        // A conservative original27 charge spans the actor lifetime, including all queued aliases.
        earthBootstrapSnapshots.clear();earthBootstrapVersions.clear();earthBootstrapLeases=null;
        if(earthBootstrapReservation!=null)earthBootstrapReservation.releaseAfterPhysicalDrain();
        Closed closed = new Closed(failure == null, failure == null ? "Rolling native host drained and closed" : detail(failure));
        results.offer(closed); lifecycle = Lifecycle.CLOSED; completion.publish(closed);
    }
    private void publishCounters() {
        earthCounters=new EarthDiagnostics(earthCapture==null?0:earthCapture.declaredBytes(),earthPeakCaptureBytes,
                earthAdmission==null?0:replacementAuthority==null?0:replacementAuthority.issued()==null?0:ReplacementCertificate.DEPENDENCIES);
        arenaCounters=new ArenaDiagnostics(arenaBootstrapPeakOwners,arenaBootstrapPeakCaches,
                arenaCapture!=null||arenaBuilder!=null?ArenaCapture.PACKED_BYTES:0,arenaPeakCaptureBytes,
                arenaCapture!=null?64:arenaBootstrapVersions.size());
        var stats = host == null ? null : host.stats();
        counters = new Counters(committedGeneration, stage == null ? 0 : stage.metadata.generation(),
                currentCenter, stage == null ? null : stage.metadata.candidateCenter(),
                stats == null ? 0 : stats.residentCubes(), stats == null ? 0 : stats.readyCubes(),
                stats == null ? 0 : stats.tickets(), encodedSnapshots.size()+earthBootstrapSnapshots.size(),
                currentVersions.size() + (stage == null ? 0 : stage.versions.size()),
                stats == null ? 0 : stats.loader().running(), stats == null ? 0 : stats.loader().queued(),
                stats == null ? 0 : stats.loader().cancelled(), stage == null ? 0 : stage.nextSnapshot,
                publishedSnapshots, admittedEdits, currentPinned, stage == null ? currentPinned : stage.metadata.candidatePinned());
    }
    private record Counters(long committed, long staged, CubePos current, CubePos candidate,
                            int residents, int ready, int tickets, int encoded, int versions,
                            int running, int queued, long cancelled, int stageSnapshots, long published, long edits,
                            boolean currentPinned, boolean candidatePinned) {}
    private record CommitCommand(long generation, String certificateIdentity) {}
    private static final class Stage {
        private final StageMetadata metadata;
        private final String ticket;
        private final Map<CubePos, CubeVersion> versions;
        private final Map<CubePos, Long> leaseByPosition;
        private int nextSnapshot;
        private boolean metadataPublished, readyPublished;
        private ReplacementCertificate certificate;
        private ReplacementSetCertificate replacementSet;
        private ArenaCertificate arenaCertificate;
        private Stage(StageMetadata metadata, String ticket, Map<CubePos, CubeVersion> versions) {
            this.metadata = metadata; this.ticket = ticket; this.versions = versions;
            Map<CubePos, Long> leases = new HashMap<>();
            metadata.candidateLeases().forEach(lease -> leases.put(lease.position(), lease.leaseGeneration()));
            leaseByPosition = Map.copyOf(leases);
        }
    }
    private static final class BootstrapCapture {
        private final CubePos position;
        private final List<CubeLease> leases;
        private final List<CubeVersion> versions = new ArrayList<>(ReplacementCertificate.DEPENDENCIES);
        private final long minX, minY, minZ;
        private final byte[] values;
        private int nextVersion, cursor;
        private BootstrapCapture(CubePos position, List<CubeLease> leases) {
            this.position = position; this.leases = leases;
            minX = position.minBlockX() - 2; minZ = position.minBlockZ() - 2;
            minY = Math.max(position.minBlockY() - 1, RollingCubePlan.GAMEPLAY_MIN_Y) - 1;
            long maxY = Math.min(position.minBlockY() + 17, RollingCubePlan.GAMEPLAY_MAX_Y_EXCLUSIVE) + 1;
            values = new byte[Math.toIntExact(20 * (maxY - minY) * 20)];
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
