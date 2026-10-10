package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.CubeLease;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.EditStatus;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.StageMetadata;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor.StageReady;
import io.earthplatform.minecraft.enginebridge.RollingCubePlan;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.scene.SceneCube;
import io.earthplatform.minecraft.scene.SceneRenderEnvelope;
import io.earthplatform.streaming.CubeStreamAdmission;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * CPU-only owner-thread rolling client. One admission gate survives every stage and commit, so
 * retained leases/revisions remain valid and re-entry receives a fresh generation. CPU data for
 * new coordinates is staged in the bounded union; current coordinates remain intact until the
 * caller has proved native/DH publication and GPU retirement and calls {@link #commitStage}.
 * No cube is synthesized for unknown data, and receiving all snapshots alone is never readiness.
 */
public final class RollingGateReplica implements AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private final WorldIdentity world;
    private final long epoch;
    private final EnginePaletteContext palette;
    private final CubePos pinCenter;
    private final CubeStreamAdmission admission;
    private final Map<CubePos, SceneCube> cubes = new LinkedHashMap<>();
    private final Map<CubePos, SceneCube> cubeView = Collections.unmodifiableMap(cubes);
    private List<CubeLease> currentLeases = List.of();
    private CubePos currentCenter;
    private long committedGeneration, lastLease, barrierBase = -1, ackRevision = -1;
    private StageMetadata staged;
    private StageReady ready, currentReady;
    private boolean paused, replacementAccepted, awaitingCommittedReady, durabilityFailed, closed;

    public RollingGateReplica(WorldIdentity world, long epoch, EnginePaletteContext palette, CoreGateProfile profile) {
        this.world = Objects.requireNonNull(world); this.palette = Objects.requireNonNull(palette);
        if (epoch < 0) throw new IllegalArgumentException("Nonnegative replica epoch required");
        this.epoch = epoch; pinCenter = Objects.requireNonNull(profile).center(); currentCenter = pinCenter;
        admission = new CubeStreamAdmission(world, epoch, RollingCubePlan.MAX_STAGED_OWNERS);
    }

    public void acceptMetadata(StageMetadata metadata) {
        check(); Objects.requireNonNull(metadata); RollingGateWire.validateMetadata(metadata);
        if (paused || awaitingCommittedReady || staged != null) throw new IllegalStateException("One stage or edit barrier at a time");
        if (!world.equals(metadata.world()) || epoch != metadata.epoch() || !pinCenter.equals(metadata.pinCenter())
                || metadata.generation() != Math.incrementExact(committedGeneration)
                || metadata.initial() != (committedGeneration == 0) || !currentCenter.equals(metadata.currentCenter())
                || !metadata.currentLeases().equals(currentLeases))
            throw new IllegalArgumentException("Stale or mismatched rolling metadata");
        TreeSet<CubePos> union = new TreeSet<>();
        for (CubeLease lease : currentLeases) union.add(lease.position());
        for (CubeLease lease : metadata.candidateLeases()) union.add(lease.position());
        Map<CubePos, Long> expected = new LinkedHashMap<>();
        for (CubeLease lease : currentLeases) expected.put(lease.position(), lease.leaseGeneration());
        long next = lastLease;
        // Validate authoritative additions before replaceInterest consumes any lease generations.
        for (CubePos p : union) if (!expected.containsKey(p)) expected.put(p, next = Math.incrementExact(next));
        for (CubeLease lease : metadata.candidateLeases()) if (expected.get(lease.position()).longValue() != lease.leaseGeneration())
            throw new IllegalArgumentException("Noncanonical authoritative lease generation");
        for (CubeLease lease : currentLeases) {
            SceneCube cube = cubes.get(lease.position());
            if (cube == null || !admission.isCurrentLoaded(header(cube.snapshot())))
                throw new IllegalStateException("Current cube data is incomplete or no longer admitted");
        }
        var changed = admission.replaceInterest(union);
        if (!changed.retired().isEmpty()) throw new IllegalStateException("Stage must retain all current leases");
        lastLease = next; staged = metadata; ready = null;
    }

    public void acceptSnapshot(long generation, EngineCubeSnapshot snapshot) {
        check(); Objects.requireNonNull(snapshot);
        if (durabilityFailed) throw new IllegalStateException("Failed durability requires closing this replica");
        if (staged != null) {
            if (generation != staged.generation() || ready != null || !staged.newPositions().contains(snapshot.position()))
                throw new IllegalArgumentException("Snapshot does not belong to new coordinates of this open stage");
            if (cubes.containsKey(snapshot.position())) throw new IllegalArgumentException("Duplicate staged snapshot");
        } else {
            if (!paused || generation != committedGeneration || !snapshot.position().equals(pinCenter)
                    || replacementAccepted || snapshot.revision() != Math.incrementExact(barrierBase))
                throw new IllegalArgumentException("Snapshot lacks its exact durable single-edit barrier");
        }
        var accepted = admission.admit(header(snapshot));
        if (!accepted.accepted()) throw new IllegalArgumentException("Rolling cube rejected: " + accepted.rejection());
        final SceneCube cube;
        try {
            // Rolling windows use the approved full-height dependency padding even for BASE.
            cube = new SceneCube(snapshot, palette, SceneRenderEnvelope.PLAYER_DIAGNOSTIC_PADDING);
            if (paused) verifyImmutableShell(cubes.get(pinCenter), cube);
        } catch (RuntimeException failure) { admission.cancel(accepted.token()); throw failure; }
        if (admission.commit(accepted.token(), () -> cubes.put(snapshot.position(), cube)) != CubeStreamAdmission.Rejection.NONE)
            throw new IllegalStateException("Rolling cube commit rejected");
        if (paused) replacementAccepted = true;
    }

    /** A post-edit committed ready is accepted once, only after its recoverable edit ACK. */
    public void acceptReady(StageReady supplied) {
        check(); Objects.requireNonNull(supplied); RollingGateWire.validateReady(supplied);
        if (paused) throw new IllegalStateException("Durability acknowledgement must precede ready");
        if (staged != null) {
            if (ready != null) throw new IllegalArgumentException("Replayed stage readiness");
            verifyManifest(supplied, staged.generation(), staged.candidateCenter(), staged.candidateLeases());
            ready = supplied;
        } else {
            if (!awaitingCommittedReady) throw new IllegalStateException("No committed edit is awaiting readiness");
            verifyManifest(supplied, committedGeneration, currentCenter, currentLeases);
            currentReady = supplied; awaitingCommittedReady = false;
        }
    }

    /**
     * The caller must first prove old GPU generations and dependency references are retired.
     * This operation alone does not publish any mesh or distant-LOD pair.
     */
    public void commitStage(long generation) {
        check();
        if (staged == null || ready == null || generation != staged.generation())
            throw new IllegalStateException("Exact ready stage required before retirement");
        verifyManifest(ready, staged.generation(), staged.candidateCenter(), staged.candidateLeases());
        List<CubePos> wanted = staged.candidateLeases().stream().map(CubeLease::position).toList();
        var retired = admission.replaceInterest(wanted);
        if (!retired.requested().isEmpty()) throw new IllegalStateException("Commit cannot allocate new leases");
        for (var lease : retired.retired()) cubes.remove(lease.lease().position());
        currentLeases = staged.candidateLeases(); currentCenter = staged.candidateCenter(); committedGeneration = generation;
        currentReady = ready; staged = null; ready = null;
    }

    public void beginEdit() {
        check();
        if (paused || staged != null || awaitingCommittedReady || committedGeneration == 0 || cubes.size() != currentLeases.size())
            throw new IllegalStateException("Edit requires complete committed data without a stage");
        barrierBase = revision();
        if (barrierBase < 0) throw new IllegalStateException("Original editable cube is unknown");
        replacementAccepted = false; paused = true; currentReady = null;
    }

    public void acknowledge(String status, long revision) {
        check(); if (!paused) throw new IllegalStateException("Acknowledgement without edit barrier");
        if (durabilityFailed) throw new IllegalStateException("Failed durability requires closing this replica");
        EditStatus result = EditStatus.valueOf(status);
        if (result == EditStatus.SAVE_FAILED) {
            durabilityFailed = true;
            throw new IllegalStateException("Failed durability leaves replica fenced");
        }
        if (result == EditStatus.APPLIED) {
            if (!replacementAccepted || revision != Math.incrementExact(barrierBase) || revision() != revision)
                throw new IllegalStateException("Durable acknowledgement precedes its exact replacement snapshot");
            ackRevision = revision;
        } else if (replacementAccepted) throw new IllegalStateException("Replacement snapshot lacks successful durability acknowledgement");
        paused = false; currentReady = null; awaitingCommittedReady = true;
    }

    private void verifyManifest(StageReady supplied, long generation, CubePos center, List<CubeLease> leases) {
        if (!world.equals(supplied.world()) || epoch != supplied.epoch() || generation != supplied.generation()
                || !center.equals(supplied.candidateCenter()) || supplied.versions().size() != leases.size())
            throw new IllegalArgumentException("Ready identity or complete manifest mismatch");
        for (int i = 0; i < leases.size(); i++) {
            CubeLease lease = leases.get(i); var version = supplied.versions().get(i);
            SceneCube cube = cubes.get(lease.position());
            if (!lease.position().equals(version.position()) || lease.leaseGeneration() != version.leaseGeneration()
                    || cube == null || cube.snapshot().revision() != version.revision()
                    || !admission.isCurrentLoaded(header(cube.snapshot())))
                throw new IllegalArgumentException("Ready manifest lacks an exact admitted snapshot");
        }
    }

    private static void verifyImmutableShell(SceneCube old, SceneCube replacement) {
        if (old == null) throw new IllegalStateException("Editable cube is unknown");
        int changes = 0;
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            if (old.state(x, y, z) == replacement.state(x, y, z)) continue;
            if (x == 0 || y == 0 || z == 0 || x == 15 || y == 15 || z == 15)
                throw new IllegalArgumentException("Original editable cube boundary shell is immutable");
            if (++changes > 1) throw new IllegalArgumentException("Only one interior block may change per edit barrier");
        }
    }

    private static CubeStreamAdmission.Header header(EngineCubeSnapshot snapshot) {
        return new CubeStreamAdmission.Header(snapshot.world(), snapshot.epoch(), snapshot.position(), snapshot.leaseGeneration(), snapshot.revision());
    }
    public CubeStreamAdmission admission() { check(); return admission; }
    /** Read-only CPU dependency union, not permission to render or collide against staged data. */
    public Map<CubePos, SceneCube> cubes() { check(); return cubeView; }
    public StageMetadata stagedMetadata() { check(); return staged; }
    public boolean stageReady() { check(); return ready != null; }
    public CubePos currentCenter() { check(); return currentCenter; }
    public long currentActorGeneration() { check(); return committedGeneration; }
    public boolean paused() { check(); return paused; }
    public boolean awaitingCommittedReady() { check(); return awaitingCommittedReady; }
    public long acknowledgedRevision() { check(); return ackRevision; }
    public long revision() { check(); var cube = cubes.get(pinCenter); return cube == null ? -1 : cube.snapshot().revision(); }
    public List<CubePos> currentPositions125() { check(); return currentPlan().currentOwners(); }
    public List<CubePos> currentTargets() { check(); return currentPlan().committedTargets(); }
    public List<CubePos> candidatePositions125() { check(); return staged == null ? currentPositions125() : candidatePlan().currentOwners(); }
    public List<CubePos> candidateTargets() { check(); return staged == null ? currentTargets() : candidatePlan().committedTargets(); }
    public String candidateManifestDigest() { check(); return digest(staged == null ? currentReady : ready); }
    public String currentManifestDigest() { check(); return digest(currentReady); }
    private static String digest(StageReady manifest) {
        if (manifest == null) throw new IllegalStateException("No exact ready manifest");
        return RollingGateWire.manifestDigest(manifest);
    }
    private RollingCubePlan currentPlan() { return RollingCubePlan.stationary(pinCenter, currentCenter); }
    private RollingCubePlan candidatePlan() { return RollingCubePlan.stationary(pinCenter, staged.candidateCenter()); }
    private void check() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Rolling replica owner thread required");
        if (closed) throw new IllegalStateException("Rolling replica closed");
    }
    @Override public void close() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Rolling replica owner thread required");
        if (closed) return;
        closed = true; paused = true; admission.close(); cubes.clear(); currentLeases = List.of();
        staged = null; ready = null; currentReady = null; awaitingCommittedReady = false;
    }
}
