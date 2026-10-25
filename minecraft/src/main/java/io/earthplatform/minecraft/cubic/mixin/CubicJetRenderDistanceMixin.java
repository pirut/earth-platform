package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.jet.JetStream;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A gliding player is drawn only within about 38 blocks, which a jet stream rider crosses in under a
 * tick, so a camera chasing it lost it at every frame's small lag. A fast glider in the cubic world is
 * drawn as far as it travels in a few ticks.
 */
@Mixin(Entity.class)
abstract class CubicJetRenderDistanceMixin {
    @Inject(method = "shouldRenderAtSqrDistance", at = @At("HEAD"), cancellable = true)
    private void earthplatform$fastGlider(double distance, CallbackInfoReturnable<Boolean> result) {
        if ((Object) this instanceof LivingEntity living && JetStream.remoteGlider(living)
                && distance < JetStream.drawReachSqr(living.getX() - living.xo, living.getY() - living.yo, living.getZ() - living.zo)) {
            result.setReturnValue(true);
        }
    }
}
