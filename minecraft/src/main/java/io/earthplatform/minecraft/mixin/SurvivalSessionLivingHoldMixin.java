package io.earthplatform.minecraft.mixin;
import io.earthplatform.minecraft.survival.persistence.NativeSessionExecutionGate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(value=LivingEntity.class,priority=2200)
abstract class SurvivalSessionLivingHoldMixin {
 @Inject(method="startUsingItem(Lnet/minecraft/world/InteractionHand;)V",at=@At("HEAD"),cancellable=true,require=1,allow=1)
 private void earthplatform$holdUse(InteractionHand hand,CallbackInfo ci){io.earthplatform.minecraft.survival.interaction.host.NativePlayerStepReadScope.rejectEffect((net.minecraft.world.entity.Entity)(Object)this,"holdUse");if((Object)this instanceof ServerPlayer player && NativeSessionExecutionGate.blocksNative(player))ci.cancel();}
 @Inject(method="heal(F)V",at=@At("HEAD"),cancellable=true,require=1,allow=1)
 private void earthplatform$holdHeal(float amount,CallbackInfo ci){io.earthplatform.minecraft.survival.interaction.host.NativePlayerStepReadScope.rejectEffect((net.minecraft.world.entity.Entity)(Object)this,"holdHeal");if((Object)this instanceof ServerPlayer player && NativeSessionExecutionGate.blocksNative(player))ci.cancel();}
 @Inject(method="addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z",at=@At("HEAD"),cancellable=true,require=1,allow=1)
 private void earthplatform$holdEffect(MobEffectInstance effect,Entity source,CallbackInfoReturnable<Boolean> ci){io.earthplatform.minecraft.survival.interaction.host.NativePlayerStepReadScope.rejectEffect((net.minecraft.world.entity.Entity)(Object)this,"holdEffect");if((Object)this instanceof ServerPlayer player && NativeSessionExecutionGate.blocksNative(player))ci.setReturnValue(false);}
}
