package io.earthplatform.minecraft.fabric.mixin;

import io.earthplatform.minecraft.cubic.client.CubicClient;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A client level is made, as NeoForge's LevelEvent.Load at the end of its constructor. */
@Mixin(ClientLevel.class)
abstract class ClientLevelLoadMixin {
    @Inject(method = "<init>", at = @At("TAIL"))
    private void earthplatform$load(CallbackInfo info) { CubicClient.load((ClientLevel) (Object) this); }
}
