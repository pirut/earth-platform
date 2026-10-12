package io.earthplatform.minecraft.mixin;
import io.earthplatform.minecraft.survival.interaction.host.NativePlayerStepReadScope;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
/** Exact doCheckFallDamage receiver only. Never overrides touchingUnloadedChunk globally. */
@Mixin(ServerPlayer.class)
abstract class NativePlayerStepFallMixin {
 @Redirect(method="doCheckFallDamage(DDDZ)V",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerPlayer;touchingUnloadedChunk()Z"),require=1)
 private boolean earthplatform$exactPlayerFallResidency(ServerPlayer source){return NativePlayerStepReadScope.fallResidency(source);}
}
