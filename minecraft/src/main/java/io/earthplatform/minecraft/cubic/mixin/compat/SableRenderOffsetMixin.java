package io.earthplatform.minecraft.cubic.mixin.compat;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import io.earthplatform.minecraft.cubic.CubicLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Sable draws Create's outlines and boxes on a ship (the physics staff's selection box, value boxes,
 * schematics and so on) by letting Create subtract the camera position from plot-space geometry, then
 * adding the camera back in the pose it pushes. Both halves cancel exactly in theory, but each is a
 * 32-bit float on its way to the GPU: far out in the Earth world the camera is millions of blocks
 * away, a float holds that to a block or worse, and the box lands off the block it outlines.
 *
 * <p>This moves the camera out of both halves, so no large number ever goes through a float: the
 * translation Sable hands its callers already has the camera taken off (they subtract it, which now
 * brings the geometry to the ship's own space directly), and the pose no longer adds it back. The
 * callers all use the translation as "geometry minus it", next to a camera-relative origin, so the
 * result is identical in theory and exact in practice. Only in the Earth world.
 */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.util.SublevelRenderOffsetHelper", remap = false)
abstract class SableRenderOffsetMixin {
    private static boolean earthplatform$earth() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level != null && CubicLevel.of(minecraft.level) != null;
    }

    /** The second translate of {@code posePlotToProjected} adds the camera back; it is no longer needed. */
    @WrapOperation(method = "posePlotToProjected", require = 0, at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V", ordinal = 1))
    private static void earthplatform$keepCamera(PoseStack stack, double x, double y, double z, Operation<Void> original) {
        if (!earthplatform$earth()) original.call(stack, x, y, z);
    }

    @ModifyReturnValue(method = "translation", require = 0, at = @At("RETURN"))
    private static Vec3 earthplatform$relativeTranslation(Vec3 translation) {
        if (translation == Vec3.ZERO || !earthplatform$earth()) return translation;
        return translation.subtract(Minecraft.getInstance().gameRenderer.getMainCamera().getPosition());
    }
}
