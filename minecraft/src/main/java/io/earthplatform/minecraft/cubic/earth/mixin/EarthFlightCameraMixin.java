package io.earthplatform.minecraft.cubic.earth.mixin;

import io.earthplatform.minecraft.cubic.play.client.EarthFlight;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** During a trip from the travel map the camera leaves the player and flies; see {@link EarthFlight}. */
@Mixin(Camera.class)
abstract class EarthFlightCameraMixin {
    @Shadow private boolean detached;
    @Shadow protected abstract void setPosition(Vec3 position);
    @Shadow protected abstract void setRotation(float yRot, float xRot);

    @Inject(method = "setup", at = @At("TAIL"))
    private void earthplatform$flight(BlockGetter level, Entity entity, boolean detached, boolean mirrored, float partialTick, CallbackInfo info) {
        double[] view = EarthFlight.camera(entity, partialTick);
        if (view == null) return;
        if (view[5] > 0) this.detached = true;
        setRotation((float) view[3], (float) view[4]);
        setPosition(new Vec3(view[0], view[1], view[2]));
    }
}
