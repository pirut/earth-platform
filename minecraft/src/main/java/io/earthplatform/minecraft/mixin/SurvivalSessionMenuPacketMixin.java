package io.earthplatform.minecraft.mixin;
import io.earthplatform.minecraft.survival.interaction.host.NativeSessionMenuPackets;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Above2100 blanket packet hold; the typed host/gate still owns all admission and failure fencing. */
@Mixin(value=ServerGamePacketListenerImpl.class,priority=2300)
abstract class SurvivalSessionMenuPacketMixin {
 @Shadow public ServerPlayer player;
 @Inject(method="handleContainerClick(Lnet/minecraft/network/protocol/game/ServerboundContainerClickPacket;)V",at=@At("HEAD"),cancellable=true,require=1,allow=1)
 private void earthplatform$retainedMenuClick(ServerboundContainerClickPacket packet,CallbackInfo ci){
  if(NativeSessionMenuPackets.click((ServerGamePacketListenerImpl)(Object)this,player,packet))ci.cancel();
 }
 @Inject(method="handleContainerClose(Lnet/minecraft/network/protocol/game/ServerboundContainerClosePacket;)V",at=@At("HEAD"),cancellable=true,require=1,allow=1)
 private void earthplatform$retainedMenuClose(ServerboundContainerClosePacket packet,CallbackInfo ci){
  if(NativeSessionMenuPackets.close((ServerGamePacketListenerImpl)(Object)this,player,packet))ci.cancel();
 }
}
