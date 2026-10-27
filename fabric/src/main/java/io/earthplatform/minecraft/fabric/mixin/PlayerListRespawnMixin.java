package io.earthplatform.minecraft.fabric.mixin;

import io.earthplatform.minecraft.cubic.portal.CubicTravel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.portal.DimensionTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Where a player respawns, as NeoForge's PlayerRespawnPositionEvent: right after vanilla finds it. */
@Mixin(PlayerList.class)
abstract class PlayerListRespawnMixin {
    @ModifyVariable(method = "respawn", at = @At(value = "INVOKE_ASSIGN",
            target = "Lnet/minecraft/server/level/ServerPlayer;findRespawnPositionAndUseSpawnBlock(ZLnet/minecraft/world/level/portal/DimensionTransition$PostDimensionTransition;)Lnet/minecraft/world/level/portal/DimensionTransition;"))
    private DimensionTransition earthplatform$respawnAt(DimensionTransition found, ServerPlayer player) {
        return CubicTravel.home(player, found);
    }
}
