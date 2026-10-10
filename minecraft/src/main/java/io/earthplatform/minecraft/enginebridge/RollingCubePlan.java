package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.CubeTickets;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Immutable geometry for one rolling 5 x 5 x 5 window and one fixed 3 x 3 x 3 pin.
 * A candidate is at most one cube away on each axis; a distant desired goal is coalesced by
 * the caller and approached using {@link #nextStep}. No heightfield/provider is consulted.
 * All lists are duplicate-free, immutable, and ordered by {@link CubePos#compareTo}.
 *
 * <p>These are coordinate bounds, NOT a total payload/storage memory proof. The owning actor
 * must separately bound and retire: generated bases and edit overlays; cancelled-but-running
 * generation (which retains its physical loader slot); queued/running storage operations;
 * native encoded snapshots and wire buffers/in-flight sends; captured ray snapshots and work;
 * CPU mesh generations and GPU vertex/index bytes; and old/new collision snapshot generations.
 * Cancellation does not alter this plan or prove any of those owners have settled.
 *
 * <p>Only the center of the 27-cube pin is a pinned render target, so its rebuild dependencies
 * remain resident when the rolling window moves away. Its outer one-block shell is immutable
 * for the gate's one-block-neighborhood mesh contract; arbitrary modded model reads are not proved.
 * At most
 * 55 old/new mesh generations assumes a single reused fixed-pin mesh generation; an edit rebuild
 * must still be serialized with a rolling transition or separately accounted for by its owner.
 */
public final class RollingCubePlan {
    public static final int OWNER_RADIUS = 2;
    public static final int TARGET_RADIUS = 1;
    public static final int PIN_RADIUS = 1;
    public static final int WINDOW_OWNERS = 125;
    public static final int WINDOW_TARGETS = 27;
    public static final int PIN_OWNERS = 27;
    public static final int PIN_TARGETS = 1;
    public static final int MAX_COMMITTED_OWNERS = 152;
    public static final int MAX_ADDED_OR_RETIRED_OWNERS = 61;
    /** 125 + 125 - 4^3 + 27; this bound is attainable with a disjoint pin. */
    public static final int MAX_STAGED_OWNERS = 213;
    public static final int MAX_COMMITTED_TARGETS = 28;
    /** Distinct target coordinates: 27 + 27 - 2^3 + 1. */
    public static final int MAX_STAGED_TARGETS = 47;
    /** Old 27 + new 27 + shared fixed pin 1; this is not a GPU-byte bound. */
    public static final int MAX_STAGED_MESH_GENERATIONS = 55;

    // Matches the signed 22/20/22-bit SectionPos key, without depending on Minecraft classes.
    public static final int NATIVE_XZ_MIN = -(1 << 21);
    public static final int NATIVE_XZ_MAX = (1 << 21) - 1;
    public static final int NATIVE_Y_MIN = -(1 << 19);
    public static final int NATIVE_Y_MAX = (1 << 19) - 1;
    public static final int RENDER_MIN_Y = -11040;
    public static final int RENDER_MAX_Y_EXCLUSIVE = 8896;
    public static final int GAMEPLAY_MIN_Y = -11008;
    public static final int GAMEPLAY_MAX_Y_EXCLUSIVE = 8864;
    public static final int MIN_WINDOW_CENTER_Y = RENDER_MIN_Y / CubePos.SIZE + OWNER_RADIUS;
    public static final int MAX_WINDOW_CENTER_Y = RENDER_MAX_Y_EXCLUSIVE / CubePos.SIZE - OWNER_RADIUS - 1;

    private final CubePos pinCenter;
    private final CubePos currentCenter;
    private final CubePos candidateCenter;
    private final List<CubePos> currentOwners;
    private final List<CubePos> candidateOwners;
    private final List<CubePos> pinnedOwners;
    private final List<CubePos> stagedOwners;
    private final List<CubePos> committedOwners;
    private final List<CubePos> nextCommittedOwners;
    private final List<CubePos> addedOwners;
    private final List<CubePos> retiredOwners;
    private final List<CubePos> currentTargets;
    private final List<CubePos> candidateTargets;
    private final List<CubePos> pinnedTargets;
    private final List<CubePos> committedTargets;
    private final List<CubePos> stagedTargets;

    private RollingCubePlan(CubePos pinCenter, CubePos currentCenter, CubePos candidateCenter) {
        this.pinCenter = Objects.requireNonNull(pinCenter, "pinCenter");
        this.currentCenter = Objects.requireNonNull(currentCenter, "currentCenter");
        this.candidateCenter = candidateCenter;
        validateBox(pinCenter, PIN_RADIUS);
        validateWindowCenter(currentCenter);
        if (candidateCenter != null) {
            validateWindowCenter(candidateCenter);
            if (!adjacent(currentCenter, candidateCenter))
                throw new IllegalArgumentException("Candidate must be adjacent on every axis; coalesce goals with nextStep");
        }
        currentOwners = box(currentCenter, OWNER_RADIUS, WINDOW_OWNERS);
        candidateOwners = candidateCenter == null ? List.of() : box(candidateCenter, OWNER_RADIUS, WINDOW_OWNERS);
        pinnedOwners = box(pinCenter, PIN_RADIUS, PIN_OWNERS);
        committedOwners = union(currentOwners, pinnedOwners);
        stagedOwners = union(committedOwners, candidateOwners);
        nextCommittedOwners = candidateCenter == null ? committedOwners : union(candidateOwners, pinnedOwners);
        addedOwners = difference(nextCommittedOwners, committedOwners);
        retiredOwners = difference(committedOwners, nextCommittedOwners);
        currentTargets = box(currentCenter, TARGET_RADIUS, WINDOW_TARGETS);
        candidateTargets = candidateCenter == null ? List.of() : box(candidateCenter, TARGET_RADIUS, WINDOW_TARGETS);
        pinnedTargets = List.of(pinCenter);
        committedTargets = union(currentTargets, pinnedTargets);
        stagedTargets = union(committedTargets, candidateTargets);
        validate();
    }

    /** A null candidate means that no transition is admitted; an equal center is a safe no-op. */
    public static RollingCubePlan create(CubePos pinCenter, CubePos currentCenter, CubePos candidateCenter) {
        return new RollingCubePlan(pinCenter, currentCenter, candidateCenter);
    }

    public static RollingCubePlan stationary(CubePos pinCenter, CubePos currentCenter) {
        return create(pinCenter, currentCenter, null);
    }

    /**
     * Signed floor of the actual player's feet position, never a provider's surface height.
     * Feet must be inside gameplay Y; the surrounding dependency window fits the real padding.
     * This does not prove a complete player AABB is safe or widen the gameplay envelope.
     */
    public static CubePos centerFromFeet(double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || y < GAMEPLAY_MIN_Y || y >= GAMEPLAY_MAX_Y_EXCLUSIVE)
            throw new IllegalArgumentException("Finite feet inside gameplay Y required");
        CubePos center = new CubePos(section(x), section(y), section(z));
        validateWindowCenter(center);
        return center;
    }

    /**
     * Take one simultaneous 3D adjacent step toward the latest goal, or return current at goal.
     * Validation precedes subtraction/offsets, including for far-away or extreme integer goals.
     * A returned step is only a proposal; it does not admit another candidate before retirement.
     */
    public static CubePos nextStep(CubePos current, CubePos goal) {
        validateWindowCenter(current);
        validateWindowCenter(goal);
        return current.offset(Integer.compare(goal.x(), current.x()), Integer.compare(goal.y(), current.y()),
                Integer.compare(goal.z(), current.z()));
    }

    /** Chebyshev distance at most one, including an unchanged center. Uses non-overflowing deltas. */
    public static boolean adjacent(CubePos a, CubePos b) {
        Objects.requireNonNull(a); Objects.requireNonNull(b);
        return Math.abs((long) a.x() - b.x()) <= 1 && Math.abs((long) a.y() - b.y()) <= 1
                && Math.abs((long) a.z() - b.z()) <= 1;
    }

    public static void validateWindowCenter(CubePos center) {
        validateBox(center, OWNER_RADIUS);
    }

    /**
     * Only local 1..14 on all three axes of the original editable cube may change. The outer
     * one-block shell stays immutable under the caller's one-block-neighborhood mesh contract.
     * Long comparisons reject unrelated/overflowing block coordinates without narrowing casts.
     * Full permission, actor lifecycle, revision and durable-save checks remain caller-owned.
     */
    public static boolean isEditableInterior(CubePos editableCube, long x, long y, long z) {
        Objects.requireNonNull(editableCube, "editableCube");
        return y >= GAMEPLAY_MIN_Y && y < GAMEPLAY_MAX_Y_EXCLUSIVE
                && x > editableCube.minBlockX() && x < editableCube.minBlockX() + CubePos.SIZE - 1
                && y > editableCube.minBlockY() && y < editableCube.minBlockY() + CubePos.SIZE - 1
                && z > editableCube.minBlockZ() && z < editableCube.minBlockZ() + CubePos.SIZE - 1;
    }

    public boolean isEditableInterior(long x, long y, long z) {
        return isEditableInterior(pinCenter, x, y, z);
    }

    /** Validate complete dependency cubes, not just their centers, before enumerating any box. */
    private static void validateBox(CubePos center, int radius) {
        Objects.requireNonNull(center, "center");
        if ((long) center.x() - radius < NATIVE_XZ_MIN || (long) center.x() + radius > NATIVE_XZ_MAX
                || (long) center.z() - radius < NATIVE_XZ_MIN || (long) center.z() + radius > NATIVE_XZ_MAX
                || (long) center.y() - radius < NATIVE_Y_MIN || (long) center.y() + radius > NATIVE_Y_MAX)
            throw new IllegalArgumentException("Dependency window exceeds lossless native section-key range");
        long bottom = ((long) center.y() - radius) * CubePos.SIZE;
        long topExclusive = ((long) center.y() + radius + 1) * CubePos.SIZE;
        if (bottom < RENDER_MIN_Y || topExclusive > RENDER_MAX_Y_EXCLUSIVE)
            throw new IllegalArgumentException("Dependency window exceeds the real render envelope");
    }

    /** Recheck the immutable snapshot's accounting invariants without consulting mutable state. */
    public void validate() {
        if (currentOwners.size() != WINDOW_OWNERS || pinnedOwners.size() != PIN_OWNERS
                || currentTargets.size() != WINDOW_TARGETS || pinnedTargets.size() != PIN_TARGETS
                || candidateOwners.size() != (candidateCenter == null ? 0 : WINDOW_OWNERS)
                || candidateTargets.size() != (candidateCenter == null ? 0 : WINDOW_TARGETS)
                || committedOwners.size() > MAX_COMMITTED_OWNERS || stagedOwners.size() > MAX_STAGED_OWNERS
                || nextCommittedOwners.size() > MAX_COMMITTED_OWNERS
                || addedOwners.size() > MAX_ADDED_OR_RETIRED_OWNERS || retiredOwners.size() > MAX_ADDED_OR_RETIRED_OWNERS
                || committedTargets.size() > MAX_COMMITTED_TARGETS || stagedTargets.size() > MAX_STAGED_TARGETS
                || !stagedOwners.containsAll(stagedTargets))
            throw new IllegalStateException("Invalid rolling owner/target accounting");
    }

    private static int section(double block) {
        // Floor the block first: dividing a negative subnormal by 16 can underflow to -0.0.
        double section = Math.floor(Math.floor(block) / CubePos.SIZE);
        if (section < Integer.MIN_VALUE || section > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Feet exceed signed cube-coordinate range");
        return (int) section;
    }

    private static List<CubePos> box(CubePos center, int radius, int size) {
        return CubeTickets.box(center, radius, radius, size).stream().sorted().toList();
    }

    private static List<CubePos> union(Collection<CubePos> first, Collection<CubePos> second) {
        TreeSet<CubePos> ordered = new TreeSet<>(first);
        ordered.addAll(second);
        return List.copyOf(ordered);
    }

    private static List<CubePos> difference(Collection<CubePos> first, Collection<CubePos> second) {
        TreeSet<CubePos> ordered = new TreeSet<>(first);
        ordered.removeAll(second);
        return List.copyOf(ordered);
    }

    public CubePos pinCenter() { return pinCenter; }
    public CubePos currentCenter() { return currentCenter; }
    public CubePos candidateCenter() { return candidateCenter; }
    public boolean hasCandidate() { return candidateCenter != null; }
    public List<CubePos> currentOwners() { return currentOwners; }
    public List<CubePos> candidateOwners() { return candidateOwners; }
    public List<CubePos> pinnedOwners() { return pinnedOwners; }
    public List<CubePos> stagedOwners() { return stagedOwners; }
    public List<CubePos> committedOwners() { return committedOwners; }
    /** Candidate plus fixed pin after commit, or committedOwners when there is no candidate. */
    public List<CubePos> nextCommittedOwners() { return nextCommittedOwners; }
    /** New leases required by this transition; initial startup must separately admit all owners. */
    public List<CubePos> addedOwners() { return addedOwners; }
    /** Leases that can retire after a successful commit, never merely after cancellation. */
    public List<CubePos> retiredOwners() { return retiredOwners; }
    public List<CubePos> currentTargets() { return currentTargets; }
    public List<CubePos> candidateTargets() { return candidateTargets; }
    public List<CubePos> pinnedTargets() { return pinnedTargets; }
    public List<CubePos> committedTargets() { return committedTargets; }
    public List<CubePos> stagedTargets() { return stagedTargets; }
}
