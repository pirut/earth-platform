package io.earthplatform.minecraft.mixin;
import io.earthplatform.minecraft.survival.persistence.NativeSessionExecutionGate;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** A foreign push/piston/plugin write fails BEFORE a mutation prefix, rather than drifting escrow.
 * Only the owned single native transfer or exact native teleport-ack handler has a write scope.
 */
@Mixin(value=Entity.class,priority=2200)
abstract class SurvivalSessionKinematicHoldMixin {
 @Inject(method={
  "setPos(Lnet/minecraft/world/phys/Vec3;)V","setPos(DDD)V",
  "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
  "absMoveTo(DDDFF)V","absMoveTo(DDD)V",
  "moveTo(Lnet/minecraft/world/phys/Vec3;)V","moveTo(DDD)V",
  "moveTo(Lnet/minecraft/core/BlockPos;FF)V","moveTo(Lnet/minecraft/world/phys/Vec3;FF)V","moveTo(DDDFF)V",
  "push(Lnet/minecraft/world/entity/Entity;)V","push(Lnet/minecraft/world/phys/Vec3;)V","push(DDD)V",
  "setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V","setDeltaMovement(DDD)V","setPosRaw(DDD)V"},at=@At("HEAD"),require=16,allow=16)
 private void earthplatform$holdKinematics(CallbackInfo ci){NativeSessionExecutionGate.rejectForeignKinematicWrite((Entity)(Object)this);}
}
