package io.earthplatform.minecraft.survival.render;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.CancellationToken;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.survival.interaction.NativeWholeAction;
import io.earthplatform.minecraft.scene.SceneCube;
import io.earthplatform.minecraft.network.EngineCubeSection;
import io.earthplatform.minecraft.survival.persistence.NativeSurvivalCheckpoint;
import io.earthplatform.minecraft.survival.persistence.SurvivalCheckpointCoordinator;
import io.earthplatform.minecraft.survival.source.MarinVisualCubeSource;
import io.earthplatform.minecraft.survival.world.CubeSnapshot;
import io.earthplatform.minecraft.survival.world.NativeSurvivalWorld;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Complete durable + currently owned render overrides, plus a separately owner-minted content
 * currentness lease. Content/PlanInput alone have no publication authority. Historical frame latches
 * prove currentness only at one CPU boundary; resources, compilation, GPU/DH and gameplay readiness
 * require independent joins and are deliberately not certified here.
 * AIR is a present value. Absence always requires the exact certified visual source.
 *
 * Captures conservatively retain all owned cubes, even unchanged generated cubes. Consequently
 * adding such an override can change representation identity although source-resolved blocks
 * happen to be equivalent. No unproved global source/overlay equivalence is claimed.
 *
 * SOURCE accounts retained immutable payload aliases; OWNER_METADATA accounts copied indexes,
 * provenance, canonicalization scratch and every handle. Planning uses the existing PLAN pool.
 * These are conservative logical admissions, not measured JVM heap. No cap is increased.
 * Handles expose scalar reads rather than escapable payloads/maps/sets. Retain a handle for each
 * consumer; the last close drops the physical aliases before releasing the shared reservation.
 */
public final class SurvivalCompleteOverlay {
    private static final long BASE_METADATA = 8192, ENTRY_METADATA = 1024, HANDLE_METADATA = 256;
    private SurvivalCompleteOverlay() {}

    /** Caller chooses a bound within the existing pools; count admission precedes any copied index. */
    public record Limits(int maxCubes, long maxPayloadBytes, long maxMetadataBytes) {
        public Limits {
            if (maxCubes < 0 || maxCubes > 65536 || maxPayloadBytes < 1
                    || maxPayloadBytes > NativeDistanceCpuBudget.Pool.SOURCE.limit()
                    || maxMetadataBytes < BASE_METADATA
                    || maxMetadataBytes > NativeDistanceCpuBudget.Pool.OWNER_METADATA.limit())
                throw new IllegalArgumentException("Explicit bounded complete-overlay admission required");
        }
    }

    /** Keep this capability on the simulation owner thread. Workers receive only Content handles. */
    public static final class CaptureOwner implements AutoCloseable {
        private final NativeSurvivalWorld world;
        private final NativeWholeAction.Owner actions;
        private final SurvivalCheckpointCoordinator checkpoints;
        private final MarinVisualCubeSource source;
        private final io.earthplatform.minecraft.survival.runtime.InstalledMarinSessionAdmission.SceneCompatibility installedCompatibility;
        private final NativeDistanceCpuBudget budget;
        private final Limits limits;
        private final Object ownerKey = new Object();
        private final UUID stream = UUID.randomUUID();
        private final NativeSurvivalCheckpoint.Binding binding;
        private long captureSequence;
        private final NativeSurvivalWorld.RenderPublicationFence publicationFence;
        private final AtomicBoolean retired = new AtomicBoolean();

        public CaptureOwner(NativeSurvivalWorld world, NativeWholeAction.Owner actions,
                SurvivalCheckpointCoordinator checkpoints, MarinVisualCubeSource source,
                NativeDistanceCpuBudget budget, Limits limits) {
            this.installedCompatibility = null;
            this.world = Objects.requireNonNull(world); this.actions = Objects.requireNonNull(actions);
            this.checkpoints = Objects.requireNonNull(checkpoints); this.source = Objects.requireNonNull(source);
            this.budget = Objects.requireNonNull(budget); this.limits = Objects.requireNonNull(limits);
            world.checkOwner(); publicationFence = world.renderPublicationFence();
            binding = checkpoints.binding(); requirePublishable();
        }
        /** Separate installed policy; only its privately minted exact-source witness qualifies this route. */
        public CaptureOwner(NativeSurvivalWorld world, NativeWholeAction.Owner actions,
                SurvivalCheckpointCoordinator checkpoints, MarinVisualCubeSource source,
                NativeDistanceCpuBudget budget, Limits limits,
                io.earthplatform.minecraft.survival.runtime.InstalledMarinSessionAdmission.SceneCompatibility compatibility) {
            this.installedCompatibility=Objects.requireNonNull(compatibility);
            this.world=Objects.requireNonNull(world); this.actions=Objects.requireNonNull(actions);
            this.checkpoints=Objects.requireNonNull(checkpoints); this.source=Objects.requireNonNull(source);
            this.budget=Objects.requireNonNull(budget); this.limits=Objects.requireNonNull(limits);
            world.checkOwner(); publicationFence=world.renderPublicationFence();
            binding=checkpoints.binding(); requirePublishable();
        }
        private void requirePublishable() {
            world.checkOwner();
            if (retired.get()) throw new SurvivalRefusal("Complete overlay capture owner was retired");
            if (actions.world() != world || checkpoints.actions() != actions || actions.hasCurrentAction()
                    || actions.fenced() || world.hasActiveTransaction() || NativeWholeAction.hasCurrentOrOpeningAction())
                throw new SurvivalRefusal("Complete overlay requires its exact idle unfenced whole-action owner");
            if (!binding.equals(checkpoints.binding()) || !binding.world().equals(world.world())
                    || !binding.registryIdentity().equals(source.palette().identity())
                    || !world.world().blockPaletteVersion().equals(source.palette().identity()))
                throw new SurvivalRefusal("Complete overlay source, checkpoint or native registry binding changed");
            if (installedCompatibility == null) {
                if (!binding.sourceIdentity().equals(source.definition().identity()))
                    throw new SurvivalRefusal("Complete terrain overlay source identity changed");
            } else installedCompatibility.require(world,actions,checkpoints,source);
        }
        public Content capture() { return capture(() -> {}); }

        /** Package-private adversarial test seam; production capture has no caller callback. */
        Content capture(Runnable beforeValidation) {
            requirePublishable(); Objects.requireNonNull(beforeValidation);
            Map<CubePos, NativeSurvivalCheckpoint.Cube> durable = checkpoints.completeDurableCubes();
            int ownedCount = world.ownedCount();
            // Conservative sum admits both copied provenance and the merged index before either copy.
            long entries = Math.addExact((long) ownedCount, durable.size());
            long metadata = Math.addExact(BASE_METADATA, Math.multiplyExact(entries, ENTRY_METADATA));
            long payload = world.usage().currentCubePayloadBytes();
            for (var cube : durable.values()) payload = Math.addExact(payload, cube.blocks().payloadBytes());
            if (entries > limits.maxCubes() || Math.addExact(metadata, HANDLE_METADATA) > limits.maxMetadataBytes()
                    || payload > limits.maxPayloadBytes())
                throw new SurvivalRefusal("Complete overlay exceeds admission before copying or hashing");
            var metadataCharge = budget.reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA, metadata);
            NativeDistanceCpuBudget.Reservation payloadCharge = null;
            Content result = null;
            try {
                payloadCharge = budget.reserve(NativeDistanceCpuBudget.Pool.SOURCE, Math.max(1, payload));
                long next = Math.incrementExact(captureSequence);
                var overlays = new TreeMap<CubePos, ImmutableCube>();
                var owned = new TreeMap<CubePos, CubeSnapshot>();
                for (var entry : durable.entrySet()) {
                    source.validate(entry.getKey()); source.palette().validate(entry.getValue().blocks());
                    overlays.put(entry.getKey(), entry.getValue().blocks());
                }
                // ownedPositions() is itself a copy, deliberately after both reservations.
                for (CubePos position : world.ownedPositions()) {
                    CubeSnapshot live = world.snapshot(position);
                    lineage(position, live, durable.get(position));
                    source.validate(position); source.palette().validate(live.blocks());
                    owned.put(position, live); overlays.put(position, live.blocks());
                }
                var shared = new Shared(ownerKey, world.world(), world.ownerEpoch(), stream, next,
                        world.commitSequence(), world.terrainContentSequence(), source, binding, overlays, owned,
                        budget, metadataCharge, payloadCharge);
                result = shared.open();
                beforeValidation.run();
                validateCurrent(result);
                captureSequence = next;
                return result;
            } catch (RuntimeException | Error failure) {
                if (result != null) result.close();
                else {
                    if (payloadCharge != null) payloadCharge.releaseAfterPhysicalDrain();
                    metadataCharge.releaseAfterPhysicalDrain();
                }
                throw failure;
            }
        }

        /**
         * Read-only owner-thread seam immediately before the host's separately proved publication.
         * Counter equality alone never succeeds: membership, incarnation, revisions, lineage and
         * every effective override block are checked. Effects, residency and matching acknowledgements
         * may progress. A fresh/recovered capability cannot validate an earlier capability's handle.
         */
        public void validateCurrent(Content content) {
            requirePublishable(); Objects.requireNonNull(content);
            synchronized (content.shared) {
                Shared value = content.requireOpen();
                if (value.ownerKey != ownerKey || !value.world.equals(world.world())
                        || !value.ownerEpoch.equals(world.ownerEpoch()) || !value.binding.equals(binding)
                        || value.source != source || !value.sourceIdentity.equals(source.identity()))
                    throw new SurvivalRefusal("Foreign source, capture lease or recovery epoch");
                if (value.terrainSequence != world.terrainContentSequence() || value.owned.size() != world.ownedCount())
                    throw new SurvivalRefusal("Complete overlay terrain or ownership changed");
                Map<CubePos, NativeSurvivalCheckpoint.Cube> durable = checkpoints.completeDurableCubes();
                long count = durable.size();
                for (var entry : value.owned.entrySet()) {
                    CubePos position = entry.getKey();
                    if (world.presence(position) == NativeSurvivalWorld.Presence.UNKNOWN)
                        throw new SurvivalRefusal("Complete overlay lost cube ownership");
                    CubeSnapshot live = world.snapshot(position), before = entry.getValue();
                    if (!before.stamp().equals(live.stamp()) || !sameBlocks(before.blocks(), live.blocks()))
                        throw new SurvivalRefusal("Complete overlay cube changed or was released/re-admitted");
                    lineage(position, live, durable.get(position));
                    source.palette().validate(live.blocks());
                    if (!durable.containsKey(position)) count++;
                }
                if (count != value.overlays.size()) throw new SurvivalRefusal("Complete overlay membership changed");
                for (var entry : durable.entrySet()) {
                    ImmutableCube captured = value.overlays.get(entry.getKey());
                    if (captured == null) throw new SurvivalRefusal("Complete overlay gained an unrepresented durable cube");
                    // Exact live values already checked above overlay a matching, potentially older durable image.
                    if (!value.owned.containsKey(entry.getKey())) {
                        if (!sameBlocks(captured, entry.getValue().blocks()))
                            throw new SurvivalRefusal("Unloaded durable override changed");
                        source.palette().validate(captured);
                    }
                }
                requirePublishable();
            }
        }
        public void validateCurrent(PlanInput plan) {
            Objects.requireNonNull(plan);
            synchronized (plan) { plan.requireOpen(); validateCurrent(plan.content); }
        }

        /**
         * Mint only after exact owner validation. This may rebind the SAME immutable geometry after
         * an effect-only/aborted action or matching durable acknowledgement. Equal hashes, newly
         * constructed equivalent plans, changed cube incarnations and recovered owners never suffice.
         * Transfer the returned lease using an actual Java safe-publication mechanism.
         */
        public CurrentLease authorize(PlanInput plan) {
            requirePublishable(); Objects.requireNonNull(plan);
            var charge = budget.reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA, CurrentLease.METADATA_BYTES);
            PlanInput retained = null;
            try {
                // Capture BEFORE validation: any concurrent lifecycle retirement during or after
                // validation permanently poisons this stamp, never issuing a post-retirement stamp.
                var stamp = publicationFence.captureStamp();
                retained = plan.retain();
                validateCurrent(retained);
                if (retired.get()) throw new SurvivalRefusal("Complete overlay owner retired during authorization");
                return new CurrentLease(retained, stamp, retired, budget, charge);
            } catch (RuntimeException | Error failure) {
                if (retained != null) retained.close();
                charge.releaseAfterPhysicalDrain();
                throw failure;
            }
        }

        /**
         * Thread-safe irreversible lifecycle HEAD fence. Call before source retirement, leave or
         * disconnect changes ownership. It touches no mutable world/action/checkpoint object and
         * never waits for a render operation. Resources must be invalidated separately before reuse.
         */
        public void requestInvalidation() {
            if (retired.compareAndSet(false, true)) publicationFence.revoke();
        }
        @Override public void close() { requestInvalidation(); }
    }


    /**
     * Owner-minted CURRENT CONTENT only. There is no caller boolean, callback, hash or CPU/GPU
     * completion that can construct this capability. It is safe to hand to another thread; mutable
     * simulation state is never called there. Retained aliases and every accepted frame are charged
     * in OWNER_METADATA. Session closure and world mutation monotonically revoke future latches.
     */
    public static final class CurrentLease implements AutoCloseable {
        private static final long METADATA_BYTES = 2048;
        private PlanInput plan;
        private final NativeSurvivalWorld.RenderPublicationStamp stamp;
        private final AtomicBoolean retired;
        private final NativeDistanceCpuBudget budget;
        private final NativeDistanceCpuBudget.Reservation charge;
        private boolean closed;
        private CurrentLease(PlanInput plan, NativeSurvivalWorld.RenderPublicationStamp stamp,
                AtomicBoolean retired, NativeDistanceCpuBudget budget, NativeDistanceCpuBudget.Reservation charge) {
            this.plan = plan; this.stamp = stamp; this.retired = retired; this.budget = budget; this.charge = charge;
        }
        private void requireOpen() {
            if (closed || retired.get()) throw new SurvivalRefusal("Current content lease is closed or retired");
        }
        /**
         * A bounded CPU latch, never a lock/permit held over publication, GL, callbacks or a frame.
         * The exact retained PlanData identity is required; no equality/hash substitute is accepted.
         * A mutation after the CAS makes this accepted immutable frame historical immediately.
         * This does not prove physical mesh completion, DH complement, camera, resource validity,
         * collision or gameplay readiness. Actual resource retirement may forbid drawing it at all.
         */
        public synchronized FrameLatch latch(PlanInput exactGeometry) {
            FrameLatch result = tryLatchCurrent(exactGeometry);
            if (result == null) throw new SurvivalRefusal("Render content authority was revoked before the frame latch");
            return result;
        }
        /**
         * Null means ONLY the exact owner-minted generation CAS was revoked. Closed/foreign
         * PlanData, owner retirement, exhaustion and allocation/cleanup faults still throw.
         * A non-null private historical latch has exactly the same scope as latch().
         */
        public synchronized FrameLatch tryLatchCurrent(PlanInput exactGeometry) {
            requireOpen(); Objects.requireNonNull(exactGeometry);
            synchronized (exactGeometry) {
                exactGeometry.requireOpen();
                if (exactGeometry.data != plan.data)
                    throw new SurvivalRefusal("Current authority does not bind this exact geometry plan");
            }
            var frameCharge = budget.reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA, FrameLatch.METADATA_BYTES);
            PlanInput retained = null;
            try {
                retained = plan.retain();
                FrameLatch result = new FrameLatch(retained, frameCharge);
                // All reservation/retention/allocation precedes the linearization point. Revocation
                // is atomic and never acquires this monitor or waits for a renderer to release it.
                if (!stamp.tryLatchBeforeRevocation()) {
                    result.close(); retained = null;
                    requireOpen(); // A retired source owner is fatal, never pending content.
                    return null;
                }
                requireOpen(); // Monotonic closure after the CAS can only reject, never revive it.
                return result;
            } catch (RuntimeException | Error failure) {
                if (retained != null) retained.close();
                frameCharge.releaseAfterPhysicalDrain();
                throw failure;
            }
        }
        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            plan.close(); plan = null;
            charge.releaseAfterPhysicalDrain();
        }
    }

    /**
     * Immutable historical-content receipt accepted at an earlier CPU boundary. Deliberately retains
     * no CurrentLease/stamp and exposes no current/ready/draw authorization. Its parent render host
     * must join exact compiler provenance, physical native+DH completion, camera and live resources.
     * Resource/shader retirement cannot be bypassed by retaining this historical receipt.
     */
    public static final class FrameLatch implements AutoCloseable {
        private static final long METADATA_BYTES = 512;
        private final Thread owner = Thread.currentThread();
        private PlanInput plan;
        private final NativeDistanceCpuBudget.Reservation charge;
        private boolean closed;
        private FrameLatch(PlanInput plan, NativeDistanceCpuBudget.Reservation charge) { this.plan = plan; this.charge = charge; }
        private void requireOpen() {
            if (Thread.currentThread() != owner) throw new SurvivalRefusal("Historical frame latch requires its render owner");
            if (closed) throw new SurvivalRefusal("Historical frame latch is closed");
        }
        public String planIdentity() { requireOpen(); return plan.identity(); }
        public int pageCount() { requireOpen(); return plan.pageCount(); }
        public SurvivalDistancePagePlan.Page page(int index) { requireOpen(); return plan.page(index); }
        // Internal proof consumers compare the retained PlanData identity, never its hash.
        void requireExactPlan(PlanInput exact) {
            requireOpen(); Objects.requireNonNull(exact);
            synchronized (exact) {
                exact.requireOpen();
                if (plan.data != exact.data) throw new SurvivalRefusal("Historical content belongs to another exact plan");
            }
        }
        /** Caller has admitted one-cube scratch before entry. No raw expected payload escapes. */
        DependencyCheck beginDependency(CubePos position, SceneCube actual) {
            requireOpen(); Objects.requireNonNull(position); Objects.requireNonNull(actual);
            Shared value;
            synchronized (plan.content.shared) { value = plan.content.requireOpen(); }
            // This frame's charged retained handle keeps every immutable alias alive. Do not hold
            // a shared monitor while source sampling or exact-palette native decoding runs.
            if (!position.equals(actual.snapshot().position()) || !value.world.equals(actual.snapshot().world()))
                throw new SurvivalRefusal("Compiled dependency has a foreign world or cube address");
            value.source.validate(position);
            ImmutableCube expected = value.overlays.get(position);
            boolean generated = expected == null;
            if (generated) expected = value.source.generate(position, new CancellationToken());
            // materialize checks the snapshot's private wire palette fingerprint against this exact
            // pinned context; comparing only world IDs or public numeric revisions is insufficient.
            EngineCubeSection decoded = actual.snapshot().materialize(value.source.palette().context());
            return new DependencyCheck(expected, decoded, actual, value.source.palette(), generated);
        }
        @Override public void close() {
            if (Thread.currentThread() != owner) throw new SurvivalRefusal("Historical frame latch requires its render owner");
            if (closed) return;
            closed = true;
            plan.close(); plan = null;
            charge.releaseAfterPhysicalDrain();
        }
    }

    /** One charged verification scratch cube, confined to the compilation owner. Never a proof. */
    static final class DependencyCheck implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private ImmutableCube expected;
        private EngineCubeSection decoded;
        private SceneCube actual;
        private SurvivalRenderPalette palette;
        private final boolean generated;
        private int checked;
        private boolean closed;
        private DependencyCheck(ImmutableCube expected, EngineCubeSection decoded, SceneCube actual,
                SurvivalRenderPalette palette, boolean generated) {
            this.expected = expected; this.decoded = decoded; this.actual = actual;
            this.palette = palette; this.generated = generated;
        }
        private void requireOpen() {
            if (Thread.currentThread() != owner || closed) throw new SurvivalRefusal("Dependency scratch is closed or on another owner");
        }
        boolean generated() { requireOpen(); return generated; }
        boolean complete() { requireOpen(); return checked == CubePos.VOLUME; }
        void verifyCell() {
            requireOpen();
            if (checked == CubePos.VOLUME) throw new SurvivalRefusal("Dependency is already fully verified");
            int x = checked & 15, y = checked >>> 8, z = checked >>> 4 & 15;
            var exact = palette.state(expected.get(checked));
            if (actual.state(x, y, z) != exact || decoded.get(x, y, z) != exact)
                throw new SurvivalRefusal("Actual native dependency differs from the exact complete source/overlay state");
            checked++;
        }
        @Override public void close() {
            if (Thread.currentThread() != owner) throw new SurvivalRefusal("Dependency scratch requires its compilation owner");
            if (closed) return;
            closed = true; expected = null; decoded = null; actual = null; palette = null;
        }
    }

    private static void lineage(CubePos position, CubeSnapshot live, NativeSurvivalCheckpoint.Cube prior) {
        if (!position.equals(live.position())) throw new SurvivalRefusal("Mismatched live override address");
        if (prior == null) {
            if (live.durableRevision() != 0 || live.durableSimulationRevision() != 0)
                throw new SurvivalRefusal("Live durable cube is absent from the complete verified checkpoint");
        } else if (live.durableRevision() != prior.durableRevision()
                || live.durableSimulationRevision() != prior.simulationRevision()
                || live.simulationRevision() < prior.simulationRevision()
                || live.simulationRevision() == prior.simulationRevision() && !sameBlocks(live.blocks(), prior.blocks())) {
            throw new SurvivalRefusal("Live override has not recovered the exact durable lineage");
        }
    }
    /** No hash shortcut and no ImmutableCube.equals/toArray allocation. */
    private static boolean sameBlocks(ImmutableCube a, ImmutableCube b) {
        if (a == b) return true;
        for (int i = 0; i < CubePos.VOLUME; i++) if (a.get(i) != b.get(i)) return false;
        return true;
    }

    private static final class Shared {
        final Object ownerKey;
        final WorldIdentity world;
        final UUID ownerEpoch, stream;
        final long captureSequence, actionSequence, terrainSequence;
        MarinVisualCubeSource source;
        final NativeSurvivalCheckpoint.Binding binding;
        final String sourceIdentity, contentIdentity;
        final NativeDistanceCpuBudget budget;
        final NativeDistanceCpuBudget.Reservation metadataCharge, payloadCharge;
        Map<CubePos, ImmutableCube> overlays;
        Map<CubePos, CubeSnapshot> owned;
        List<CubePos> positions;
        int references;
        Shared(Object key, WorldIdentity world, UUID epoch, UUID stream, long captureSequence,
                long actionSequence, long terrainSequence, MarinVisualCubeSource source,
                NativeSurvivalCheckpoint.Binding binding, Map<CubePos, ImmutableCube> overlays,
                Map<CubePos, CubeSnapshot> owned, NativeDistanceCpuBudget budget,
                NativeDistanceCpuBudget.Reservation metadata, NativeDistanceCpuBudget.Reservation payload) {
            ownerKey = key; this.world = world; ownerEpoch = epoch; this.stream = stream;
            this.captureSequence = captureSequence; this.actionSequence = actionSequence; this.terrainSequence = terrainSequence;
            this.source = source; this.binding = binding; sourceIdentity = source.identity();
            this.overlays = overlays; this.owned = owned; positions = List.copyOf(overlays.keySet());
            this.budget = budget; metadataCharge = metadata; payloadCharge = payload;
            contentIdentity = contentIdentity(sourceIdentity, overlays);
        }
        Content open() {
            var charge = budget.reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA, HANDLE_METADATA);
            try {
                int next = Math.incrementExact(references);
                Content result = new Content(this, charge); references = next; return result;
            } catch (RuntimeException | Error failure) { charge.releaseAfterPhysicalDrain(); throw failure; }
        }
        void release() {
            if (--references != 0) return;
            overlays = null; owned = null; positions = null; source = null;
            payloadCharge.releaseAfterPhysicalDrain(); metadataCharge.releaseAfterPhysicalDrain();
        }
    }

    /** Thread-safe immutable reads. Closed handles refuse all reads/retains, even if another alias lives. */
    public static final class Content implements AutoCloseable {
        private final Shared shared;
        private final NativeDistanceCpuBudget.Reservation handleCharge;
        private boolean closed;
        private Content(Shared shared, NativeDistanceCpuBudget.Reservation charge) { this.shared = shared; handleCharge = charge; }
        private Shared requireOpen() {
            if (closed || shared.references < 1) throw new SurvivalRefusal("Complete overlay handle is closed");
            return shared;
        }
        public Content retain() { synchronized (shared) { requireOpen(); return shared.open(); } }
        public int overrideCount() { synchronized (shared) { return requireOpen().positions.size(); } }
        public CubePos overridePosition(int index) { synchronized (shared) { return requireOpen().positions.get(index); } }
        public boolean hasOverride(CubePos position) { synchronized (shared) { return requireOpen().overlays.containsKey(Objects.requireNonNull(position)); } }
        public int overrideStateId(CubePos position, int localIndex) {
            synchronized (shared) {
                ImmutableCube cube = requireOpen().overlays.get(Objects.requireNonNull(position));
                if (cube == null) throw new SurvivalRefusal("Absent override requires the certified visual source, never AIR");
                return cube.get(localIndex);
            }
        }
        /** Exact charged-source lifetime assertion; equal identities never substitute another allocation. */
        public void requireSource(MarinVisualCubeSource exact) {
            synchronized (shared) {
                if (requireOpen().source != Objects.requireNonNull(exact))
                    throw new SurvivalRefusal("Different exact retained visual source owner");
            }
        }
        public String sourceIdentity() { synchronized (shared) { return requireOpen().sourceIdentity; } }
        public String contentIdentity() { synchronized (shared) { return requireOpen().contentIdentity; } }
        public UUID ownerEpoch() { synchronized (shared) { return requireOpen().ownerEpoch; } }
        public UUID captureStream() { synchronized (shared) { return requireOpen().stream; } }
        public long captureSequence() { synchronized (shared) { return requireOpen().captureSequence; } }
        public long actionSequence() { synchronized (shared) { return requireOpen().actionSequence; } }
        public long terrainContentSequence() { synchronized (shared) { return requireOpen().terrainSequence; } }
        public PlanInput plan(CubePos focus, SurvivalDistancePagePlan.DistanceChoice distance,
                SurvivalDistancePagePlan.Limits limits) {
            Objects.requireNonNull(limits);
            final Shared value;
            final Content alias;
            synchronized (shared) {
                value = requireOpen(); alias = shared.open();
            }
            // This private charged alias prevents physical drain throughout construction. Do not
            // hold the shared monitor across distance-wide source/planning work: server-owner
            // validation must not wait for a worker's height sampling or canonicalization.
            NativeDistanceCpuBudget.Reservation charge = null;
            try {
                long bytes = Math.addExact(limits.maxMetadataBytes(), Math.addExact(256,
                        Math.multiplyExact(96L, value.overlays.size())));
                charge = value.budget.reserve(NativeDistanceCpuBudget.Pool.PLAN, bytes);
                var edits = SurvivalDistancePagePlan.CompleteEditSet.copyOf(value.terrainSequence,
                        value.contentIdentity, value.overlays.keySet(), limits);
                var plan = SurvivalDistancePagePlan.build(value.source.definition(), focus, distance, edits, limits);
                return new PlanInput(alias, new PlanData(plan, charge));
            } catch (RuntimeException | Error failure) {
                alias.close();
                if (charge != null) charge.releaseAfterPhysicalDrain();
                throw failure;
            }
        }
        @Override public void close() {
            synchronized (shared) {
                if (closed) return;
                closed = true; shared.release(); handleCharge.releaseAfterPhysicalDrain();
            }
        }
    }

    private static final class PlanData {
        SurvivalDistancePagePlan plan;
        final NativeDistanceCpuBudget.Reservation charge;
        int references = 1;
        PlanData(SurvivalDistancePagePlan plan, NativeDistanceCpuBudget.Reservation charge) { this.plan = plan; this.charge = charge; }
    }
    /** Owns the CompleteEditSet and plan without an escapable raw index or plan alias. */
    public static final class PlanInput implements AutoCloseable {
        private final Content content;
        private final PlanData data;
        private boolean closed;
        private PlanInput(Content content, PlanData data) { this.content = content; this.data = data; }
        private void requireOpen() { if (closed) throw new SurvivalRefusal("Complete overlay plan input is closed"); }
        public synchronized PlanInput retain() {
            requireOpen();
            synchronized (data) {
                int next = Math.incrementExact(data.references);
                Content alias = content.retain();
                try { var result = new PlanInput(alias, data); data.references = next; return result; }
                catch (RuntimeException | Error failure) { alias.close(); throw failure; }
            }
        }
        public synchronized int pageCount() { requireOpen(); return data.plan.pages().size(); }
        /** Detached selected/effective policy; not currentness or camera authority. */
        public synchronized SurvivalDistancePagePlan.DistanceChoice distance() { requireOpen(); return data.plan.distance(); }
        /** Exact retained PlanData assertion. Independent equal-valued plans do not match. */
        public void requireSame(PlanInput exact) {
            Objects.requireNonNull(exact);
            PlanData mine; synchronized (this) { requireOpen(); mine = data; }
            synchronized (exact) { exact.requireOpen(); if (mine != exact.data)
                throw new SurvivalRefusal("Different exact geometry plan ownership"); }
        }
        /** A frame owner must charge the same retained content budget, never a reset allowance. */
        public synchronized void requireBudget(NativeDistanceCpuBudget exact) {
            requireOpen(); synchronized (content.shared) {
                if (content.requireOpen().budget != exact) throw new SurvivalRefusal("Different exact content CPU budget");
            }
        }
        /** Page/address/masks are small detached scalar values; dependency arrays stay caller-accounted. */
        public synchronized SurvivalDistancePagePlan.Page page(int index) { requireOpen(); return data.plan.pages().get(index); }
        public synchronized String identity() { requireOpen(); return data.plan.identity(); }
        public synchronized SurvivalDistancePagePlan.Census census() { requireOpen(); return data.plan.census(); }
        public synchronized SurvivalDistancePagePlan.Bounds bounds() { requireOpen(); return data.plan.bounds(); }
        public synchronized boolean containsMember(CubePos position) { requireOpen(); return data.plan.containsMember(position); }
        public synchronized Content retainContent() { requireOpen(); return content.retain(); }
        @Override public synchronized void close() {
            if (closed) return;
            closed = true;
            synchronized (data) {
                if (--data.references == 0) { data.plan = null; data.charge.releaseAfterPhysicalDrain(); }
            }
            content.close();
        }
    }

    private static String contentIdentity(String sourceIdentity, Map<CubePos, ImmutableCube> cubes) {
        final MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        digest.update("complete-survival-source-overlay-content-039-v1\n".getBytes(StandardCharsets.UTF_8));
        digest.update(sourceIdentity.getBytes(StandardCharsets.UTF_8));
        byte[] encoded = new byte[Integer.BYTES]; // Covered by pre-copy metadata reservation.
        update(digest, encoded, cubes.size());
        for (var entry : cubes.entrySet()) {
            CubePos p = entry.getKey(); update(digest, encoded, p.x()); update(digest, encoded, p.y()); update(digest, encoded, p.z());
            for (int i = 0; i < CubePos.VOLUME; i++) update(digest, encoded, entry.getValue().get(i));
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void update(MessageDigest digest, byte[] bytes, int value) {
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (value >>> (i * 8));
        digest.update(bytes);
    }
}
