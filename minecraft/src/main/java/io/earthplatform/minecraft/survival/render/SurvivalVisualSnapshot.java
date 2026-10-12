package io.earthplatform.minecraft.survival.render;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.scene.SceneRenderEnvelope;
import io.earthplatform.minecraft.survival.world.CubeSnapshot;
import io.earthplatform.minecraft.survival.interaction.NativeWholeAction;
import io.earthplatform.minecraft.survival.world.NativeSurvivalWorld;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Immutable server export, including exact signed cells and full owner/incarnation provenance.
 * No mutable simulation owner, callback, native section or generated fallback is retained.
 * This page-local value is NOT evidence that the selected/effective render distance is complete.
 */
public final class SurvivalVisualSnapshot {
    public static final int MAX_MEMBERS = SurvivalVisualPage.MAX_MEMBERS;
    public static final int MAX_DEPENDENCIES = SurvivalVisualPage.MAX_DEPENDENCIES;
    private final WorldIdentity world;
    private final UUID ownerEpoch, exportStream;
    private final long exportSequence, commitSequence, payloadBytes;
    private final Set<CubePos> members;
    private final Map<CubePos, CubeSnapshot> cubes;

    private SurvivalVisualSnapshot(WorldIdentity world, UUID ownerEpoch, UUID exportStream,
            long exportSequence, long commitSequence, Set<CubePos> members, Map<CubePos, CubeSnapshot> cubes) {
        this.world = world; this.ownerEpoch = ownerEpoch; this.exportStream = exportStream;
        this.exportSequence = exportSequence; this.commitSequence = commitSequence;
        this.members = Collections.unmodifiableSet(new TreeSet<>(members));
        this.cubes = Collections.unmodifiableMap(new TreeMap<>(cubes));
        this.payloadBytes = cubes.values().stream().mapToLong(c -> c.blocks().payloadBytes()).sum();
    }
    public WorldIdentity world() { return world; }
    public UUID ownerEpoch() { return ownerEpoch; }
    public UUID exportStream() { return exportStream; }
    public long exportSequence() { return exportSequence; }
    public long commitSequence() { return commitSequence; }
    /** Logical immutable integer payload only; does not estimate heap, engine sections or native buffers. */
    public long payloadBytes() { return payloadBytes; }
    public Set<CubePos> members() { return members; }
    public Map<CubePos, CubeSnapshot> cubes() { return cubes; }

    /** Preflight union is bounded before any CubeSnapshot or native-state array is retained. */
    public static Set<CubePos> dependencies(Set<CubePos> members) {
        Objects.requireNonNull(members, "members");
        if (members.isEmpty() || members.size() > MAX_MEMBERS)
            throw new SurvivalRefusal("visual export requires 1..64 explicit native members");
        var result = new TreeSet<CubePos>();
        int count = 0;
        for (CubePos member : members) {
            Objects.requireNonNull(member, "member");
            if (++count > MAX_MEMBERS) throw new SurvivalRefusal("visual member budget exceeded");
            for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++) {
                CubePos dependency = member.offset(x, y, z);
                if (!SceneRenderEnvelope.NATIVE.contains(dependency))
                    throw new SurvivalRefusal("visual dependency exceeds the explicit cubic render envelope");
                Math.toIntExact(dependency.minBlockX()); Math.toIntExact(dependency.minBlockX() + 15);
                Math.toIntExact(dependency.minBlockZ()); Math.toIntExact(dependency.minBlockZ() + 15);
                if (result.add(dependency) && result.size() > MAX_DEPENDENCIES)
                    throw new SurvivalRefusal("visual dependency union exceeds 216 owners");
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /**
     * Server-only capability. Keep one exporter for its lifetime, then transfer only capture() values.
     * A stream sequence orders admission/reincarnation exports even when no action commit occurred.
     * Bind the host session's exact whole-action owner for the entire lifetime; replacing a fenced
     * owner is not recovery. No export may reveal committed cubes until native delivery and scope
     * close have both succeeded. Aborted scopes must also close before publication.
     */
    public static final class Exporter {
        private final NativeSurvivalWorld owner;
        private final NativeWholeAction.Owner actionOwner;
        private final UUID stream = UUID.randomUUID();
        private long sequence;
        public Exporter(NativeSurvivalWorld owner, NativeWholeAction.Owner actionOwner) {
            this.owner = Objects.requireNonNull(owner, "owner"); owner.checkOwner();
            this.actionOwner = Objects.requireNonNull(actionOwner, "actionOwner");
            if (actionOwner.world() != owner)
                throw new SurvivalRefusal("visual exporter requires the exact simulation's native action owner");
            requirePublishable();
        }
        private void requirePublishable() {
            owner.checkOwner();
            if (actionOwner.world() != owner || actionOwner.hasCurrentAction() || actionOwner.fenced())
                throw new SurvivalRefusal("visual export refused during native action or failed effect delivery");
            if (owner.hasActiveTransaction()) throw new SurvivalRefusal("visual export cannot bypass an active native action");
        }
        public SurvivalVisualSnapshot capture(SurvivalVisualPage page) {
            return capture(Objects.requireNonNull(page, "page").members());
        }
        public SurvivalVisualSnapshot capture(Set<CubePos> requestedMembers) {
            requirePublishable();
            // Check the caller's bound before copying, then freeze membership before deriving the union.
            Objects.requireNonNull(requestedMembers, "members");
            if (requestedMembers.isEmpty() || requestedMembers.size() > MAX_MEMBERS)
                throw new SurvivalRefusal("visual export requires 1..64 explicit native members");
            Set<CubePos> members = Set.copyOf(requestedMembers);
            Set<CubePos> required = dependencies(members);
            for (CubePos position : required)
                if (owner.presence(position) != NativeSurvivalWorld.Presence.RESIDENT)
                    throw new SurvivalRefusal("visual export requires resident dependency " + position);
            long next = Math.incrementExact(sequence), commit = owner.commitSequence();
            var cubes = new TreeMap<CubePos, CubeSnapshot>();
            for (CubePos position : required) {
                CubeSnapshot cube = owner.snapshot(position);
                if (!cube.world().equals(owner.world()) || !cube.ownerEpoch().equals(owner.ownerEpoch()))
                    throw new SurvivalRefusal("visual export crossed owner identity");
                cubes.put(position, cube);
            }
            requirePublishable();
            if (owner.commitSequence() != commit)
                throw new SurvivalRefusal("visual export crossed a native commit");
            var result = new SurvivalVisualSnapshot(owner.world(), owner.ownerEpoch(), stream, next, commit, members, cubes);
            sequence = next;
            return result;
        }
    }
}
