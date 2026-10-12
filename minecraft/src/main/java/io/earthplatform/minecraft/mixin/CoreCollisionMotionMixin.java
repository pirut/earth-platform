package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.enginebridge.NativeCollisionLevelAccess;
import io.earthplatform.minecraft.enginebridge.NativeCollisionMotionFence;
import io.earthplatform.minecraft.enginebridge.NativePlayerMotionGate;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** All-mode rolling defense in depth, including direct server-driven position entrances. */
@Mixin(Entity.class)
abstract class CoreCollisionMotionMixin {
    @Unique private NativePlayerMotionGate earthplatform$gate(Player player){var view=((NativeCollisionLevelAccess)player.level()).earthplatform$coreCollisionView();return view instanceof NativePlayerMotionGate gate&&gate.ownsMotion(player)?gate:null;}
    @Inject(method="move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",at=@At("HEAD"),cancellable=true)
    private void earthplatform$motion(MoverType type,Vec3 motion,CallbackInfo result) {
        Entity entity=(Entity)(Object)this;
        if(entity instanceof Player player){
            var gate=earthplatform$gate(player);
            boolean blocked=gate!=null?!gate.admitMotion(player,motion,false):!player.isSpectator()
                    &&((NativeCollisionLevelAccess)entity.level()).earthplatform$coreCollisionView() instanceof NativeCollisionMotionFence fence
                    &&(fence.blocksPlayerMovement()||!NativeCollisionMotionFence.bounded(entity.getBoundingBox(),motion));
            if(blocked){entity.setDeltaMovement(Vec3.ZERO);entity.resetFallDistance();result.cancel();}
        }
    }
    @Inject(method="move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",at=@At("RETURN"))
    private void earthplatform$observed(MoverType type,Vec3 motion,CallbackInfo ci){if((Object)this instanceof Player player){var gate=earthplatform$gate(player);if(gate!=null)gate.observeMotion(player);}}
    @Inject(method="setPosRaw(DDD)V",at=@At("HEAD"),cancellable=true)
    private void earthplatform$rawPosition(double x,double y,double z,CallbackInfo ci){
        if((Object)this instanceof Player player&&player.level()!=null&&!player.level().isClientSide){var gate=earthplatform$gate(player);if(gate!=null&&!gate.admitPosition(player,new Vec3(x,y,z))){player.setDeltaMovement(Vec3.ZERO);player.resetFallDistance();ci.cancel();}}
    }
    @Inject(method="setBoundingBox(Lnet/minecraft/world/phys/AABB;)V",at=@At("HEAD"),cancellable=true)
    private void earthplatform$body(net.minecraft.world.phys.AABB body,CallbackInfo ci){if((Object)this instanceof Player player&&player.level()!=null&&!player.level().isClientSide){var gate=earthplatform$gate(player);if(gate!=null&&!gate.admitBody(player,body))ci.cancel();}}
    @Inject(method="setBoundingBox(Lnet/minecraft/world/phys/AABB;)V",at=@At("RETURN"))
    private void earthplatform$bodyAccepted(net.minecraft.world.phys.AABB body,CallbackInfo ci){if((Object)this instanceof Player player&&player.level()!=null&&!player.level().isClientSide){var gate=earthplatform$gate(player);if(gate!=null)gate.observeMotion(player);}}
    @Inject(method="startRiding(Lnet/minecraft/world/entity/Entity;Z)Z",at=@At("HEAD"),cancellable=true)
    private void earthplatform$vehicle(Entity vehicle,boolean force,CallbackInfoReturnable<Boolean> ci){if((Object)this instanceof Player player&&earthplatform$gate(player)!=null)ci.setReturnValue(false);}
}
