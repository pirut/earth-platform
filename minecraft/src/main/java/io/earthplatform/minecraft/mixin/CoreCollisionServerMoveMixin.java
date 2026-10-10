package io.earthplatform.minecraft.mixin;

import io.earthplatform.minecraft.enginebridge.NativeCollisionLevelAccess;
import io.earthplatform.minecraft.enginebridge.NativeCollisionMotionFence;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla later absMoveTo can bypass Entity.move cancellation; fence before that packet path. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class CoreCollisionServerMoveMixin {
    @Shadow public ServerPlayer player;
    @Inject(method="handleMovePlayer(Lnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket;)V",
            at=@At(value="INVOKE",target="Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",shift=At.Shift.AFTER),cancellable=true)
    private void earthplatform$packetMotion(ServerboundMovePlayerPacket packet,CallbackInfo result) {
        if(!player.isSpectator()&&((NativeCollisionLevelAccess)player.level()).earthplatform$coreCollisionView() instanceof NativeCollisionMotionFence fence) {
            Vec3 delta=new Vec3(packet.getX(player.getX())-player.getX(),packet.getY(player.getY())-player.getY(),packet.getZ(player.getZ())-player.getZ());
            if(fence.blocksPlayerMovement()||!NativeCollisionMotionFence.bounded(player.getBoundingBox(),delta)) {
                player.setDeltaMovement(Vec3.ZERO);player.resetFallDistance();
                player.connection.teleport(player.getX(),player.getY(),player.getZ(),player.getYRot(),player.getXRot());
                result.cancel();
            }
        }
    }
}
