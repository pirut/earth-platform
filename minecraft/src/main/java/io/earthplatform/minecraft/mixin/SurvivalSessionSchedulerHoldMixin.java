package io.earthplatform.minecraft.mixin;
import io.earthplatform.minecraft.survival.persistence.NativeSessionExecutionGate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Covers the REAL scheduler before old-pose writes, tickCount increment and EntityTickEvent.Pre. */
@Mixin(value=ServerLevel.class,priority=2200)
abstract class SurvivalSessionSchedulerHoldMixin {
 @Inject(method="tick(Ljava/util/function/BooleanSupplier;)V",at=@At("HEAD"),cancellable=true,require=1,allow=1)
 private void earthplatform$holdCarrier(java.util.function.BooleanSupplier available,CallbackInfo ci){var level=(ServerLevel)(Object)this;if(NativeSessionExecutionGate.blocksCarrier(level)){NativeSessionExecutionGate.observedCarrierSuppression(level);ci.cancel();}}
 @Inject(method="tickNonPassenger(Lnet/minecraft/world/entity/Entity;)V",at=@At("HEAD"),cancellable=true,require=1,allow=1)
 private void earthplatform$holdScheduledPlayer(Entity entity,CallbackInfo ci){if(entity instanceof ServerPlayer player && NativeSessionExecutionGate.blocksNative(player)){NativeSessionExecutionGate.observedSchedulerSuppression(player);ci.cancel();}}
}
