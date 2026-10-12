package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.survival.gametest.NativePlayerTickProbeScope;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
abstract class NativePlayerTickProbeLivingEffectMixin {
    @Inject(method="heal(F)V",at=@At("HEAD"),require=1)
    private void earthplatform$rejectDiagnosticHealing(CallbackInfo result) {
        NativePlayerTickProbeScope.rejectEffect((LivingEntity)(Object)this,"LivingEntity.heal");
    }
    @Inject(method="addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z",at=@At("HEAD"),require=1)
    private void earthplatform$rejectDiagnosticEffect(CallbackInfoReturnable<Boolean> result) {
        NativePlayerTickProbeScope.rejectEffect((LivingEntity)(Object)this,"LivingEntity.addEffect");
    }
}
