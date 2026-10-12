package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.enginebridge.NativeCollisionLevelAccess;
import io.earthplatform.minecraft.enginebridge.NativeCollisionMotionFence;
import io.earthplatform.minecraft.enginebridge.NativePlayerMotionGate;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.Set;

/** Prevent before vanilla's later absMoveTo; the rolling permit includes spectator/noPhysics. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class CoreCollisionServerMoveMixin {
    @Shadow public ServerPlayer player;
    @Shadow private int awaitingTeleport;
    @Shadow private Vec3 awaitingPositionFromClient;
    @Unique private NativePlayerMotionGate earthplatform$gate(){
        var view=((NativeCollisionLevelAccess)player.level()).earthplatform$coreCollisionView();
        return view instanceof NativePlayerMotionGate gate&&gate.ownsMotion(player)?gate:null;
    }
    @Inject(method="handleMovePlayer(Lnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket;)V",
            at=@At(value="INVOKE",target="Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",shift=At.Shift.AFTER),cancellable=true)
    private void earthplatform$packetMotion(ServerboundMovePlayerPacket packet,CallbackInfo result) {
        Vec3 delta=new Vec3(packet.getX(player.getX())-player.getX(),packet.getY(player.getY())-player.getY(),packet.getZ(player.getZ())-player.getZ());
        var gate=earthplatform$gate();
        if(gate!=null) {
            // Capture only bounded intent during a hold. Do not replace an outstanding correction ID.
            if(!gate.admitMotion(player,delta,true)||gate.correctionOutstanding(player)) {
                player.setDeltaMovement(Vec3.ZERO);player.resetFallDistance();
                if(awaitingPositionFromClient==null)gate.correctMotion(player);
                result.cancel();
            }
            return;
        }
        // Unchanged legacy walking-only fence, scoped to its attached native collision owner.
        if(!player.isSpectator()&&((NativeCollisionLevelAccess)player.level()).earthplatform$coreCollisionView() instanceof NativeCollisionMotionFence fence
                &&(fence.blocksPlayerMovement()||!NativeCollisionMotionFence.bounded(player.getBoundingBox(),delta))) {
            player.setDeltaMovement(Vec3.ZERO);player.resetFallDistance();
            if(awaitingPositionFromClient==null)player.connection.teleport(player.getX(),player.getY(),player.getZ(),player.getYRot(),player.getXRot());
            result.cancel();
        }
    }
    @Inject(method="handleMovePlayer(Lnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket;)V",at=@At("RETURN"))
    private void earthplatform$acceptedMotion(ServerboundMovePlayerPacket packet,CallbackInfo ci){var gate=earthplatform$gate();if(gate!=null)gate.observeMotion(player);}
    @Inject(method="teleport(DDDFFLjava/util/Set;)V",at=@At("HEAD"),cancellable=true)
    private void earthplatform$teleport(double x,double y,double z,float yaw,float pitch,Set<RelativeMovement> relative,CallbackInfo ci){
        var gate=earthplatform$gate();if(gate!=null&&!gate.admitTeleport(player,new Vec3(x,y,z)))ci.cancel();
    }
    @Inject(method="teleport(DDDFFLjava/util/Set;)V",at=@At("RETURN"))
    private void earthplatform$correctionSent(double x,double y,double z,float yaw,float pitch,Set<RelativeMovement> relative,CallbackInfo ci){var gate=earthplatform$gate();if(gate!=null){gate.correctionSent(player,awaitingTeleport);gate.observeMotion(player);}}
    @Inject(method="handleAcceptTeleportPacket(Lnet/minecraft/network/protocol/game/ServerboundAcceptTeleportationPacket;)V",at=@At("RETURN"))
    private void earthplatform$correctionAck(ServerboundAcceptTeleportationPacket packet,CallbackInfo ci){var gate=earthplatform$gate();if(gate!=null)gate.correctionAcknowledged(player,packet.getId(),packet.getId()==awaitingTeleport&&awaitingPositionFromClient==null);}
    @Inject(method="handleMoveVehicle(Lnet/minecraft/network/protocol/game/ServerboundMoveVehiclePacket;)V",at=@At(value="INVOKE",target="Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",shift=At.Shift.AFTER),cancellable=true)
    private void earthplatform$vehicle(ServerboundMoveVehiclePacket packet,CallbackInfo ci){if(earthplatform$gate()!=null)ci.cancel();}
    @Inject(method="handleTeleportToEntityPacket(Lnet/minecraft/network/protocol/game/ServerboundTeleportToEntityPacket;)V",at=@At(value="INVOKE",target="Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",shift=At.Shift.AFTER),cancellable=true)
    private void earthplatform$spectatorTarget(ServerboundTeleportToEntityPacket packet,CallbackInfo ci){if(earthplatform$gate()!=null)ci.cancel();}
    @Inject(method="handleInteract(Lnet/minecraft/network/protocol/game/ServerboundInteractPacket;)V",at=@At(value="INVOKE",target="Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",shift=At.Shift.AFTER),cancellable=true)
    private void earthplatform$spectatorCamera(ServerboundInteractPacket packet,CallbackInfo ci){if(player.isSpectator()&&earthplatform$gate()!=null)ci.cancel();}
}
