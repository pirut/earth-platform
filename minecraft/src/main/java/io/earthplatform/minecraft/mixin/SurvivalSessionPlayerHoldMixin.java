package io.earthplatform.minecraft.mixin;
import io.earthplatform.minecraft.survival.persistence.NativeSessionExecutionGate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
/** Both native player roots are gated independently; neither route is silently mistaken for the other. */
@Mixin(value=ServerPlayer.class,priority=2100)
abstract class SurvivalSessionPlayerHoldMixin {
 @Inject(method={"tick()V","doTick()V"},at=@At("HEAD"),cancellable=true,require=2,allow=2)
 private void earthplatform$holdTick(CallbackInfo ci){io.earthplatform.minecraft.survival.interaction.host.NativeMiningControlPreparation.rejectHeldIngress("held player root");io.earthplatform.minecraft.survival.interaction.host.NativePlayerStepReadScope.rejectEffect((net.minecraft.world.entity.Entity)(Object)this,"holdTick");if(NativeSessionExecutionGate.blocksNative((ServerPlayer)(Object)this))ci.cancel();}
 @Inject(method="hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",at=@At("HEAD"),cancellable=true,require=1,allow=1)
 private void earthplatform$holdDamage(net.minecraft.world.damagesource.DamageSource source,float amount,CallbackInfoReturnable<Boolean> ci){io.earthplatform.minecraft.survival.interaction.host.NativeMiningControlPreparation.rejectHeldIngress("held player root");io.earthplatform.minecraft.survival.interaction.host.NativePlayerStepReadScope.rejectEffect((net.minecraft.world.entity.Entity)(Object)this,"holdDamage");if(NativeSessionExecutionGate.blocksNative((ServerPlayer)(Object)this))ci.setReturnValue(false);}
 @Inject(method="drop(Z)Z",at=@At("HEAD"),cancellable=true,require=1,allow=1)
 private void earthplatform$holdDrop(boolean all,CallbackInfoReturnable<Boolean> ci){io.earthplatform.minecraft.survival.interaction.host.NativeMiningControlPreparation.rejectHeldIngress("held player root");io.earthplatform.minecraft.survival.interaction.host.NativePlayerStepReadScope.rejectEffect((net.minecraft.world.entity.Entity)(Object)this,"holdDrop");if(NativeSessionExecutionGate.blocksNative((ServerPlayer)(Object)this))ci.setReturnValue(false);}
 @Inject(method="drop(Lnet/minecraft/world/item/ItemStack;ZZ)Lnet/minecraft/world/entity/item/ItemEntity;",at=@At("HEAD"),cancellable=true,require=1,allow=1)
 private void earthplatform$holdDropStack(ItemStack stack,boolean around,boolean trace,CallbackInfoReturnable<ItemEntity> ci){io.earthplatform.minecraft.survival.interaction.host.NativeMiningControlPreparation.rejectHeldIngress("held player root");io.earthplatform.minecraft.survival.interaction.host.NativePlayerStepReadScope.rejectEffect((net.minecraft.world.entity.Entity)(Object)this,"holdDropStack");if(NativeSessionExecutionGate.blocksNative((ServerPlayer)(Object)this))ci.setReturnValue(null);}
}
