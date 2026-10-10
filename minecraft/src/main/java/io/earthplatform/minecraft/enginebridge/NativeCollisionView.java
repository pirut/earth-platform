package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Bounded, read-only collision policy over an immutable admitted snapshot generation.
 * No level, provider, source IO, terrain mutation, stock-height array or client authority lives here.
 * Missing/stale/unsupported cells return a virtual BARRIER exclusively to the native collision
 * consumer. This is an unavailable-space boundary, never a generated or persisted block.
 *
 * <p>Native BlockCollisions can use this as its chunk BlockGetter at any X/Z; this getter retains
 * independent full XYZ ownership. Its outer CollisionGetter is passed to native shape evaluation,
 * so only the explicitly audited, entity/neighbor-independent air and unit-block states qualify.
 * An owner containing any unsupported state is conservatively unavailable as a whole, including
 * a one-cell collar in adjacent admitted cubes (for ordinary protruding shapes such as fences).
 * Arbitrarily oversized modded geometry is outside this explicitly bounded gate.
 *
 * <p>Views are replaced, never edited in place. Close the previous generation BEFORE altering
 * admission, applying a replacement, reloading registries or releasing a session. The caller must
 * serialize native collision consumption and publication on the owning game thread, and check
 * isCurrent before accepting the result. No BlockGetter can retract a shape already consumed by
 * vanilla if another thread changes authority halfway through a movement calculation.
 */
public final class NativeCollisionView implements BlockGetter, AutoCloseable {
    public static final int MAX_OWNERS = 125;
    public static final int MIN_Y = -11008, MAX_Y_EXCLUSIVE = 8864;
    public static final int MIN_XZ = -33554432, MAX_XZ_EXCLUSIVE = 33554432;
    private static final Set<Block> AIR = Set.of(Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR);
    private static final Set<Block> SOLID = Set.of(Blocks.STONE, Blocks.DIRT, Blocks.GRASS_BLOCK,
            Blocks.GRAVEL, Blocks.SNOW_BLOCK, Blocks.ANDESITE, Blocks.DIORITE, Blocks.TUFF,
            Blocks.CYAN_TERRACOTTA, Blocks.MAGENTA_TERRACOTTA, Blocks.GOLD_BLOCK, Blocks.BEDROCK);
    private static final BlockState BOUNDARY_STATE = Blocks.BARRIER.defaultBlockState();

    public enum BoundaryReason {
        MISSING_CUBE, STALE_DEPENDENCY, UNSUPPORTED_STATE, OUTSIDE_DOMAIN,
        REGISTRY_CHANGED, FENCE_FAILED, CLOSED
    }
    public record Boundary(BoundaryReason reason, CubePos position) {}
    public record Read(BlockState state, Boundary boundary) {
        public boolean known() { return boundary == null; }
    }
    /** Cumulative bounded-storage diagnostics, not a history of visited positions. */
    public record Counters(long reads, long knownAir, long knownSolid, long boundaryReads,
                           long fenceChecks, long stampChecks, Map<BoundaryReason, Long> reasons,
                           Boundary lastBoundary, Boundary invalidation) {
        public Counters { reasons = Map.copyOf(reasons); }
    }
    private record Owner(NativeKnownCubeQueries.CubeStamp stamp, ImmutableCube blocks, boolean audited) {}

    // Contents never change in place. Invalidation only drops this complete immutable generation.
    private Map<CubePos, Owner> owners;
    private NativeKnownCubeQueries.CurrentStamp currentStamp;
    private BooleanSupplier currentGeneration;
    private BlockState[] pinnedStates;
    private boolean[] usedStates;
    private final EnumMap<BoundaryReason, Long> reasons = new EnumMap<>(BoundaryReason.class);
    private Boundary invalidation, lastBoundary;
    private long reads, knownAir, knownSolid, boundaryReads, fenceChecks, stampChecks;

    /**
     * Safe standalone mode: every read revalidates all (at most 125) owner stamps. In-place
     * replacement of any owner therefore closes the entire old view, even on another cube's read.
     */
    public NativeCollisionView(WorldIdentity world, long epoch, EnginePaletteContext palette,
                               Map<CubePos, EngineCubeSnapshot> snapshots,
                               NativeKnownCubeQueries.CurrentStamp currentStamp) {
        this(world, epoch, palette, snapshots, currentStamp, null);
    }

    /** Whole-frame authority: the caller's fence covers every exact admitted stamp in the map. */
    public NativeCollisionView(WorldIdentity world, long epoch, EnginePaletteContext palette,
                               Map<CubePos, EngineCubeSnapshot> snapshots, BooleanSupplier frameCurrent) {
        this(world, epoch, palette, snapshots, ignored -> true, Objects.requireNonNull(frameCurrent));
    }

    /**
     * Fast owner-thread mode: currentGeneration must turn false BEFORE ANY constituent stamp or
     * registry changes. Reads check that global generation plus the referenced owner only;
     * isCurrent performs the full bounded validation at query boundaries. A null generation
     * selects the safe standalone mode. The callbacks must read already-owned memory, never IO.
     * Preparation decodes at most 125 sections and should be kept off latency-sensitive ticks.
     */
    public NativeCollisionView(WorldIdentity world, long epoch, EnginePaletteContext palette,
                               Map<CubePos, EngineCubeSnapshot> snapshots,
                               NativeKnownCubeQueries.CurrentStamp currentStamp,
                               BooleanSupplier currentGeneration) {
        Objects.requireNonNull(world); Objects.requireNonNull(snapshots);
        this.currentStamp = Objects.requireNonNull(currentStamp);
        this.currentGeneration = currentGeneration;
        if (epoch < 0 || snapshots.size() > MAX_OWNERS)
            throw new IllegalArgumentException("Invalid epoch or collision owner budget");
        NativeCubeCodec codec = new NativeCubeCodec(world, palette);
        pinnedStates = new BlockState[Block.BLOCK_STATE_REGISTRY.size()];
        usedStates = new boolean[pinnedStates.length];
        byte[] audited = new byte[pinnedStates.length];
        for (int id = 0; id < pinnedStates.length; id++) pinnedStates[id] = NativeCubeCodec.registeredState(id);
        Map<CubePos, Owner> copy = new HashMap<>();
        for (var entry : snapshots.entrySet()) {
            EngineCubeSnapshot snapshot = Objects.requireNonNull(entry.getValue());
            if (!snapshot.position().equals(entry.getKey()) || !world.equals(snapshot.world()) || epoch != snapshot.epoch())
                throw new IllegalArgumentException("Snapshot key, world or epoch differs from collision view");
            ImmutableCube blocks = codec.capture(snapshot);
            boolean safe = true;
            for (int i = 0, count = blocks.isUniform() ? 1 : CubePos.VOLUME; i < count; i++) {
                int id = blocks.get(i);
                usedStates[id] = true;
                if (audited[id] == 0) audited[id] = (byte) (audited(pinnedStates[id]) ? 1 : -1);
                safe &= audited[id] == 1;
            }
            copy.put(entry.getKey(), new Owner(NativeKnownCubeQueries.CubeStamp.of(snapshot), blocks, safe));
            if (copy.size() > MAX_OWNERS) throw new IllegalArgumentException("Collision owner budget exceeded");
        }
        owners = Map.copyOf(copy);
    }

    public synchronized int ownerCount() { return owners.size(); }
    public synchronized int residentOwners() { return owners.size(); }
    public synchronized NativeKnownCubeQueries.Dependencies dependencies() {
        var stamps = new ArrayList<NativeKnownCubeQueries.CubeStamp>(owners.size());
        owners.values().forEach(owner -> stamps.add(owner.stamp));
        return new NativeKnownCubeQueries.Dependencies(stamps);
    }

    /** Explicit fail-closed query-boundary check; a failed fence is sticky for this generation. */
    public synchronized boolean isCurrent() { return validateGeneration(true) == null; }

    public synchronized Read read(BlockPos pos) {
        Objects.requireNonNull(pos);
        reads++;
        CubePos cube = CubePos.fromBlock(pos.getX(), pos.getY(), pos.getZ());
        Boundary failure = validateGeneration(currentGeneration == null);
        if (failure != null) return boundary(failure);
        if (pos.getY() < MIN_Y || pos.getY() >= MAX_Y_EXCLUSIVE
                || pos.getX() < MIN_XZ || pos.getX() >= MAX_XZ_EXCLUSIVE
                || pos.getZ() < MIN_XZ || pos.getZ() >= MAX_XZ_EXCLUSIVE)
            return boundary(new Boundary(BoundaryReason.OUTSIDE_DOMAIN, cube));
        Owner owner = owners.get(cube);
        if (owner == null) return boundary(new Boundary(BoundaryReason.MISSING_CUBE, cube));
        if (currentGeneration != null && !checkStamp(owner)) return boundary(invalidation);
        if (!owner.audited) return boundary(new Boundary(BoundaryReason.UNSUPPORTED_STATE, cube));
        Boundary halo = unsupportedHalo(pos, cube);
        if (halo != null) return boundary(halo);
        int id = owner.blocks.get(CubePos.indexOfBlock(pos.getX(), pos.getY(), pos.getZ()));
        if (Block.BLOCK_STATE_REGISTRY.byId(id) != pinnedStates[id])
            return boundary(invalidate(BoundaryReason.REGISTRY_CHANGED, cube));
        BlockState state = pinnedStates[id];
        if (AIR.contains(state.getBlock())) knownAir++; else knownSolid++;
        return new Read(state, null);
    }

    @Override public BlockState getBlockState(BlockPos pos) { return read(pos).state(); }
    @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
    @Override public BlockEntity getBlockEntity(BlockPos pos) {
        // Neither accepted states nor virtual boundaries own block entities.
        getBlockState(pos);
        return null;
    }
    @Override public int getHeight() { return MAX_Y_EXCLUSIVE - MIN_Y; }
    @Override public int getMinBuildHeight() { return MIN_Y; }

    public synchronized Counters counters() {
        return new Counters(reads, knownAir, knownSolid, boundaryReads, fenceChecks, stampChecks,
                reasons, lastBoundary, invalidation);
    }
    /** Idempotent, immediate lifecycle fence. Reopening requires a fresh immutable view. */
    @Override public synchronized void close() { invalidate(BoundaryReason.CLOSED, null); }
    public synchronized void invalidate() { close(); }

    private Boundary validateGeneration(boolean allOwners) {
        if (invalidation != null) return invalidation;
        fenceChecks++;
        try {
            if (currentGeneration != null && !currentGeneration.getAsBoolean())
                return invalidate(BoundaryReason.STALE_DEPENDENCY, null);
        } catch (RuntimeException failure) {
            return invalidate(BoundaryReason.FENCE_FAILED, null);
        }
        if (Block.BLOCK_STATE_REGISTRY.size() != pinnedStates.length)
            return invalidate(BoundaryReason.REGISTRY_CHANGED, null);
        if (allOwners) {
            for (Owner owner : owners.values()) if (!checkStamp(owner)) return invalidation;
            for (int id = 0; id < usedStates.length; id++)
                if (usedStates[id] && Block.BLOCK_STATE_REGISTRY.byId(id) != pinnedStates[id])
                    return invalidate(BoundaryReason.REGISTRY_CHANGED, null);
        }
        return null;
    }
    private boolean checkStamp(Owner owner) {
        stampChecks++;
        try {
            boolean current = currentStamp.isCurrent(owner.stamp);
            if (invalidation != null) return false;
            if (current) return true;
            invalidate(BoundaryReason.STALE_DEPENDENCY, owner.stamp.position());
        } catch (RuntimeException failure) {
            invalidate(BoundaryReason.FENCE_FAILED, owner.stamp.position());
        }
        return false;
    }
    private Boundary unsupportedHalo(BlockPos pos, CubePos cube) {
        int x = CubePos.local(pos.getX()), y = CubePos.local(pos.getY()), z = CubePos.local(pos.getZ());
        // Owner-wide audit already covers interior neighbours. Only seams need additional lookups.
        for (int dx = x == 0 ? -1 : 0; dx <= (x == 15 ? 1 : 0); dx++)
            for (int dy = y == 0 ? -1 : 0; dy <= (y == 15 ? 1 : 0); dy++)
                for (int dz = z == 0 ? -1 : 0; dz <= (z == 15 ? 1 : 0); dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    CubePos adjacent = cube.offset(dx, dy, dz);
                    if (adjacent.y() < MIN_Y / 16 || adjacent.y() >= MAX_Y_EXCLUSIVE / 16
                            || adjacent.x() < MIN_XZ / 16 || adjacent.x() >= MAX_XZ_EXCLUSIVE / 16
                            || adjacent.z() < MIN_XZ / 16 || adjacent.z() >= MAX_XZ_EXCLUSIVE / 16) continue;
                    Owner neighbor = owners.get(adjacent);
                    // Missing space has explicit unit-cell boundary policy. No native shape is
                    // inferred from it; admitted unsupported native geometry needs the extra collar.
                    if (neighbor == null) continue;
                    if (currentGeneration != null && !checkStamp(neighbor)) return invalidation;
                    if (!neighbor.audited) return new Boundary(BoundaryReason.UNSUPPORTED_STATE, adjacent);
                }
        return null;
    }
    private Boundary invalidate(BoundaryReason reason, CubePos position) {
        if (invalidation == null) {
            invalidation = new Boundary(reason, position);
            owners = Map.of();
            pinnedStates = new BlockState[0];
            usedStates = new boolean[0];
            currentStamp = null;
            currentGeneration = null;
        }
        return invalidation;
    }
    private Read boundary(Boundary failure) {
        boundaryReads++;
        reasons.merge(failure.reason(), 1L, Long::sum);
        lastBoundary = failure;
        return new Read(BOUNDARY_STATE, failure);
    }
    private static boolean audited(BlockState state) {
        boolean air = AIR.contains(state.getBlock()), solid = SOLID.contains(state.getBlock());
        if ((!air && !solid) || state.hasBlockEntity() || !state.getFluidState().isEmpty()) return false;
        var shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty());
        List<AABB> boxes = shape.toAabbs();
        if (air) return shape.isEmpty();
        if (boxes.size() != 1 || state.hasLargeCollisionShape()) return false;
        AABB box = boxes.getFirst();
        return box.minX == 0 && box.minY == 0 && box.minZ == 0 && box.maxX == 1 && box.maxY == 1 && box.maxZ == 1;
    }
}
