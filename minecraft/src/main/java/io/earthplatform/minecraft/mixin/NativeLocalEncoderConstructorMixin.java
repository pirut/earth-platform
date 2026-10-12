package io.earthplatform.minecraft.mixin;
import io.earthplatform.minecraft.survival.interaction.host.NativeLocalCausalObservation;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.ProtocolInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(PacketEncoder.class)
abstract class NativeLocalEncoderConstructorMixin {
    @Inject(method="<init>(Lnet/minecraft/network/ProtocolInfo;)V",at=@At("RETURN"),require=1,allow=1)
    private void earthplatform$observeCodecConstructor(ProtocolInfo<?> protocol,CallbackInfo ci){
        try{NativeLocalCausalObservation.codecConstructed(this,protocol,true);}
        catch(Throwable failure){try{NativeLocalCausalObservation.observerFailed();}catch(Throwable ignored){}}
    }
}
