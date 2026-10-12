package io.earthplatform.minecraft.mixin;
import io.earthplatform.minecraft.survival.interaction.host.NativePlayerStepReadScope;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** First MOVE closure has no outbound packets. A correction/pairing must fence rather than be hidden. */
@Mixin(value=ServerCommonPacketListenerImpl.class,priority=2400)
abstract class NativePlayerMoveOutputMixin {
 @Inject(method="send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",at=@At("HEAD"),require=1)
 private void earthplatform$rejectUnownedMoveOutput(Packet<?> packet,PacketSendListener listener,CallbackInfo ci){NativePlayerStepReadScope.rejectOutput(this);}
}
