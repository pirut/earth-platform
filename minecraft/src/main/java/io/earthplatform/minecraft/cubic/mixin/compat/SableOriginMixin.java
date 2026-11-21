package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.CubicLevel;
import io.earthplatform.minecraft.cubic.compat.SableOrigin;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps Sable's 32-bit Rapier scene near zero in the cubic level by subtracting a fixed origin from
 * every world position it is given and adding it back to every pose read out (see {@link SableOrigin}).
 * Ship plots and ship-local data are left alone. Boxes and ropes are not covered.
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline", remap = false)
abstract class SableOriginMixin {
    @Shadow private ServerLevel level;

    private static final String R3D = "Ldev/ryanhcode/sable/physics/impl/rapier/Rapier3D;";

    /** Pose arrays are x, y, z, then a quaternion: shift x and z. */
    private static void earthplatform$toPhysics(SableOrigin origin, double[] pose) {
        pose[0] -= origin.blockX();
        pose[2] -= origin.blockZ();
    }

    /** A scene exists here from the start, so boxes and ropes made before any ship share the same origin. */
    @WrapOperation(method = "init", require = 0, at = @At(value = "INVOKE", target = R3D + "initialize(DDDD)J"))
    private long earthplatform$init(double a, double b, double c, double d, Operation<Long> original) {
        long scene = original.call(a, b, c, d);
        if (CubicLevel.of(level) != null) SableOrigin.of(scene);
        return scene;
    }

    @WrapOperation(method = "add", require = 0, at = @At(value = "INVOKE", target = R3D + "createSubLevel(JI[D)V"))
    private void earthplatform$createShip(long scene, int id, double[] pose, Operation<Void> original) {
        if (CubicLevel.of(level) != null) {
            SableOrigin origin = SableOrigin.of(scene);
            origin.bodyAdded(pose[0], pose[2]);
            earthplatform$toPhysics(origin, pose);
        }
        original.call(scene, id, pose);
    }

    @WrapOperation(method = "add", require = 0, at = @At(value = "INVOKE", target = R3D + "createKinematicContraption(JII[D)V"))
    private void earthplatform$createContraption(long scene, int id, int shipId, double[] pose, Operation<Void> original) {
        if (CubicLevel.of(level) != null) {
            SableOrigin origin = SableOrigin.of(scene);
            origin.bodyAdded(pose[0], pose[2]);
            earthplatform$toPhysics(origin, pose);
        }
        original.call(scene, id, shipId, pose);
    }

    @WrapOperation(method = "remove", require = 0, at = @At(value = "INVOKE", target = R3D + "removeSubLevel(JI)V"))
    private void earthplatform$removeShip(long scene, int id, Operation<Void> original) {
        SableOrigin origin = SableOrigin.find(scene);
        if (origin != null) origin.bodyRemoved();
        original.call(scene, id);
    }

    @WrapOperation(method = "remove", require = 0, at = @At(value = "INVOKE", target = R3D + "removeKinematicContraption(JI)V"))
    private void earthplatform$removeContraption(long scene, int id, Operation<Void> original) {
        SableOrigin origin = SableOrigin.find(scene);
        if (origin != null) origin.bodyRemoved();
        original.call(scene, id);
    }

    @WrapOperation(method = "dispose", require = 0, at = @At(value = "INVOKE", target = R3D + "dispose(J)V"))
    private void earthplatform$dispose(long scene, Operation<Void> original) {
        SableOrigin.drop(scene);
        original.call(scene);
    }

    @WrapOperation(method = "updateContraptionPose", require = 0, at = @At(value = "INVOKE",
            target = R3D + "setKinematicContraptionTransform(JI[D[D[D)V"))
    private void earthplatform$moveContraption(long scene, int id, double[] centerOfMass, double[] pose, double[] velocity, Operation<Void> original) {
        SableOrigin origin = SableOrigin.find(scene);
        if (origin != null) earthplatform$toPhysics(origin, pose);
        original.call(scene, id, centerOfMass, pose, velocity);
    }

    @WrapOperation(method = "teleport", require = 0, at = @At(value = "INVOKE", target = R3D + "teleportObject(JIDDDDDDD)V"))
    private void earthplatform$teleport(long scene, int id, double x, double y, double z, double qx, double qy, double qz, double qw, Operation<Void> original) {
        SableOrigin origin = SableOrigin.find(scene);
        if (origin != null) {
            x -= origin.blockX();
            z -= origin.blockZ();
        }
        original.call(scene, id, x, y, z, qx, qy, qz, qw);
    }

    @WrapOperation(method = "readPose", require = 0, at = @At(value = "INVOKE", target = R3D + "getPose(JI[D)V"))
    private void earthplatform$readPose(long scene, int id, double[] pose, Operation<Void> original) {
        original.call(scene, id, pose);
        SableOrigin origin = SableOrigin.find(scene);
        if (origin != null) {
            pose[0] += origin.blockX();
            pose[2] += origin.blockZ();
        }
    }

    /** World terrain sections only; a section inside a ship's plot is the ship's own and stays put. */
    @WrapOperation(method = "handleChunkSectionAddition", require = 0, at = @At(value = "INVOKE", target = R3D + "addChunk(JIII[IZI)V"))
    private void earthplatform$addChunk(long scene, int x, int y, int z, int[] states, boolean world, int shipId, Operation<Void> original) {
        if (world && CubicLevel.of(level) != null) {
            SableOrigin origin = SableOrigin.of(scene);
            origin.chunkAdded(x, y, z);
            original.call(scene, origin.chunkX(x), y, origin.chunkZ(z), states, world, shipId);
        } else {
            original.call(scene, x, y, z, states, world, shipId);
        }
    }

    @WrapOperation(method = "handleChunkSectionRemoval", require = 0, at = @At(value = "INVOKE", target = R3D + "removeChunk(JIIIZ)V"))
    private void earthplatform$removeChunk(long scene, int x, int y, int z, boolean world, Operation<Void> original) {
        SableOrigin origin = world ? SableOrigin.find(scene) : null;
        if (origin != null) {
            origin.chunkRemoved(x, y, z);
            original.call(scene, origin.chunkX(x), y, origin.chunkZ(z), world);
        } else {
            original.call(scene, x, y, z, world);
        }
    }

    @WrapOperation(method = "handleBlockChange", require = 0, at = @At(value = "INVOKE", target = R3D + "changeBlock(JIIII)V"))
    private void earthplatform$changeBlock(long scene, int x, int y, int z, int state, Operation<Void> original) {
        if (CubicLevel.at(level, x, z) != null) {
            SableOrigin origin = SableOrigin.find(scene);
            // Nothing of the world is in the scene yet, so there is no section to edit.
            if (origin == null || !origin.isSet()) return;
            original.call(scene, x - origin.blockX(), y, z - origin.blockZ(), state);
        } else {
            original.call(scene, x, y, z, state);
        }
    }
}
