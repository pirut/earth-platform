package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Read-only, full-3D queries over a bounded set of already admitted immutable native snapshots.
 * Owns no provider, tickets, store, level, thread, or tick hook. Missing/stale/unsupported data is
 * UNKNOWN, never air. This first gate deliberately supports audited air/opaque-full-block states
 * only; it is not a general entity/fluid/neighbor-dependent Minecraft physics implementation.
 */
public final class NativeKnownCubeQueries {
    public static final int MAX_OWNERS = 125;
    public static final double MAX_REACH = 8.0;
    public static final double MAX_BODY_WIDTH = 1.0, MAX_BODY_HEIGHT = 2.0;
    public static final int MIN_Y = -11008, MAX_Y_EXCLUSIVE = 8864;
    private static final int MIN_XZ = -33554432, MAX_XZ_EXCLUSIVE = 33554432;
    private static final int MAX_RAY_CELLS = 32;
    private static final Set<Block> AIR = Set.of(Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR);
    // Explicitly audited, entity-independent, within-cell shapes used by the present fixtures.
    // In particular slabs, fences, doors, fluids, snow layers and block entities are NOT inferred.
    private static final Set<Block> SOLID = Set.of(Blocks.STONE, Blocks.DIRT, Blocks.GRASS_BLOCK,
            Blocks.GRAVEL, Blocks.SNOW_BLOCK, Blocks.ANDESITE, Blocks.DIORITE, Blocks.TUFF,
            Blocks.CYAN_TERRACOTTA, Blocks.MAGENTA_TERRACOTTA, Blocks.GOLD_BLOCK, Blocks.BEDROCK);

    public enum Status { HIT, CLEAR, UNKNOWN }
    public enum UnknownReason { MISSING_CUBE, STALE_DEPENDENCY, UNSUPPORTED_STATE, OUTSIDE_DOMAIN, REGISTRY_CHANGED }

    /** Exact identity of one admitted owner, including its independent Y and revision. */
    public record CubeStamp(WorldIdentity world, long epoch, CubePos position, long leaseGeneration, long revision) {
        public CubeStamp {
            Objects.requireNonNull(world); Objects.requireNonNull(position);
            if (epoch < 0 || leaseGeneration <= 0 || revision < 0) throw new IllegalArgumentException("Invalid owner stamp");
        }
        public static CubeStamp of(EngineCubeSnapshot snapshot) {
            return new CubeStamp(snapshot.world(), snapshot.epoch(), snapshot.position(),
                    snapshot.leaseGeneration(), snapshot.revision());
        }
    }

    /** Caller checks all stamp components against its current admission/actor ownership. */
    @FunctionalInterface public interface CurrentStamp { boolean isCurrent(CubeStamp stamp); }

    /** Revalidate on the authoritative owner immediately before applying a hit/edit or movement. */
    public record Dependencies(List<CubeStamp> stamps) {
        public Dependencies { stamps = List.copyOf(stamps); }
        public boolean isCurrent(CurrentStamp current) {
            Objects.requireNonNull(current);
            for (CubeStamp stamp : stamps) if (!current.isCurrent(stamp)) return false;
            return true;
        }
    }
    public record Unknown(UnknownReason reason, CubePos position) {}
    public record RayResult(Status status, BlockHitResult hit, Dependencies dependencies, Unknown unknown) {}
    public record CollisionResult(Status status, BlockPos block, Dependencies dependencies, Unknown unknown) {}
    private record Owner(CubeStamp stamp, ImmutableCube blocks) {}
    private record Intersection(double fraction, Direction face, boolean inside) {}

    private final Map<CubePos, Owner> owners;
    private final BlockState[] pinnedStates;

    /**
     * Preparation may decode up to 125 sections. Do this outside a latency-sensitive tick.
     * The map is copied; no mutable caller section or admission collection is retained.
     * Uniform epoch/world is mandatory, while revisions and leases are per owner.
     */
    public NativeKnownCubeQueries(WorldIdentity world, long epoch, EnginePaletteContext palette,
                                 Map<CubePos, EngineCubeSnapshot> snapshots) {
        Objects.requireNonNull(world); Objects.requireNonNull(snapshots);
        if (epoch < 0 || snapshots.size() > MAX_OWNERS) throw new IllegalArgumentException("Invalid epoch or owner budget");
        NativeCubeCodec codec = new NativeCubeCodec(world, palette);
        Map<CubePos, Owner> copy = new HashMap<>();
        for (var entry : snapshots.entrySet()) {
            EngineCubeSnapshot snapshot = Objects.requireNonNull(entry.getValue());
            if (!snapshot.position().equals(entry.getKey()) || !world.equals(snapshot.world()) || epoch != snapshot.epoch())
                throw new IllegalArgumentException("Snapshot key, world or epoch differs from query view");
            copy.put(entry.getKey(), new Owner(CubeStamp.of(snapshot), codec.capture(snapshot)));
            if (copy.size() > MAX_OWNERS) throw new IllegalArgumentException("Owner budget exceeded");
        }
        owners = Map.copyOf(copy);
        pinnedStates = new BlockState[Block.BLOCK_STATE_REGISTRY.size()];
        for (int i = 0; i < pinnedStates.length; i++) pinnedStates[i] = NativeCubeCodec.registeredState(i);
    }

    public int ownerCount() { return owners.size(); }

    /**
     * First intersection with actual registered collision-shape boxes, with inclusive 8m reach.
     * All traversal/intersection calculations use doubles relative to each block, never floats
     * or packed BlockPos keys. A start inside solid hits at distance zero. At an exact voxel
     * boundary, the direction of travel chooses its owner; parallel boundaries use floor.
     * Zero-length/nonfinite/over-budget inputs are programmer errors, not known-clear space.
     */
    public RayResult raycast(Vec3 start, Vec3 end, CurrentStamp current) {
        requirePoint(start); requirePoint(end);
        Vec3 delta = end.subtract(start);
        double lengthSquared = delta.lengthSqr();
        if (!(lengthSquared > 0 && lengthSquared <= MAX_REACH * MAX_REACH))
            throw new IllegalArgumentException("Ray must have positive length at most 8 metres");
        ReadScope scope = new ReadScope(current);
        try {
            int x = initialCell(start.x, delta.x), y = initialCell(start.y, delta.y), z = initialCell(start.z, delta.z);
            for (int visited = 0; visited < MAX_RAY_CELLS; visited++) {
                BlockPos pos = new BlockPos(x, y, z);
                VoxelShape shape = scope.shapeWithHalo(pos);
                Vec3 local = start.subtract(x, y, z);
                Intersection nearest = null;
                for (AABB box : shape.toAabbs()) {
                    Intersection candidate = intersect(box, local, delta);
                    if (candidate != null && (nearest == null || candidate.fraction < nearest.fraction)) nearest = candidate;
                }
                if (nearest != null) {
                    Vec3 point = start.add(delta.scale(nearest.fraction));
                    return rayResult(scope, new BlockHitResult(point, nearest.face, pos, nearest.inside));
                }
                double tx = nextBoundary(start.x, delta.x, x), ty = nextBoundary(start.y, delta.y, y), tz = nextBoundary(start.z, delta.z, z);
                double next = Math.min(tx, Math.min(ty, tz));
                if (next > 1.0) return rayResult(scope, null);
                if (tx == next) x += delta.x > 0 ? 1 : -1;
                if (ty == next) y += delta.y > 0 ? 1 : -1;
                if (tz == next) z += delta.z > 0 ? 1 : -1;
            }
            throw new IllegalStateException("Bounded 8m ray exceeded its traversal proof");
        } catch (UnknownData unknown) {
            return new RayResult(Status.UNKNOWN, null, scope.dependencies(), unknown.detail);
        }
    }

    /**
     * Static, strictly positive-volume overlap only; touching faces do not overlap. The query
     * examines every intersected voxel before reporting HIT, so any required unknown owner
     * dominates even an already found overlap. This is not swept movement or collision response.
     */
    public CollisionResult overlaps(AABB body, CurrentStamp current) {
        Objects.requireNonNull(body);
        requirePoint(new Vec3(body.minX, body.minY, body.minZ));
        requirePoint(new Vec3(body.maxX, body.maxY, body.maxZ));
        if (!(body.getXsize() > 0 && body.getXsize() <= MAX_BODY_WIDTH
                && body.getZsize() > 0 && body.getZsize() <= MAX_BODY_WIDTH
                && body.getYsize() > 0 && body.getYsize() <= MAX_BODY_HEIGHT))
            throw new IllegalArgumentException("Body must fit a positive 1x2x1 metre box");
        ReadScope scope = new ReadScope(current);
        BlockPos overlap = null;
        try {
            int maxX = (int) Math.ceil(body.maxX) - 1, maxY = (int) Math.ceil(body.maxY) - 1, maxZ = (int) Math.ceil(body.maxZ) - 1;
            for (int y = (int) Math.floor(body.minY); y <= maxY; y++)
                for (int z = (int) Math.floor(body.minZ); z <= maxZ; z++)
                    for (int x = (int) Math.floor(body.minX); x <= maxX; x++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        VoxelShape shape = scope.shapeWithHalo(pos);
                        AABB local = body.move(-((double) x), -((double) y), -((double) z));
                        for (AABB box : shape.toAabbs()) if (box.intersects(local) && overlap == null) overlap = pos;
                    }
            scope.revalidate();
            return new CollisionResult(overlap == null ? Status.CLEAR : Status.HIT, overlap, scope.dependencies(), null);
        } catch (UnknownData unknown) {
            return new CollisionResult(Status.UNKNOWN, null, scope.dependencies(), unknown.detail);
        }
    }

    private RayResult rayResult(ReadScope scope, BlockHitResult hit) {
        scope.revalidate();
        return new RayResult(hit == null ? Status.CLEAR : Status.HIT, hit, scope.dependencies(), null);
    }

    private final class ReadScope implements BlockGetter {
        private final CurrentStamp current;
        private final Map<CubePos, CubeStamp> sampled = new LinkedHashMap<>();
        private final Map<BlockPos, VoxelShape> shapes = new HashMap<>();
        private ReadScope(CurrentStamp current) { this.current = Objects.requireNonNull(current); }
        private Dependencies dependencies() { return new Dependencies(List.copyOf(sampled.values())); }
        private void revalidate() {
            for (CubeStamp stamp : sampled.values()) if (!current.isCurrent(stamp))
                throw unknown(UnknownReason.STALE_DEPENDENCY, stamp.position());
        }
        @Override public BlockState getBlockState(BlockPos pos) {
            CubePos cube = CubePos.fromBlock(pos.getX(), pos.getY(), pos.getZ());
            if (pos.getY() < MIN_Y || pos.getY() >= MAX_Y_EXCLUSIVE || pos.getX() < MIN_XZ || pos.getX() >= MAX_XZ_EXCLUSIVE
                    || pos.getZ() < MIN_XZ || pos.getZ() >= MAX_XZ_EXCLUSIVE) throw unknown(UnknownReason.OUTSIDE_DOMAIN, cube);
            Owner owner = owners.get(cube);
            if (owner == null) throw unknown(UnknownReason.MISSING_CUBE, cube);
            sampled.put(cube, owner.stamp);
            if (!current.isCurrent(owner.stamp)) throw unknown(UnknownReason.STALE_DEPENDENCY, cube);
            int id = owner.blocks.get(CubePos.local(pos.getX()), CubePos.local(pos.getY()), CubePos.local(pos.getZ()));
            if (Block.BLOCK_STATE_REGISTRY.size() != pinnedStates.length || id < 0 || id >= pinnedStates.length
                    || Block.BLOCK_STATE_REGISTRY.byId(id) != pinnedStates[id]) throw unknown(UnknownReason.REGISTRY_CHANGED, cube);
            return pinnedStates[id];
        }
        /** Native collision can protrude from an adjacent block, so audit a one-cell halo too.
         * All accepted states have within-cell shapes. Any unsupported neighbor makes the query
         * unknown rather than being silently ignored (for example the upper half of a fence).
         * Arbitrarily oversized modded shapes are outside this explicitly bounded fixture API.
         */
        private VoxelShape shapeWithHalo(BlockPos pos) {
            for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++)
                shape(pos.offset(dx, dy, dz));
            return shape(pos);
        }
        private VoxelShape shape(BlockPos pos) {
            VoxelShape cached = shapes.get(pos);
            if (cached != null) return cached;

            BlockState state = getBlockState(pos);
            boolean air = AIR.contains(state.getBlock()), solid = SOLID.contains(state.getBlock());
            if ((!air && !solid) || state.hasBlockEntity() || !state.getFluidState().isEmpty())
                throw unknown(UnknownReason.UNSUPPORTED_STATE, CubePos.fromBlock(pos.getX(), pos.getY(), pos.getZ()));
            // Native code gets the scoped getter; any new dependency must also be present/current.
            VoxelShape shape = state.getCollisionShape(this, pos, CollisionContext.empty());
            List<AABB> boxes = shape.toAabbs();
            if (air ? !shape.isEmpty() : boxes.size() != 1 || !unitBox(boxes.getFirst()))
                throw unknown(UnknownReason.UNSUPPORTED_STATE, CubePos.fromBlock(pos.getX(), pos.getY(), pos.getZ()));
            shapes.put(pos.immutable(), shape);
            return shape;
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) {
            getBlockState(pos);
            throw unknown(UnknownReason.UNSUPPORTED_STATE, CubePos.fromBlock(pos.getX(), pos.getY(), pos.getZ()));
        }
        @Override public int getHeight() { return MAX_Y_EXCLUSIVE - MIN_Y; }
        @Override public int getMinBuildHeight() { return MIN_Y; }
    }

    private static boolean unitBox(AABB box) {
        return box.minX == 0 && box.minY == 0 && box.minZ == 0 && box.maxX == 1 && box.maxY == 1 && box.maxZ == 1;
    }
    private static void requirePoint(Vec3 point) {
        Objects.requireNonNull(point);
        // Leave a one-block arithmetic guard inside Java's int domain. Native domain checked on read.
        if (!Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)
                || point.x <= Integer.MIN_VALUE + 1.0 || point.x >= Integer.MAX_VALUE - 1.0
                || point.y <= Integer.MIN_VALUE + 1.0 || point.y >= Integer.MAX_VALUE - 1.0
                || point.z <= Integer.MIN_VALUE + 1.0 || point.z >= Integer.MAX_VALUE - 1.0)
            throw new IllegalArgumentException("Finite block-addressable coordinates required");
    }
    private static int initialCell(double value, double delta) {
        int floor = (int) Math.floor(value);
        return delta < 0 && value == floor ? floor - 1 : floor;
    }
    private static double nextBoundary(double start, double delta, int cell) {
        if (delta == 0) return Double.POSITIVE_INFINITY;
        return ((delta > 0 ? cell + 1.0 : cell) - start) / delta;
    }

    /** Exact segment/slab test over boxes obtained from Minecraft's registered VoxelShape. */
    private static Intersection intersect(AABB box, Vec3 start, Vec3 delta) {
        double near = Double.NEGATIVE_INFINITY, far = Double.POSITIVE_INFINITY;
        Direction face = null;
        double[] s = {start.x, start.y, start.z}, d = {delta.x, delta.y, delta.z};
        double[] min = {box.minX, box.minY, box.minZ}, max = {box.maxX, box.maxY, box.maxZ};
        Direction[] negative = {Direction.WEST, Direction.DOWN, Direction.NORTH};
        Direction[] positive = {Direction.EAST, Direction.UP, Direction.SOUTH};
        for (int axis = 0; axis < 3; axis++) {
            if (d[axis] == 0) {
                if (s[axis] < min[axis] || s[axis] >= max[axis]) return null;
                continue;
            }
            double entry = ((d[axis] > 0 ? min[axis] : max[axis]) - s[axis]) / d[axis];
            double exit = ((d[axis] > 0 ? max[axis] : min[axis]) - s[axis]) / d[axis];
            if (entry > near) { near = entry; face = d[axis] > 0 ? negative[axis] : positive[axis]; }
            far = Math.min(far, exit);
        }
        if (near > 1 || far <= Math.max(near, 0)) return null;
        boolean inside = near < 0;
        if (inside) face = Direction.getNearest(delta.x, delta.y, delta.z).getOpposite();
        return new Intersection(Math.max(near, 0), face, inside);
    }
    private static UnknownData unknown(UnknownReason reason, CubePos position) { return new UnknownData(new Unknown(reason, position)); }
    private static final class UnknownData extends RuntimeException {
        private final Unknown detail;
        private UnknownData(Unknown detail) { super(detail.toString(), null, false, false); this.detail = detail; }
    }
}
