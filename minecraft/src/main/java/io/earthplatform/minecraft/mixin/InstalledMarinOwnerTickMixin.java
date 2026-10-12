package io.earthplatform.minecraft.mixin;
import io.earthplatform.minecraft.survival.persistence.NativeSessionExecutionGate;
import io.earthplatform.minecraft.survival.runtime.InstalledMarinSessionRuntime;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Actual integrated owner cadence, including paused ticks; no manual Level simulation. */
@Mixin(value=IntegratedServer.class,priority=2100)
abstract class InstalledMarinOwnerTickMixin {
 @Inject(method="tickServer(Ljava/util/function/BooleanSupplier;)V",at=@At("TAIL"),require=1,allow=1)
 private void earthplatform$pollHeldSession(java.util.function.BooleanSupplier hasTime,CallbackInfo ci){
  InstalledMarinSessionRuntime.ownerTick((IntegratedServer)(Object)this);
 }
 @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method="tickPaused()V",
   at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerPlayer;awardStat(Lnet/minecraft/resources/ResourceLocation;)V"),require=1,allow=1)
 private void earthplatform$holdPausedStat(ServerPlayer player,ResourceLocation stat,
   com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original){
  if(!NativeSessionExecutionGate.blocksNative(player))original.call(player,stat);
 }
}
