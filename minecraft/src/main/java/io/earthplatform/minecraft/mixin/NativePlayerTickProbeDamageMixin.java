package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.survival.gametest.NativePlayerTickProbeScope;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Damage is outside the dry profile even if native spawn immunity would silently reject it. */
@Mixin(ServerPlayer.class)
abstract class NativePlayerTickProbeDamageMixin {
    @Inject(method="hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",at=@At("HEAD"),require=1)
    private void earthplatform$rejectDiagnosticDamage(CallbackInfoReturnable<Boolean> result) {
        NativePlayerTickProbeScope.rejectEffect((ServerPlayer)(Object)this,"ServerPlayer.hurt");
    }
}
