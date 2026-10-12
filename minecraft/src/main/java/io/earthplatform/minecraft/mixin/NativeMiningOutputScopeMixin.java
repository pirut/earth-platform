package io.earthplatform.minecraft.mixin;
import io.earthplatform.minecraft.survival.interaction.host.NativeMiningControlPreparation;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** No transport output escapes native scratch preparation. Unknown packets poison the exact action. */
@Mixin(value=ServerCommonPacketListenerImpl.class,priority=2500)
abstract class NativeMiningOutputScopeMixin {
    @Inject(method="send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",at=@At("HEAD"),cancellable=true,require=1,allow=1)
    private void earthplatform$captureNativeMiningOutput(Packet<?> packet,PacketSendListener listener,CallbackInfo ci) {
        if(NativeMiningControlPreparation.captureOutput(this,packet,listener))ci.cancel();
    }
}
