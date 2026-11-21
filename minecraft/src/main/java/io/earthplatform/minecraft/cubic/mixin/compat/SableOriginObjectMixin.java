package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.compat.SableOrigin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The origin shift of {@link SableOriginMixin} for physics boxes and ropes. Sable gives them their own
 * terrain collision, so they have to live in the same shifted space as the terrain and the ships they
 * are tied to, or a rope pulls its contraption toward a point millions of blocks away.
 */
@Pseudo
@Mixin(targets = {
        "dev.ryanhcode.sable.physics.impl.rapier.box.RapierBoxHandle",
        "dev.ryanhcode.sable.physics.impl.rapier.rope.RapierRopeHandle",
}, remap = false)
abstract class SableOriginObjectMixin {
    private static final String R3D = "Ldev/ryanhcode/sable/physics/impl/rapier/Rapier3D;";

    @WrapOperation(method = "create", require = 0, at = @At(value = "INVOKE", target = R3D + "createBox(JIDDDD[D)V"))
    private static void earthplatform$createBox(long scene, int id, double hx, double hy, double hz, double mass, double[] pose, Operation<Void> original) {
        SableOrigin origin = SableOrigin.find(scene);
        if (origin != null) {
            origin.bodyAdded(pose[0], pose[2]);
            pose[0] -= origin.blockX();
            pose[2] -= origin.blockZ();
        }
        original.call(scene, id, hx, hy, hz, mass, pose);
    }

    @WrapOperation(method = "readPose", require = 0, at = @At(value = "INVOKE", target = R3D + "getPose(JI[D)V"))
    private void earthplatform$readBox(long scene, int id, double[] pose, Operation<Void> original) {
        original.call(scene, id, pose);
        SableOrigin origin = SableOrigin.find(scene);
        if (origin != null) {
            pose[0] += origin.blockX();
            pose[2] += origin.blockZ();
        }
    }

    @WrapOperation(method = "remove", require = 0, at = @At(value = "INVOKE", target = R3D + "removeBox(JI)V"))
    private void earthplatform$removeBox(long scene, int id, Operation<Void> original) {
        SableOrigin origin = SableOrigin.find(scene);
        if (origin != null) origin.bodyRemoved();
        original.call(scene, id);
    }

    @WrapOperation(method = "create", require = 0, at = @At(value = "INVOKE", target = R3D + "createRope(JDD[DI)J"))
    private static long earthplatform$createRope(long scene, double radius, double segment, double[] points, int count, Operation<Long> original) {
        SableOrigin origin = SableOrigin.find(scene);
        if (origin != null && count > 0) {
            origin.bodyAdded(points[0], points[2]);
            origin.toPhysics(points, count);
        }
        return original.call(scene, radius, segment, points, count);
    }

    @WrapOperation(method = "readPose", require = 0, at = @At(value = "INVOKE", target = R3D + "queryRope(JJ)[D"))
    private double[] earthplatform$readRope(long scene, long handle, Operation<double[]> original) {
        double[] points = original.call(scene, handle);
        SableOrigin origin = SableOrigin.find(scene);
        if (origin != null && points != null) origin.fromPhysics(points);
        return points;
    }

    @WrapOperation(method = "addPoint", require = 0, at = @At(value = "INVOKE", target = R3D + "addRopePointAtStart(JJDDD)V"))
    private void earthplatform$addRopePoint(long scene, long handle, double x, double y, double z, Operation<Void> original) {
        SableOrigin origin = SableOrigin.find(scene);
        if (origin != null) {
            x -= origin.blockX();
            z -= origin.blockZ();
        }
        original.call(scene, handle, x, y, z);
    }

    /** An attachment to no ship (id -1) is a point in the world. */
    @WrapOperation(method = "setAttachment", require = 0, at = @At(value = "INVOKE", target = R3D + "setRopeAttachment(JJIDDDZ)V"))
    private void earthplatform$attachRope(long scene, long handle, int body, double x, double y, double z, boolean end, Operation<Void> original) {
        SableOrigin origin = SableOrigin.find(scene);
        if (origin != null && body == SableOrigin.WORLD) {
            x -= origin.blockX();
            z -= origin.blockZ();
        }
        original.call(scene, handle, body, x, y, z, end);
    }

    @WrapOperation(method = "remove", require = 0, at = @At(value = "INVOKE", target = R3D + "removeRope(JJ)J"))
    private long earthplatform$removeRope(long scene, long handle, Operation<Long> original) {
        SableOrigin origin = SableOrigin.find(scene);
        if (origin != null) origin.bodyRemoved();
        return original.call(scene, handle);
    }
}
