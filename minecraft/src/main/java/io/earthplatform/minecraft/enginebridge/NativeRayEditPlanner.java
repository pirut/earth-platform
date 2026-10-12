package io.earthplatform.minecraft.enginebridge;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Pure, bounded planning for the first server-authoritative native mouse-edit gate.
 * Call on a bounded CPU worker, after copying at most 125 admitted immutable snapshots and the
 * ServerPlayer's eye/look/body values on the server thread. No client target, Level, player,
 * provider, executor, ticket, or disk owner is accepted or retained. Registries must be frozen.
 *
 * <p>A plan is evidence about that captured view, not edit admission. On the server thread,
 * revalidate every dependency against the current server snapshot map immediately before
 * {@link NativeCubeActor#tryEdit}; the actor must still validate the target's exact revision.
 * The caller also owns session/cancellation, player-pose freshness and permission checks.
 * This helper implements neither vanilla world edits nor general entity/fluid physics.
 */
public final class NativeRayEditPlanner {
    private NativeRayEditPlanner() {}

    public enum Action { PLACE_DIORITE, REMOVE }
    public enum RejectionReason {
        INVALID_INPUT, INVALID_REACH, INVALID_LOOK, INVALID_BODY, TOO_MANY_SNAPSHOTS,
        INVALID_SNAPSHOTS, NO_HIT, UNKNOWN_DATA, OUTSIDE_EDITABLE_CUBE,
        TARGET_NOT_AIR, TARGET_BODY_OVERLAP
    }
    public sealed interface Result permits Plan, Rejected {}

    public record Rejected(RejectionReason reason, NativeKnownCubeQueries.Unknown unknown) implements Result {
        public Rejected { java.util.Objects.requireNonNull(reason); }
        public String detail() {
            return unknown == null ? reason.name() : reason.name() + ": " + unknown.reason() + " at " + unknown.position();
        }
    }

    /** Immutable result; only this planner can construct one. */
    public static final class Plan implements Result {
        private final NativeCubeActor.EditRequest request;
        private final BlockHitResult hit;
        private final NativeKnownCubeQueries.Dependencies dependencies;

        private Plan(NativeCubeActor.EditRequest request, BlockHitResult hit,
                     NativeKnownCubeQueries.Dependencies dependencies) {
            this.request = request;
            this.hit = new BlockHitResult(hit.getLocation(), hit.getDirection(), hit.getBlockPos().immutable(), hit.isInside());
            this.dependencies = dependencies;
        }
        public NativeCubeActor.EditRequest request() { return request; }
        public BlockHitResult hit() { return hit; }
        public NativeKnownCubeQueries.Dependencies dependencies() { return dependencies; }

        /**
         * Read only on the server owner thread, with no intervening admission changes before
         * tryEdit. Includes every ray and destination halo owner, not merely the edited cube.
         * An unchanged target cannot rescue a changed/missing sight-line or halo dependency.
         * Snapshot identity changes must advance their lease or revision as usual.
         */
        public boolean isCurrent(Map<CubePos, EngineCubeSnapshot> currentSnapshots) {
            return currentSnapshots != null && currentSnapshots.size() <= NativeKnownCubeQueries.MAX_OWNERS
                    && dependencies.isCurrent(stamp -> matches(stamp, currentSnapshots.get(stamp.position())));
        }
    }

    /**
     * Normalize the server's finite, nonzero look vector and cast at most eight metres. REMOVE
     * targets the nearest supported native collision; PLACE_DIORITE offsets its actual hit face
     * and requires the entire destination cell and one-cell halo to be known/supported air or
     * full-block states. Unknown space is never skipped in search of a later hit. A supplied
     * player body must be finite and fit the native query's positive 1x2x1 bound; strict overlap
     * with the placed unit block is rejected, while mere face contact is permitted.
     *
     * <p>The first runtime caller pins editableCube to (0,8,0); this value is server policy,
     * never a value accepted from a client packet. A null body is allowed only when the caller
     * has no body to protect. Decoding/snapshot validation belongs on the worker, not the tick.
     */
    public static Result plan(WorldIdentity world, long epoch, EnginePaletteContext palette,
                              Map<CubePos, EngineCubeSnapshot> snapshots, Vec3 eye, Vec3 look,
                              double reach, Action action, CubePos editableCube, AABB playerBody) {
        return planInternal(world,epoch,palette,snapshots,eye,look,reach,action,editableCube == null ? null : editableCube::equals,playerBody);
    }
    /** Pure fixed-arena ray policy. The actor independently checks full64 residency and durable revisions. */
    public static Result planArena(WorldIdentity world,long epoch,EnginePaletteContext palette,
            Map<CubePos,EngineCubeSnapshot> snapshots,Vec3 eye,Vec3 look,double reach,Action action,
            ArenaDefinition arena,AABB playerBody) {
        return planInternal(world,epoch,palette,snapshots,eye,look,reach,action,arena == null ? null : arena::contains,playerBody);
    }
    private static Result planInternal(WorldIdentity world,long epoch,EnginePaletteContext palette,
            Map<CubePos,EngineCubeSnapshot> snapshots,Vec3 eye,Vec3 look,double reach,Action action,
            java.util.function.Predicate<CubePos> permitted,AABB playerBody) {
        if (world == null || epoch < 0 || palette == null || snapshots == null
                || action == null || permitted == null || !addressable(eye)) return reject(RejectionReason.INVALID_INPUT);
        if (!Double.isFinite(reach) || reach <= 0 || reach > NativeKnownCubeQueries.MAX_REACH)
            return reject(RejectionReason.INVALID_REACH);
        if (!finite(look)) return reject(RejectionReason.INVALID_LOOK);
        double scale = Math.max(Math.abs(look.x), Math.max(Math.abs(look.y), Math.abs(look.z)));
        if (scale == 0) return reject(RejectionReason.INVALID_LOOK);
        // Scaling first handles even finite inputs whose naive length squared over/underflows.
        Vec3 scaled = new Vec3(look.x / scale, look.y / scale, look.z / scale);
        Vec3 direction = scaled.scale(1.0 / Math.sqrt(scaled.lengthSqr()));
        Vec3 end = boundedEnd(eye, direction, reach);
        if (!addressable(end) || !(end.subtract(eye).lengthSqr() > 0)) return reject(RejectionReason.INVALID_REACH);
        if (playerBody != null && !validBody(playerBody)) return reject(RejectionReason.INVALID_BODY);
        if (snapshots.size() > NativeKnownCubeQueries.MAX_OWNERS) return reject(RejectionReason.TOO_MANY_SNAPSHOTS);

        final Map<CubePos, EngineCubeSnapshot> captured;
        final NativeKnownCubeQueries query;
        try {
            captured = Map.copyOf(snapshots);
            query = new NativeKnownCubeQueries(world, epoch, palette, captured);
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException | io.netty.handler.codec.DecoderException invalid) {
            return reject(RejectionReason.INVALID_SNAPSHOTS);
        }
        NativeKnownCubeQueries.CurrentStamp current = stamp -> matches(stamp, captured.get(stamp.position()));
        var ray = query.raycast(eye, end, current);
        if (ray.status() == NativeKnownCubeQueries.Status.UNKNOWN) return unknown(ray.unknown());
        if (ray.status() != NativeKnownCubeQueries.Status.HIT) return reject(RejectionReason.NO_HIT);
        BlockHitResult hit = ray.hit();
        BlockPos target = action == Action.REMOVE ? hit.getBlockPos() : hit.getBlockPos().relative(hit.getDirection());
        CubePos targetCube = CubePos.fromBlock(target.getX(), target.getY(), target.getZ());
        if (!permitted.test(targetCube)) return reject(RejectionReason.OUTSIDE_EDITABLE_CUBE);

        NativeKnownCubeQueries.Dependencies dependencies = ray.dependencies();
        if (action == Action.PLACE_DIORITE) {
            AABB destination = new AABB(target);
            var occupancy = query.overlaps(destination, current);
            if (occupancy.status() == NativeKnownCubeQueries.Status.UNKNOWN) return unknown(occupancy.unknown());
            if (occupancy.status() != NativeKnownCubeQueries.Status.CLEAR) return reject(RejectionReason.TARGET_NOT_AIR);
            if (playerBody != null && destination.intersects(playerBody)) return reject(RejectionReason.TARGET_BODY_OVERLAP);
            dependencies = merge(dependencies, occupancy.dependencies());
        }
        EngineCubeSnapshot targetSnapshot = captured.get(targetCube);
        // Successful native reads include this owner. Keep the invariant explicit at the boundary.
        if (targetSnapshot == null || !dependencies.isCurrent(current))
            return unknown(new NativeKnownCubeQueries.Unknown(NativeKnownCubeQueries.UnknownReason.STALE_DEPENDENCY, targetCube));
        int stateId = Block.BLOCK_STATE_REGISTRY.getId((action == Action.REMOVE ? Blocks.AIR : Blocks.DIORITE).defaultBlockState());
        var request = new NativeCubeActor.EditRequest(world, epoch, targetCube, targetSnapshot.leaseGeneration(),
                targetSnapshot.revision(), target.getX(), target.getY(), target.getZ(), stateId);
        return new Plan(request, hit, dependencies);
    }

    private static boolean matches(NativeKnownCubeQueries.CubeStamp stamp, EngineCubeSnapshot snapshot) {
        return snapshot != null && stamp.world().equals(snapshot.world()) && stamp.epoch() == snapshot.epoch()
                && stamp.position().equals(snapshot.position()) && stamp.leaseGeneration() == snapshot.leaseGeneration()
                && stamp.revision() == snapshot.revision();
    }
    private static NativeKnownCubeQueries.Dependencies merge(NativeKnownCubeQueries.Dependencies first,
                                                            NativeKnownCubeQueries.Dependencies second) {
        var stamps = new LinkedHashMap<CubePos, NativeKnownCubeQueries.CubeStamp>();
        for (var stamp : first.stamps()) stamps.put(stamp.position(), stamp);
        for (var stamp : second.stamps()) stamps.put(stamp.position(), stamp);
        return new NativeKnownCubeQueries.Dependencies(List.copyOf(stamps.values()));
    }
    private static Rejected reject(RejectionReason reason) { return new Rejected(reason, null); }
    private static Rejected unknown(NativeKnownCubeQueries.Unknown unknown) { return new Rejected(RejectionReason.UNKNOWN_DATA, unknown); }
    private static boolean finite(Vec3 point) {
        return point != null && Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }
    private static boolean addressable(Vec3 point) {
        return finite(point) && point.x > Integer.MIN_VALUE + 1.0 && point.x < Integer.MAX_VALUE - 1.0
                && point.y > Integer.MIN_VALUE + 1.0 && point.y < Integer.MAX_VALUE - 1.0
                && point.z > Integer.MIN_VALUE + 1.0 && point.z < Integer.MAX_VALUE - 1.0;
    }
    private static boolean validBody(AABB body) {
        return addressable(new Vec3(body.minX, body.minY, body.minZ)) && addressable(new Vec3(body.maxX, body.maxY, body.maxZ))
                && body.getXsize() > 0 && body.getXsize() <= NativeKnownCubeQueries.MAX_BODY_WIDTH
                && body.getZsize() > 0 && body.getZsize() <= NativeKnownCubeQueries.MAX_BODY_WIDTH
                && body.getYsize() > 0 && body.getYsize() <= NativeKnownCubeQueries.MAX_BODY_HEIGHT;
    }
    private static Vec3 boundedEnd(Vec3 eye, Vec3 direction, double reach) {
        Vec3 end = eye.add(direction.scale(reach));
        if (end.subtract(eye).lengthSqr() <= reach * reach) return end;
        // World-scale addition can round an otherwise normalized 8m segment just over budget.
        // Move the represented endpoint towards the eye, never extend authoritative reach.
        for (int attempt = 0; attempt < 4; attempt++) {
            end = new Vec3(Math.nextAfter(end.x, eye.x), Math.nextAfter(end.y, eye.y), Math.nextAfter(end.z, eye.z));
            if (end.subtract(eye).lengthSqr() <= reach * reach) return end;
        }
        return eye; // Degenerate/unrepresentable input fails closed at the caller.
    }
}
