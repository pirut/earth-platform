package io.earthplatform.minecraft.mixin;
import io.earthplatform.minecraft.survival.host.SurvivalLevelBinding;
import io.earthplatform.minecraft.survival.interaction.host.NativeEffectDeliveryHost;
import io.earthplatform.minecraft.survival.interaction.host.NativePlayerStepOperation;
import io.earthplatform.minecraft.survival.interaction.host.NativePlayerStepReadScope;
import io.earthplatform.minecraft.survival.persistence.NativeSessionExecutionGate;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
/** Original native owner-thread handler, not simulated movement or a second network listener. */
@Mixin(value=ServerGamePacketListenerImpl.class,priority=2300)
abstract class SurvivalPlayerMovePacketMixin {
 @Shadow public ServerPlayer player;
 @WrapMethod(method="handleMovePlayer(Lnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket;)V")
 private void earthplatform$ownedMove(ServerboundMovePlayerPacket packet,Operation<Void> original){
  var receiver=(ServerGamePacketListenerImpl)(Object)this;
  if(player!=null&&(NativeSessionExecutionGate.find(player)!=null||SurvivalLevelBinding.find(player.level())!=null)
    &&(receiver.getClass()!=ServerGamePacketListenerImpl.class||player.getClass()!=ServerPlayer.class||player.connection!=receiver||receiver.player!=player)){
   NativePlayerStepReadScope.rejectEffect(player,"foreign movement receiver before native scheduling");
   throw new SurvivalRefusal("Bound MOVE receiver/player identity changed before scheduling");
  }
  if(player!=null&&(NativeSessionExecutionGate.find(player)!=null||SurvivalLevelBinding.find(player.level())!=null)
     &&(packet==null||packet.getClass()!=ServerboundMovePlayerPacket.Pos.class&&packet.getClass()!=ServerboundMovePlayerPacket.PosRot.class
       &&packet.getClass()!=ServerboundMovePlayerPacket.Rot.class&&packet.getClass()!=ServerboundMovePlayerPacket.StatusOnly.class)){
   NativePlayerStepReadScope.rejectEffect(player,"foreign movement packet before native scheduling");
   throw new SurvivalRefusal("Bound MOVE packet class changed before scheduling");
  }
  // Preserve native off-thread scheduling and its normal handoff exception outside a mutation catch.
  PacketUtils.ensureRunningOnSameThread(packet,receiver,player.serverLevel());
  if(NativePlayerStepReadScope.enterMove(receiver,player,packet)){original.call(packet);return;}
  if(NativeSessionExecutionGate.blocksNative(player)){
   if(!NativeSessionExecutionGate.acceptsPlayerMove(player))return;
   var host=NativeEffectDeliveryHost.findAttached(player.serverLevel());
   if(host==null)throw new SurvivalRefusal("Registered Earth MOVE has no native effect owner");
   host.dispatchPlayerStep(NativePlayerStepOperation.MOVE_PACKET,receiver,packet);return;
  }
  if(SurvivalLevelBinding.find(player.level())!=null)throw new SurvivalRefusal("Bound native MOVE requires its registered session owner");
  original.call(packet);
 }
}
