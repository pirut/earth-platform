package io.earthplatform.minecraft.cubic.earth.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.cubic.jet.JetStream;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Jet stream speeds are far past vanilla's elytra speed check; skip it for gliders in the cubic world, who also keep jet speed for a while after leaving the wind. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class CubicJetMoveCheckMixin {
    @Shadow public ServerPlayer player;

    @WrapOperation(method = "handleMovePlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/GameRules;getBoolean(Lnet/minecraft/world/level/GameRules$Key;)Z"))
    private boolean earthplatform$jetSpeed(GameRules rules, GameRules.Key<GameRules.BooleanValue> key, Operation<Boolean> original) {
        if (key == GameRules.RULE_DISABLE_ELYTRA_MOVEMENT_CHECK && player.isFallFlying() && JetStream.inCubic(player)) return true;
        return original.call(rules, key);
    }
}
