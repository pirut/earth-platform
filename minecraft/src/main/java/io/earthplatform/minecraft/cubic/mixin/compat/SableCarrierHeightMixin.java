package io.earthplatform.minecraft.cubic.mixin.compat;

import io.earthplatform.minecraft.cubic.compat.SableCarrier;
import io.earthplatform.minecraft.cubic.compat.SableCompat;
import net.minecraft.world.level.LevelReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The build range a level reports, widened for the cubic carrier when Sable needs room for ships. */
@Mixin(LevelReader.class)
interface SableCarrierHeightMixin {
    @Inject(method = "getMinBuildHeight()I", at = @At("HEAD"), cancellable = true)
    private void earthplatform$carrierBottom(CallbackInfoReturnable<Integer> result) {
        if (this instanceof SableCarrier carrier && carrier.earthplatform$tallCarrier()) result.setReturnValue(SableCompat.CARRIER_MIN_Y);
    }

    @Inject(method = "getHeight()I", at = @At("HEAD"), cancellable = true)
    private void earthplatform$carrierHeight(CallbackInfoReturnable<Integer> result) {
        if (this instanceof SableCarrier carrier && carrier.earthplatform$tallCarrier()) result.setReturnValue(SableCompat.CARRIER_HEIGHT);
    }
}
