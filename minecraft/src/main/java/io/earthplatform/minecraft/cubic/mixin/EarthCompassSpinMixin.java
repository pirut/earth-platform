package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.play.client.EarthCompassHud;
import javax.annotation.Nullable;
import net.minecraft.client.renderer.item.CompassItemPropertyFunction;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * During a Where on Earth round every compass spins, as in the Nether: a needle toward the spawn
 * or a famous place would hint at the answer, and two of them would pin it down.
 */
@Mixin(CompassItemPropertyFunction.class)
abstract class EarthCompassSpinMixin {
    @Inject(method = "isValidCompassTargetPos", at = @At("HEAD"), cancellable = true)
    private void earthplatform$spinInRound(Entity entity, @Nullable GlobalPos pos, CallbackInfoReturnable<Boolean> result) {
        if (EarthCompassHud.inRound()) result.setReturnValue(false);
    }
}
