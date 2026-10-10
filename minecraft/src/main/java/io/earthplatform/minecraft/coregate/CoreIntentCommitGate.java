package io.earthplatform.minecraft.coregate;

import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.enginebridge.NativeKnownCubeQueries;
import io.earthplatform.minecraft.enginebridge.NativeRayEditPlanner;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Pure final admission checks for a completed mouse plan. The server owner supplies current
 * values and must call the actor immediately after acceptance, with no intervening mutation.
 * activeOperatorSession includes ownership, epoch/session identity, permission, carrier and
 * spectator checks. This helper does not admit work, retain owners, or perform any world I/O.
 */
public final class CoreIntentCommitGate {
    public static final double MAX_EYE_DISTANCE_SQUARED = 0.25;
    public static final double MIN_LOOK_DOT = 0.995;
    private CoreIntentCommitGate() {}

    /** Returns a bounded rejection explanation, or null when these final checks pass. */
    public static String evaluate(boolean activeOperatorSession, Vec3 capturedEye, Vec3 capturedLook,
                                  Vec3 currentEye, Vec3 currentLook, AABB currentBody,
                                  NativeRayEditPlanner.Plan plan,
                                  Map<CubePos, EngineCubeSnapshot> currentSnapshots) {
        if (!activeOperatorSession) return "Operator session changed before edit admission";
        if (!finite(capturedEye) || !finite(currentEye) || plan == null)
            return "Invalid authoritative camera or plan";
        Vec3 capturedDirection = direction(capturedLook), currentDirection = direction(currentLook);
        if (capturedDirection == null || currentDirection == null)
            return "Invalid authoritative camera direction";
        if (!validBody(currentBody)) return "Invalid current player body";
        if (currentEye.distanceToSqr(capturedEye) > MAX_EYE_DISTANCE_SQUARED
                || currentDirection.dot(capturedDirection) < MIN_LOOK_DOT)
            return "Camera moved during authoritative ray preparation";
        if (!plan.isCurrent(currentSnapshots)) return "Ray dependencies changed before edit admission";
        if (currentEye.distanceToSqr(plan.hit().getLocation())
                > NativeKnownCubeQueries.MAX_REACH * NativeKnownCubeQueries.MAX_REACH)
            return "Target moved outside current authoritative reach";
        var request = plan.request();
        if (request.stateId() != Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState())) {
            var destination = new AABB(new BlockPos(Math.toIntExact(request.x()),
                    Math.toIntExact(request.y()), Math.toIntExact(request.z())));
            if (destination.intersects(currentBody)) return "Current player body overlaps placement cell";
        }
        return null;
    }

    private static boolean finite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.x) && Double.isFinite(vector.y)
                && Double.isFinite(vector.z);
    }
    private static Vec3 direction(Vec3 vector) {
        if (!finite(vector)) return null;
        double scale = Math.max(Math.abs(vector.x), Math.max(Math.abs(vector.y), Math.abs(vector.z)));
        if (scale == 0) return null;
        Vec3 scaled = new Vec3(vector.x / scale, vector.y / scale, vector.z / scale);
        return scaled.scale(1.0 / Math.sqrt(scaled.lengthSqr()));
    }
    private static boolean validBody(AABB body) {
        return body != null && finite(new Vec3(body.minX, body.minY, body.minZ))
                && finite(new Vec3(body.maxX, body.maxY, body.maxZ))
                && body.getXsize() > 0 && body.getXsize() <= NativeKnownCubeQueries.MAX_BODY_WIDTH
                && body.getZsize() > 0 && body.getZsize() <= NativeKnownCubeQueries.MAX_BODY_WIDTH
                && body.getYsize() > 0 && body.getYsize() <= NativeKnownCubeQueries.MAX_BODY_HEIGHT;
    }
}
