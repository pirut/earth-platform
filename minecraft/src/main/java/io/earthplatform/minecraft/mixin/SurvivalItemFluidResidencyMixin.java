package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.survival.interaction.host.NativeItemTickReadScope;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Only the exact accepted native item tick may replace its legacy X/Z-only fluid probe. */
@Mixin(Entity.class)
abstract class SurvivalItemFluidResidencyMixin {
    @Inject(method = "touchingUnloadedChunk()Z", at = @At("HEAD"), cancellable = true)
    private void earthplatform$trueCoordinateFluidResidency(CallbackInfoReturnable<Boolean> callback) {
        Entity source = (Entity) (Object) this;
        if (NativeItemTickReadScope.isExecuting(source))
            callback.setReturnValue(NativeItemTickReadScope.touchingUnloadedArea(source));
    }
}
