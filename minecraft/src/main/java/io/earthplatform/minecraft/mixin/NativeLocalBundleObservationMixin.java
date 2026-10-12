package io.earthplatform.minecraft.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.earthplatform.minecraft.survival.interaction.host.NativeLocalCausalObservation;
import io.netty.channel.ChannelHandlerContext;
import java.util.List;
import net.minecraft.network.PacketBundlePacker;
import net.minecraft.network.protocol.BundlerInfo;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
@Mixin(PacketBundlePacker.class)
abstract class NativeLocalBundleObservationMixin {
    @Shadow private BundlerInfo.Bundler currentBundler;
    @WrapMethod(method="decode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Ljava/util/List;)V")
    private void earthplatform$observeBundleReturn(ChannelHandlerContext context,Packet<?> packet,List<Object> out,Operation<Void> original)throws Exception{
        boolean failed=true;
        try{original.call(context,packet,out);failed=false;}
        finally{try{NativeLocalCausalObservation.bundleReturned(context,currentBundler!=null,failed);}
            catch(Throwable failure){try{NativeLocalCausalObservation.observerFailed();}catch(Throwable ignored){}}}
    }
}
