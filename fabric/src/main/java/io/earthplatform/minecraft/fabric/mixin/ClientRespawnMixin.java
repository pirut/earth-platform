package io.earthplatform.minecraft.fabric.mixin;

import io.earthplatform.minecraft.cubic.client.CubicClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The local player was replaced by a respawn, as NeoForge's ClientPlayerNetworkEvent.Clone. */
@Mixin(ClientPacketListener.class)
abstract class ClientRespawnMixin {
    @Inject(method = "handleRespawn", at = @At("TAIL"))
    private void earthplatform$respawned(ClientboundRespawnPacket packet, CallbackInfo info) { CubicClient.respawn(); }
}
