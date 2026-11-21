package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.compat.SableOrigin;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The origin shift of {@link SableOriginMixin} for constraints. A constraint's anchor is in the local
 * space of the body it joins, which for the world itself (the physics staff drags a ship this way) is
 * world space, so those anchors need the same shift as a ship's position.
 */
@Pseudo
@Mixin(targets = {
        "dev.ryanhcode.sable.physics.impl.rapier.constraint.fixed.RapierFixedConstraintHandle",
        "dev.ryanhcode.sable.physics.impl.rapier.constraint.free.RapierFreeConstraintHandle",
        "dev.ryanhcode.sable.physics.impl.rapier.constraint.rotary.RapierRotaryConstraintHandle",
        "dev.ryanhcode.sable.physics.impl.rapier.constraint.generic.RapierGenericConstraintHandle",
        "dev.ryanhcode.sable.physics.impl.rapier.constraint.RapierConstraintHandle",
}, remap = false)
abstract class SableOriginConstraintMixin {
    private static final String R3D = "Ldev/ryanhcode/sable/physics/impl/rapier/Rapier3D;";

    private static double earthplatform$x(SableOrigin origin, int body, double x) { return origin != null && body == SableOrigin.WORLD ? x - origin.blockX() : x; }

    private static double earthplatform$z(SableOrigin origin, int body, double z) { return origin != null && body == SableOrigin.WORLD ? z - origin.blockZ() : z; }

    @WrapOperation(method = "create", require = 0, at = @At(value = "INVOKE", target = R3D + "addFixedConstraint(JIIDDDDDDDDDD)J"))
    private static long earthplatform$fixed(long scene, int a, int b, double p1x, double p1y, double p1z, double p2x, double p2y, double p2z,
                                            double qx, double qy, double qz, double qw, Operation<Long> original) {
        SableOrigin origin = SableOrigin.find(scene);
        long handle = original.call(scene, a, b, earthplatform$x(origin, a, p1x), p1y, earthplatform$z(origin, a, p1z),
                earthplatform$x(origin, b, p2x), p2y, earthplatform$z(origin, b, p2z), qx, qy, qz, qw);
        if (origin != null) origin.constraintAdded(handle, a, b);
        return handle;
    }

    /**
     * A free constraint whose frame 1 is the world is how the physics staff drags a ship: that frame sits
     * at the world's own origin (0, 0, 0) and the linear motors pull the ship's anchor to a goal measured
     * from it. Shifted like the other anchors it would land millions of blocks from the scene origin, and
     * Rapier's 32-bit maths would put the rotation pivot a block or more off the selected block. The
     * frame is moved to the scene origin instead, which a free constraint allows, and the linear motor
     * targets are moved by the same amount, in the frame's axes (see {@link #earthplatform$motor}).
     */
    @WrapOperation(method = "create", require = 0, at = @At(value = "INVOKE", target = R3D + "addFreeConstraint(JIIDDDDDDDDDD)J"))
    private static long earthplatform$free(long scene, int a, int b, double p1x, double p1y, double p1z, double p2x, double p2y, double p2z,
                                           double qx, double qy, double qz, double qw, Operation<Long> original) {
        SableOrigin origin = SableOrigin.find(scene);
        if (origin == null || a != SableOrigin.WORLD) {
            long handle = original.call(scene, a, b, earthplatform$x(origin, a, p1x), p1y, earthplatform$z(origin, a, p1z),
                    earthplatform$x(origin, b, p2x), p2y, earthplatform$z(origin, b, p2z), qx, qy, qz, qw);
            if (origin != null) origin.constraintAdded(handle, a, b);
            return handle;
        }
        Vector3d moved = new Quaterniond(qx, qy, qz, qw).transformInverse(new Vector3d(origin.blockX() - p1x, 0, origin.blockZ() - p1z));
        long handle = original.call(scene, a, b, 0.0, p1y, 0.0, earthplatform$x(origin, b, p2x), p2y, earthplatform$z(origin, b, p2z), qx, qy, qz, qw);
        origin.constraintAdded(handle, a, b);
        origin.motorShift(handle, new double[] {moved.x, moved.y, moved.z});
        return handle;
    }

    /** Linear motor targets are measured from the world frame, which {@link #earthplatform$free} moved. */
    @WrapOperation(method = "setMotor", require = 0, at = @At(value = "INVOKE", target = R3D + "setConstraintMotor(JJIDDDZD)V"))
    private void earthplatform$motor(long scene, long handle, int axis, double target, double stiffness, double damping, boolean flag, double max, Operation<Void> original) {
        SableOrigin origin = SableOrigin.find(scene);
        double[] moved = origin == null || axis > 2 ? null : origin.motorShift(handle);
        original.call(scene, handle, axis, moved == null ? target : target - moved[axis], stiffness, damping, flag, max);
    }

    @WrapOperation(method = "create", require = 0, at = @At(value = "INVOKE", target = R3D + "addRotaryConstraint(JIIDDDDDDDDDDDD)J"))
    private static long earthplatform$rotary(long scene, int a, int b, double p1x, double p1y, double p1z, double p2x, double p2y, double p2z,
                                             double a1x, double a1y, double a1z, double a2x, double a2y, double a2z, Operation<Long> original) {
        SableOrigin origin = SableOrigin.find(scene);
        long handle = original.call(scene, a, b, earthplatform$x(origin, a, p1x), p1y, earthplatform$z(origin, a, p1z),
                earthplatform$x(origin, b, p2x), p2y, earthplatform$z(origin, b, p2z), a1x, a1y, a1z, a2x, a2y, a2z);
        if (origin != null) origin.constraintAdded(handle, a, b);
        return handle;
    }

    @WrapOperation(method = "create", require = 0, at = @At(value = "INVOKE", target = R3D + "addGenericConstraint(JIIDDDDDDDDDDDDDDI)J"))
    private static long earthplatform$generic(long scene, int a, int b, double p1x, double p1y, double p1z, double p2x, double p2y, double p2z,
                                              double q1x, double q1y, double q1z, double q1w, double q2x, double q2y, double q2z, double q2w,
                                              int flags, Operation<Long> original) {
        SableOrigin origin = SableOrigin.find(scene);
        long handle = original.call(scene, a, b, earthplatform$x(origin, a, p1x), p1y, earthplatform$z(origin, a, p1z),
                earthplatform$x(origin, b, p2x), p2y, earthplatform$z(origin, b, p2z), q1x, q1y, q1z, q1w, q2x, q2y, q2z, q2w, flags);
        if (origin != null) origin.constraintAdded(handle, a, b);
        return handle;
    }

    /** The physics staff moves its world-side anchor with this each tick. */
    @WrapOperation(method = {"setFrame1", "setFrame2"}, require = 0, at = @At(value = "INVOKE", target = R3D + "setConstraintFrame(JJIDDDDDDD)V"))
    private void earthplatform$frame(long scene, long handle, int frame, double x, double y, double z, double qx, double qy, double qz, double qw, Operation<Void> original) {
        SableOrigin origin = SableOrigin.find(scene);
        if (origin != null && origin.worldFrame(handle, frame)) {
            x -= origin.blockX();
            z -= origin.blockZ();
        }
        original.call(scene, handle, frame, x, y, z, qx, qy, qz, qw);
    }

    @WrapOperation(method = "remove", require = 0, at = @At(value = "INVOKE", target = R3D + "removeConstraint(JJ)V"))
    private void earthplatform$forget(long scene, long handle, Operation<Void> original) {
        SableOrigin origin = SableOrigin.find(scene);
        if (origin != null) origin.constraintRemoved(handle);
        original.call(scene, handle);
    }
}
