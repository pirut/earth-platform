package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.play.client.EarthSolarClient;
import java.util.function.BooleanSupplier;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps {@link ClientLevel#getSkyDarken()} and {@code isDay()} aligned with localized solar time. */
@Mixin(ClientLevel.class)
abstract class CubicClientSkyTickMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void earthplatform$updateLocalSkyBrightness(BooleanSupplier hasTimeLeft, CallbackInfo info) {
        ClientLevel self = (ClientLevel) (Object) this;
        if (EarthSolarClient.active(self)) self.updateSkyBrightness();
    }
}
