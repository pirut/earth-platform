package io.earthplatform.minecraft.survival.world;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.core.WorldIdentity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Single-server-thread, bounded cubic simulation owner. Admission is explicit; absent, retired,
 * unsupported and undeclared transaction reads refuse instead of synthesizing air or terrain.
 * Native effect receipts are immutable plans: the host must prevalidate and apply each accepted
 * receipt exactly once before publishing it. This class does not claim native-effect delivery,
 * whole-world atomic persistence, or crash-safe inventory/drop conservation.
 */
public final class NativeSurvivalWorld {
    public enum Presence { RESIDENT, RETIRED_OWNED, UNKNOWN }
    public enum TransactionState { OPEN, COMMITTED, ABORTED }
    public enum CheckpointState { IN_FLIGHT, FAILED, ACKNOWLEDGED }

    public record Bounds(CubePos minimum, CubePos maximum) {
        public Bounds {
            Objects.requireNonNull(minimum, "minimum");
            Objects.requireNonNull(maximum, "maximum");
            if (minimum.x() > maximum.x() || minimum.y() > maximum.y() || minimum.z() > maximum.z())
                throw new IllegalArgumentException("Ordered inclusive cubic bounds required");
        }
        public boolean contains(CubePos position) {
            return position.x() >= minimum.x() && position.x() <= maximum.x()
                    && position.y() >= minimum.y() && position.y() <= maximum.y()
                    && position.z() >= minimum.z() && position.z() <= maximum.z();
        }
    }

    /** Includes retained cubes, sequence high-water metadata and frozen checkpoint payloads. */
    public record Limits(int residentCubes, int ownedCubes, int actors, int transactionCubes,
            int reads, int writes, int effects, int effectBytes, int pins, int checkpointCubes) {
        public Limits {
            if (residentCubes < 1 || ownedCubes < residentCubes || ownedCubes > 65536
                    || actors < 1 || actors > 65536 || transactionCubes < 1 || transactionCubes > ownedCubes
                    || reads < 1 || reads > 262144 || writes < 1 || writes > reads
                    || effects < 1 || effects > 4096 || effectBytes < 1 || effectBytes > 4 * 1024 * 1024
                    || pins < 1 || pins > 65536 || checkpointCubes < 1 || checkpointCubes > ownedCubes)
                throw new IllegalArgumentException("Positive bounded survival ownership/action limits required");
        }
    }

    /** Logical immutable payload bytes, not a claim about JVM heap/object overhead or approved runtime budgets. */
    public record Usage(int residentCubes, int ownedCubes, int retiredCubes, int dirtyCubes,
            long currentCubePayloadBytes, int checkpointCubes, long checkpointPayloadBytes,
            int actorRecords, int explicitPins, int transactionReads, int transactionWrites,
            int transactionEffects, int transactionEffectBytes) {}

    public record CubeStamp(WorldIdentity world, UUID ownerEpoch, long incarnation, long simulationRevision) {
        public CubeStamp {
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(ownerEpoch, "ownerEpoch");
            if (incarnation <= 0 || simulationRevision < 0) throw new IllegalArgumentException("Invalid cube stamp");
        }
    }
    public record ActionIdentity(UUID ownerEpoch, UUID actor, long sequence) {
        public ActionIdentity {
            Objects.requireNonNull(ownerEpoch, "ownerEpoch");
            Objects.requireNonNull(actor, "actor");
            if (sequence <= 0) throw new IllegalArgumentException("Positive actor action sequence required");
        }
    }
    /** Only a successful owner commit can construct this value; the host still owns exact-once delivery. */
    public static final class CommitReceipt {
        private final WorldIdentity world;
        private final UUID ownerEpoch;
        private final long commitSequence;
        private final ActionIdentity action;
        private final Map<CubePos, CubeSnapshot> changedCubes;
        private final List<NativeActionEffect> effects;
        private CommitReceipt(WorldIdentity world, UUID ownerEpoch, long commitSequence,
                ActionIdentity action, Map<CubePos, CubeSnapshot> changedCubes, List<NativeActionEffect> effects) {
            this.world = world; this.ownerEpoch = ownerEpoch; this.commitSequence = commitSequence;
            this.action = action; this.changedCubes = Map.copyOf(changedCubes); this.effects = List.copyOf(effects);
        }
        public WorldIdentity world() { return world; }
        public UUID ownerEpoch() { return ownerEpoch; }
        public long commitSequence() { return commitSequence; }
        public ActionIdentity action() { return action; }
        public Map<CubePos, CubeSnapshot> changedCubes() { return changedCubes; }
        public List<NativeActionEffect> effects() { return effects; }
    }
    public record FrozenCube(CubeSnapshot snapshot, long nextDurableRevision) {
        public FrozenCube {
            Objects.requireNonNull(snapshot, "snapshot");
            if (nextDurableRevision <= 0 || nextDurableRevision - 1 != snapshot.durableRevision())
                throw new IllegalArgumentException("Checkpoint must advance exactly one durable revision");
        }
    }

    /** Immutable freeze token; acknowledge only after the host's complete required durability barrier. */
    public static final class CheckpointTicket {
        private final WorldIdentity world;
        private final UUID ownerEpoch;
        private final long number, commitSequence;
        private final Map<CubePos, FrozenCube> cubes;
        private final Map<UUID, Long> actorSequences;
        private CheckpointTicket(WorldIdentity world, UUID ownerEpoch, long number, long commitSequence,
                Map<CubePos, FrozenCube> cubes, Map<UUID, Long> actorSequences) {
            this.world = world; this.ownerEpoch = ownerEpoch; this.number = number;
            this.commitSequence = commitSequence; this.cubes = Map.copyOf(cubes);
            this.actorSequences = Map.copyOf(actorSequences);
        }
        public WorldIdentity world() { return world; }
        public UUID ownerEpoch() { return ownerEpoch; }
        public long number() { return number; }
        public long commitSequence() { return commitSequence; }
        public Map<CubePos, FrozenCube> cubes() { return cubes; }
        public Map<UUID, Long> actorSequences() { return actorSequences; }
    }

    private static final class Entry {
        ImmutableCube blocks;
        final long incarnation;
        long simulationRevision, durableRevision, durableSimulationRevision;
        int pins;
        boolean resident = true;
        Entry(ImmutableCube blocks, long incarnation, long simulationRevision, long durableRevision) {
            this.blocks = blocks; this.incarnation = incarnation; this.simulationRevision = simulationRevision;
            this.durableRevision = durableRevision; this.durableSimulationRevision = simulationRevision;
        }
        boolean dirty() { return simulationRevision != durableSimulationRevision; }
    }

    /**
     * Detached one-way render invalidation only. This carries no world/content/collision authority.
     * Revocation never waits for rendering and may be requested from a lifecycle thread. A frame
     * accepted before revocation is historical; it is not current readiness after revocation.
     */
    public static final class RenderPublicationFence {
        private final Thread owner;
        private final AtomicLong generation = new AtomicLong();
        private RenderPublicationFence(Thread owner) { this.owner = owner; }
        public void revoke() {
            for (;;) {
                long before = generation.get();
                if (before < 0) return; // Permanently fail closed on exhaustion; do not block simulation.
                long after = before == Long.MAX_VALUE ? Long.MIN_VALUE : before + 1;
                if (generation.compareAndSet(before, after)) return;
            }
        }
        /** Only the simulation owner can take a stamp. Complete content must still be validated. */
        public RenderPublicationStamp captureStamp() {
            if (Thread.currentThread() != owner) throw new SurvivalRefusal("Render stamp requires its simulation owner");
            long captured = generation.get();
            if (captured < 0) throw new SurvivalRefusal("Render publication generation exhausted");
            return new RenderPublicationStamp(this, captured);
        }
    }
    /** Private-minted invalidation stamp; by itself it proves no source, plan, resource or readiness. */
    public static final class RenderPublicationStamp {
        private final RenderPublicationFence fence;
        private final long generation;
        private RenderPublicationStamp(RenderPublicationFence fence, long generation) {
            this.fence = fence; this.generation = generation;
        }
        /** Atomic CPU latch point. Never retain a lock or block the simulation for a renderer. */
        public void latchBeforeRevocation() {
            if (!tryLatchBeforeRevocation())
                throw new SurvivalRefusal("Render content authority was revoked before the frame latch");
        }
        /** False is only a failed exact-generation CAS. Exhaustion remains a permanent refusal. */
        public boolean tryLatchBeforeRevocation() {
            if (fence.generation.compareAndSet(generation, generation)) return true;
            if (fence.generation.get() < 0) throw new SurvivalRefusal("Render publication generation exhausted");
            return false;
        }
    }

    private final Thread owner = Thread.currentThread();
    private final RenderPublicationFence renderPublication = new RenderPublicationFence(owner);
    private final WorldIdentity world;
    private final UUID ownerEpoch = UUID.randomUUID();
    private final SurvivalPalette palette;
    private final Bounds bounds;
    private final Limits limits;
    private final Map<CubePos, Entry> owned = new HashMap<>();
    private final Map<UUID, Long> actorSequences = new HashMap<>();
    private int residents, pinCount;
    private long incarnationSequence, commitSequence, terrainContentSequence, durableCommitSequence, checkpointSequence;
    private ActionTransaction active;
    private boolean preparingAction, preparationRefused;
    private CheckpointTicket checkpoint;
    private CheckpointState checkpointState;

    public NativeSurvivalWorld(WorldIdentity world, SurvivalPalette palette, Bounds bounds, Limits limits) {
        this.world = Objects.requireNonNull(world, "world");
        this.palette = Objects.requireNonNull(palette, "palette");
        this.bounds = Objects.requireNonNull(bounds, "bounds");
        this.limits = Objects.requireNonNull(limits, "limits");
        if (!world.blockPaletteVersion().equals(palette.identity()))
            throw new IllegalArgumentException("World must pin this exact native registry palette");
    }
    /** A revocation-only lifecycle capability; stamp capture still requires the simulation owner. */
    public RenderPublicationFence renderPublicationFence() { checkOwner(); return renderPublication; }
    /** Called before whole-action opening, including inventory callbacks before a transaction exists. */
    public void revokeRenderPublications() { checkOwner(); renderPublication.revoke(); }
    public WorldIdentity world() { return world; }
    public UUID ownerEpoch() { return ownerEpoch; }
    public SurvivalPalette palette() { return palette; }
    public Bounds bounds() { return bounds; }
    public Limits limits() { return limits; }
    public void checkOwner() {
        if (Thread.currentThread() != owner) throw new SurvivalRefusal("mutable owner accessed from another thread");
    }
    public int ownedCount() { checkOwner(); return owned.size(); }
    public int residentCount() { checkOwner(); return residents; }
    public int actorCount() { checkOwner(); return actorSequences.size(); }
    /** Full action high-water cut, including aborted attempts; caller owns the bounded copy. */
    public Map<UUID, Long> actionSequences() { checkOwner(); return Map.copyOf(actorSequences); }
    public int pinCount() { checkOwner(); return pinCount; }
    public long commitSequence() { checkOwner(); return commitSequence; }
    /**
     * Accepted net block-change commits only. Effects, no-op/A-to-B-to-A actions, admission,
     * residency and durable acknowledgement do not advance this counter. It is an invalidation
     * hint, never proof of complete overlay membership, source identity or ownership currentness.
     */
    public long terrainContentSequence() { checkOwner(); return terrainContentSequence; }
    public long durableCommitSequence() { checkOwner(); return durableCommitSequence; }
    /** Includes effect-only actions that cannot be discovered through dirty cube positions. */
    public boolean hasUncheckpointedActions() { checkOwner(); return commitSequence != durableCommitSequence; }
    /** Includes zero-cube checkpoints carrying inventory/entity/tick-only accepted actions. */
    public boolean hasPendingCheckpoint() { checkOwner(); return checkpoint != null; }
    /** Read-only next intent identity. Only begin consumes it, including aborted attempts. */
    public long nextActionSequence(UUID actor) {
        checkOwner(); Objects.requireNonNull(actor, "actor");
        Long last = actorSequences.get(actor);
        if (last == null) {
            if (actorSequences.size() >= limits.actors()) throw new SurvivalRefusal("actor metadata budget exhausted");
            return 1;
        }
        if (last == Long.MAX_VALUE) throw new SurvivalRefusal("actor action sequence exhausted");
        return last + 1;
    }
    public ActionIdentity actionIdentity(UUID actor, long sequence) {
        checkOwner(); return new ActionIdentity(ownerEpoch, actor, sequence);
    }
    public Usage usage() {
        checkOwner(); long bytes = 0, checkpointBytes = 0; int dirty = 0;
        for (Entry entry : owned.values()) { bytes += entry.blocks.payloadBytes(); if (entry.dirty()) dirty++; }
        if (checkpoint != null)
            for (FrozenCube cube : checkpoint.cubes.values()) checkpointBytes += cube.snapshot().blocks().payloadBytes();
        return new Usage(residents, owned.size(), owned.size() - residents, dirty, bytes,
                checkpoint == null ? 0 : checkpoint.cubes.size(), checkpointBytes, actorSequences.size(), pinCount,
                active == null ? 0 : active.readOperations, active == null ? 0 : active.writeOperations,
                active == null ? 0 : active.effects.size(), active == null ? 0 : active.effectBytes);
    }
    public boolean hasActiveTransaction() { checkOwner(); return active != null || preparingAction; }
    public Presence presence(CubePos position) {
        checkOwner(); checkPosition(position);
        Entry entry = owned.get(position);
        return entry == null ? Presence.UNKNOWN : entry.resident ? Presence.RESIDENT : Presence.RETIRED_OWNED;
    }
    public Set<CubePos> ownedPositions() { checkOwner(); return Set.copyOf(owned.keySet()); }
    public Set<CubePos> dirtyPositions() {
        checkOwner(); Set<CubePos> dirty = new HashSet<>();
        owned.forEach((position, entry) -> { if (entry.dirty()) dirty.add(position); });
        return Set.copyOf(dirty);
    }
    public boolean isDirty(CubePos position) { checkOwner(); return requireOwned(position).dirty(); }
    public CubeStamp stamp(CubePos position) { return snapshot(position).stamp(); }
    public CubeSnapshot snapshot(CubePos position) {
        checkIdle(); return snapshot(position, requireOwned(position));
    }
    public BlockState read(CubeCell cell) {
        checkOwner(); Objects.requireNonNull(cell, "cell");
        // Level/neighbor reads during a native action join its complete declared read-set.
        if (active != null) return active.read(cell);
        return palette.state(requireResident(cell.cube()).blocks.get(cell.localIndex()));
    }
    public void admitGenerated(CubePos position, ImmutableCube blocks) { admit(position, blocks, 0, 0); }
    /** The caller must already verify world identity and the complete authoritative save manifest. */
    public void admitRecovered(CubePos position, ImmutableCube blocks, long simulationRevision, long durableRevision) {
        if (simulationRevision < 0 || durableRevision < 1)
            throw new IllegalArgumentException("Nonnegative simulation and positive recovered durable revision required");
        admit(position, blocks, simulationRevision, durableRevision);
    }
    private void admit(CubePos position, ImmutableCube blocks, long simulationRevision, long durableRevision) {
        checkIdle(); checkPosition(position);
        if (owned.containsKey(position)) throw new SurvivalRefusal("refusing replacement of owned cube " + position);
        if (owned.size() >= limits.ownedCubes() || residents >= limits.residentCubes())
            throw new SurvivalRefusal("cube ownership or residency budget exhausted");
        palette.validate(blocks);
        long incarnation = increment(incarnationSequence, "cube incarnation");
        Entry admitted = new Entry(blocks, incarnation, simulationRevision, durableRevision);
        renderPublication.revoke();
        owned.put(position, admitted);
        incarnationSequence = incarnation; residents++;
    }
    /** Retains all state and metadata, including dirty ownership. It is not eviction or a save. */
    public void retire(CubePos position) {
        checkIdle(); Entry entry = requireResident(position);
        if (entry.pins != 0) throw new SurvivalRefusal("explicitly pinned cube cannot retire");
        renderPublication.revoke();
        entry.resident = false; residents--;
    }
    public void reload(CubePos position) {
        checkIdle(); Entry entry = requireOwned(position);
        if (entry.resident) throw new SurvivalRefusal("cube is already resident");
        if (residents >= limits.residentCubes()) throw new SurvivalRefusal("resident cube budget exhausted");
        renderPublication.revoke();
        entry.resident = true; residents++;
    }
    public void releaseClean(CubePos position) {
        checkIdle(); Entry entry = requireOwned(position);
        if (entry.dirty() || entry.pins != 0 || checkpoint != null && checkpoint.cubes.containsKey(position))
            throw new SurvivalRefusal("dirty, frozen or pinned cube retains ownership");
        renderPublication.revoke();
        owned.remove(position);
        if (entry.resident) residents--;
    }
    public Pin pin(CubePos position) {
        checkIdle(); Entry entry = requireOwned(position);
        if (pinCount >= limits.pins()) throw new SurvivalRefusal("pin budget exhausted");
        Pin token = new Pin(position, entry);
        entry.pins++; pinCount++; return token;
    }
    public final class Pin implements AutoCloseable {
        private final CubePos position;
        private final Entry entry;
        private boolean released;
        private Pin(CubePos position, Entry entry) { this.position = position; this.entry = entry; }
        @Override public void close() {
            checkIdle();
            if (released) return;
            if (owned.get(position) != entry) throw new SurvivalRefusal("stale ownership pin");
            entry.pins--; pinCount--; released = true;
        }
    }

    public ActionTransaction begin(ActionIdentity identity, Map<CubePos, CubeStamp> expectedStamps) {
        checkIdle();
        // Revoke before entering even the preparation state. No renderer can hold a permit that
        // stalls/refuses this action. Neutral actions preserve semantic geometry, not old authority.
        renderPublication.revoke();
        // Guard before any caller collection method. A swallowed nested owner-operation refusal
        // must poison this preparation; it must not let the outer action overwrite a nested owner.
        preparingAction = true; preparationRefused = false;
        try {
            Objects.requireNonNull(identity, "identity"); Objects.requireNonNull(expectedStamps, "expectedStamps");
            if (!ownerEpoch.equals(identity.ownerEpoch())) throw new SurvivalRefusal("foreign or expired action owner epoch");
            if (expectedStamps.size() > limits.transactionCubes()) throw new SurvivalRefusal("action cube budget exhausted");
            Map<CubePos, CubeStamp> expected = Map.copyOf(expectedStamps);
            validateExpected(expected);
            if (preparationRefused) throw new SurvivalRefusal("action preparation attempted a reentrant owner operation");
            Long last = actorSequences.get(identity.actor());
            if (last != null && identity.sequence() <= last) throw new SurvivalRefusal("repeated or stale actor action sequence");
            if (last == null && actorSequences.size() >= limits.actors()) throw new SurvivalRefusal("actor metadata budget exhausted");
            if (commitSequence == Long.MAX_VALUE) throw new SurvivalRefusal("commit sequence exhausted");
            ActionTransaction transaction = new ActionTransaction(identity, expected);
            // An admitted action identity is consumed even on abort; no unbounded replay/tombstone set.
            actorSequences.put(identity.actor(), identity.sequence()); active = transaction; return transaction;
        } finally {
            preparingAction = false; preparationRefused = false;
        }
    }

    public final class ActionTransaction implements AutoCloseable {
        private final ActionIdentity identity;
        private final Map<CubePos, CubeStamp> expected;
        private final Set<CubeCell> reads = new HashSet<>();
        private final Map<CubeCell, Integer> writes = new TreeMap<>();
        private final List<NativeActionEffect> effects = new ArrayList<>();
        private TransactionState state = TransactionState.OPEN;
        private int effectBytes, readOperations, writeOperations;
        private ActionTransaction(ActionIdentity identity, Map<CubePos, CubeStamp> expected) {
            this.identity = identity; this.expected = expected;
        }
        public ActionIdentity identity() { return identity; }
        public Map<CubePos, CubeStamp> expectedStamps() { return expected; }
        public TransactionState state() { checkOwner(); return state; }
        public int readCount() { checkOwner(); return readOperations; }
        public int writeCount() { checkOwner(); return writeOperations; }
        public int effectCount() { checkOwner(); return effects.size(); }
        public int effectBytes() { checkOwner(); return effectBytes; }
        public Set<CubeCell> readSet() { checkOwner(); return Set.copyOf(reads); }
        public BlockState read(CubeCell cell) {
            ensureOpen();
            try {
                Objects.requireNonNull(cell, "cell");
                Entry entry = declared(cell);
                if (readOperations >= limits.reads()) throw new SurvivalRefusal("action read budget exhausted");
                int id = writes.getOrDefault(cell, entry.blocks.get(cell.localIndex()));
                BlockState result = palette.state(id); reads.add(cell); readOperations++; return result;
            } catch (RuntimeException refused) { abortInternal(); throw refused; }
        }
        public BlockState set(CubeCell cell, BlockState replacement) {
            ensureOpen();
            try {
                if (writeOperations >= limits.writes()) throw new SurvivalRefusal("action write budget exhausted");
                int id = palette.id(replacement);
                BlockState previous = read(cell);
                writeOperations++;
                if (previous == replacement) return previous;
                writes.put(cell, id); return previous;
            } catch (RuntimeException refused) { abortInternal(); throw refused; }
        }
        public void stage(NativeActionEffect effect) {
            ensureOpen();
            try {
                Objects.requireNonNull(effect, "effect");
                if (effects.size() >= limits.effects() || effect.payloadBytes() > limits.effectBytes() - effectBytes)
                    throw new SurvivalRefusal("action effect count or byte budget exhausted");
                effects.add(effect); effectBytes += effect.payloadBytes();
            } catch (RuntimeException refused) { abortInternal(); throw refused; }
        }
        public CommitReceipt commit() {
            ensureOpen();
            try {
                validateExpected(expected);
                long nextCommit = increment(commitSequence, "commit sequence");
                Map<CubePos, Map<Integer, Integer>> byCube = new TreeMap<>();
                writes.forEach((cell, id) -> {
                    palette.state(id);
                    Entry current = requireResident(cell.cube());
                    if (current.blocks.get(cell.localIndex()) != id)
                        byCube.computeIfAbsent(cell.cube(), ignored -> new TreeMap<>()).put(cell.localIndex(), id);
                });
                Map<CubePos, CubeSnapshot> changed = new TreeMap<>();
                for (var write : byCube.entrySet()) {
                    Entry entry = requireResident(write.getKey());
                    long nextRevision = increment(entry.simulationRevision, "simulation revision");
                    ImmutableCube nextBlocks = entry.blocks.withEdits(write.getValue());
                    changed.put(write.getKey(), new CubeSnapshot(world, ownerEpoch, write.getKey(), entry.incarnation,
                            nextRevision, entry.durableRevision, entry.durableSimulationRevision, nextBlocks));
                }
                int dirty = 0;
                for (var entry : owned.entrySet())
                    if (entry.getValue().dirty() || changed.containsKey(entry.getKey())) dirty++;
                if (dirty > limits.checkpointCubes()) throw new SurvivalRefusal("dirty cube checkpoint budget exhausted");
                long nextTerrain = changed.isEmpty() ? terrainContentSequence
                        : increment(terrainContentSequence, "terrain content sequence");
                CommitReceipt receipt = new CommitReceipt(world, ownerEpoch, nextCommit, identity, changed, effects);
                // Every validation, allocation and overflow check above precedes the first publication.
                for (var change : changed.entrySet()) {
                    Entry entry = owned.get(change.getKey());
                    entry.blocks = change.getValue().blocks(); entry.simulationRevision = change.getValue().simulationRevision();
                }
                commitSequence = nextCommit; terrainContentSequence = nextTerrain; state = TransactionState.COMMITTED; active = null;
                return receipt;
            } catch (RuntimeException refused) { abortInternal(); throw refused; }
        }
        public void abort() { ensureOpen(); abortInternal(); }
        @Override public void close() { checkOwner(); if (state == TransactionState.OPEN) abort(); }
        private Entry declared(CubeCell cell) {
            if (!expected.containsKey(cell.cube())) throw new SurvivalRefusal("undeclared action read/write cube " + cell.cube());
            Entry entry = requireResident(cell.cube());
            if (!stamp(cell.cube(), entry).equals(expected.get(cell.cube()))) throw new SurvivalRefusal("action cube revision changed");
            return entry;
        }
        private void ensureOpen() {
            checkOwner();
            if (state != TransactionState.OPEN || active != this) throw new SurvivalRefusal("action is no longer the active open transaction");
        }
        private void abortInternal() {
            if (state == TransactionState.OPEN) {
                state = TransactionState.ABORTED; if (active == this) active = null;
                writes.clear(); effects.clear(); effectBytes = 0;
            }
        }
    }

    public CheckpointTicket freezeCheckpoint(Set<CubePos> positions) {
        checkIdle(); Objects.requireNonNull(positions, "positions");
        if (checkpoint != null) throw new SurvivalRefusal("finish or retry the exact outstanding checkpoint first");
        if (positions.size() > limits.checkpointCubes()) throw new SurvivalRefusal("checkpoint cube budget invalid");
        for (var entry : owned.entrySet())
            if (entry.getValue().dirty() && !positions.contains(entry.getKey()))
                throw new SurvivalRefusal("checkpoint must freeze every dirty cube at the action boundary");
        Map<CubePos, FrozenCube> frozen = new TreeMap<>();
        for (CubePos position : positions) {
            Entry entry = requireOwned(position);
            frozen.put(position, new FrozenCube(snapshot(position, entry), increment(entry.durableRevision, "durable revision")));
        }
        long number = increment(checkpointSequence, "checkpoint sequence");
        CheckpointTicket ticket = new CheckpointTicket(world, ownerEpoch, number, commitSequence, frozen, actorSequences);
        checkpointSequence = number; checkpoint = ticket; checkpointState = CheckpointState.IN_FLIGHT;
        return ticket;
    }
    public CheckpointState checkpointState(CheckpointTicket ticket) {
        checkOwner(); requireCurrentTicket(ticket); return checkpointState;
    }
    /** Keep exact frozen bytes and ownership after uncertain I/O; never substitute a newer snapshot. */
    public void failCheckpoint(CheckpointTicket ticket) {
        checkIdle(); requireCurrentTicket(ticket);
        if (checkpointState != CheckpointState.IN_FLIGHT) throw new SurvivalRefusal("checkpoint is not in flight");
        checkpointState = CheckpointState.FAILED;
    }
    public CheckpointTicket retryCheckpoint(CheckpointTicket ticket) {
        checkIdle(); requireCurrentTicket(ticket);
        if (checkpointState != CheckpointState.FAILED) throw new SurvivalRefusal("checkpoint does not need retry");
        checkpointState = CheckpointState.IN_FLIGHT; return ticket;
    }
    public void acknowledgeCheckpoint(CheckpointTicket ticket) {
        checkIdle(); requireCurrentTicket(ticket);
        if (checkpointState != CheckpointState.IN_FLIGHT) throw new SurvivalRefusal("failed checkpoint must be retried before acknowledgment");
        // Validate the entire token before changing any durable bookkeeping.
        for (var frozen : ticket.cubes.entrySet()) {
            Entry entry = requireOwned(frozen.getKey());
            CubeSnapshot snapshot = frozen.getValue().snapshot();
            if (entry.incarnation != snapshot.incarnation() || entry.durableRevision != snapshot.durableRevision()
                    || entry.simulationRevision < snapshot.simulationRevision())
                throw new SurvivalRefusal("checkpoint ownership/revision changed");
        }
        for (var frozen : ticket.cubes.entrySet()) {
            Entry entry = owned.get(frozen.getKey());
            entry.durableRevision = frozen.getValue().nextDurableRevision();
            entry.durableSimulationRevision = frozen.getValue().snapshot().simulationRevision();
        }
        durableCommitSequence = ticket.commitSequence;
        checkpointState = CheckpointState.ACKNOWLEDGED; checkpoint = null;
    }

    private void requireCurrentTicket(CheckpointTicket ticket) {
        if (ticket == null || checkpoint != ticket || !ownerEpoch.equals(ticket.ownerEpoch))
            throw new SurvivalRefusal("wrong, stale or already acknowledged checkpoint token");
    }
    private void validateExpected(Map<CubePos, CubeStamp> expected) {
        for (var item : expected.entrySet()) {
            Entry entry = requireResident(item.getKey());
            if (!stamp(item.getKey(), entry).equals(item.getValue())) throw new SurvivalRefusal("stale or foreign cube stamp at " + item.getKey());
        }
    }
    private CubeStamp stamp(CubePos position, Entry entry) {
        return new CubeStamp(world, ownerEpoch, entry.incarnation, entry.simulationRevision);
    }
    private CubeSnapshot snapshot(CubePos position, Entry entry) {
        return new CubeSnapshot(world, ownerEpoch, position, entry.incarnation, entry.simulationRevision,
                entry.durableRevision, entry.durableSimulationRevision, entry.blocks);
    }
    private Entry requireOwned(CubePos position) {
        checkPosition(position); Entry entry = owned.get(position);
        if (entry == null) throw new SurvivalRefusal("unknown cube " + position);
        return entry;
    }
    private Entry requireResident(CubePos position) {
        Entry entry = requireOwned(position);
        if (!entry.resident) throw new SurvivalRefusal("cube is unloaded but still owned: " + position);
        return entry;
    }
    private void checkPosition(CubePos position) {
        Objects.requireNonNull(position, "position");
        if (!bounds.contains(position)) throw new SurvivalRefusal("cube outside admitted survival bounds: " + position);
    }
    private void checkIdle() {
        checkOwner();
        if (preparingAction) {
            preparationRefused = true;
            throw new SurvivalRefusal("non-reentrant action preparation in progress");
        }
        if (active != null) throw new SurvivalRefusal("non-reentrant action in progress");
    }
    private static long increment(long value, String name) {
        if (value == Long.MAX_VALUE) throw new SurvivalRefusal(name + " exhausted");
        return value + 1;
    }
}
