package io.earthplatform.minecraft.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.survival.interaction.host.NativeLocalRootObservation;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import java.util.Queue;
import java.util.function.Consumer;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.UnconfiguredPipelineHandler;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(UnconfiguredPipelineHandler.Inbound.class)
abstract class NativeLocalInboundConfigurationRootsMixin {
    @WrapMethod(method="write(Lio/netty/channel/ChannelHandlerContext;Ljava/lang/Object;Lio/netty/channel/ChannelPromise;)V")
    private void earthplatform$rootsConfigHandler(ChannelHandlerContext context,Object message,ChannelPromise promise,Operation<Void> original) throws Exception {
        NativeLocalRootObservation.HandlerScope scope=null;
        try{scope=NativeLocalRootObservation.handlerEntered(context,message,promise,false);}catch(Throwable failure){try{NativeLocalRootObservation.observerFailed();}catch(Throwable ignored){}}
        boolean threw=true;try{original.call(context,message,promise);threw=false;}
        finally{try{NativeLocalRootObservation.handlerReturned(scope,threw);}catch(Throwable failure){try{NativeLocalRootObservation.observerFailed();}catch(Throwable ignored){}}}
    }
    @WrapOperation(method="write",at=@At(value="INVOKE",target="Lnet/minecraft/network/UnconfiguredPipelineHandler$InboundConfigurationTask;run(Lio/netty/channel/ChannelHandlerContext;)V"),require=1,allow=1)
    private void earthplatform$rootsConfigTask(UnconfiguredPipelineHandler.InboundConfigurationTask task,ChannelHandlerContext context,Operation<Void> original) {
        try{NativeLocalRootObservation.beforeConfigTask(context,task,false);}catch(Throwable failure){try{NativeLocalRootObservation.observerFailed();}catch(Throwable ignored){}}
        boolean threw=true;try{original.call(task,context);threw=false;}
        finally{try{NativeLocalRootObservation.configTaskReturned(false,threw);}catch(Throwable failure){try{NativeLocalRootObservation.observerFailed();}catch(Throwable ignored){}}}
    }
    @WrapOperation(method="write",at=@At(value="INVOKE",target="Lio/netty/util/ReferenceCountUtil;release(Ljava/lang/Object;)Z",remap=false),require=2,allow=2)
    private boolean earthplatform$rootsConfigRelease(Object message,Operation<Boolean> original) {
        boolean result=false,threw=true;try{result=original.call(message);threw=false;return result;}
        finally{try{NativeLocalRootObservation.configReleaseReturned(message,false,result,threw);}catch(Throwable failure){try{NativeLocalRootObservation.observerFailed();}catch(Throwable ignored){}}}
    }
    @WrapOperation(method="write",at=@At(value="INVOKE",target="Lio/netty/channel/ChannelPromise;setSuccess()Lio/netty/channel/ChannelPromise;",remap=false),require=1,allow=1)
    private ChannelPromise earthplatform$rootsConfigSuccess(ChannelPromise promise,Operation<ChannelPromise> original) {
        boolean listeners=false;try{listeners=NativeLocalRootObservation.promiseListenersObserved(promise,false);}catch(Throwable failure){try{NativeLocalRootObservation.observerFailed();}catch(Throwable ignored){}}
        ChannelPromise result=null;boolean threw=true;
        try{result=original.call(promise);threw=false;return result;}
        finally{try{NativeLocalRootObservation.configPromiseReturned(promise,result,false,listeners,threw);}catch(Throwable failure){try{NativeLocalRootObservation.observerFailed();}catch(Throwable ignored){}}}
    }
}
