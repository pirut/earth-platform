package io.earthplatform.minecraft.cubic.mixin;

import io.earthplatform.minecraft.cubic.jet.JetStream;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A player riding the jet stream crosses dozens of carrier chunks a second high above any
 * terrain, so like a spectator it keeps no chunks loaded until it comes back down.
 */
@Mixin(ChunkMap.class)
abstract class CubicJetTicketMixin {
    @Inject(method = "skipPlayer", at = @At("HEAD"), cancellable = true)
    private void earthplatform$skipJetRiders(ServerPlayer player, CallbackInfoReturnable<Boolean> info) {
        if (JetStream.aboveTerrain(player)) info.setReturnValue(true);
    }
}
