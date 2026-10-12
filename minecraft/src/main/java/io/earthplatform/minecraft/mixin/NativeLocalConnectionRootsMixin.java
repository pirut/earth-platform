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
/** Observe the original objects and calls. Every original operation remains exactly once. */
@Mixin(Connection.class)
abstract class NativeLocalConnectionRootsMixin {
    @Shadow @Final private Queue<Consumer<Connection>> pendingActions;
    @Shadow private Channel channel;
    @WrapMethod(method="send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;Z)V")
    private void earthplatform$rootsSourceSend(Packet<?> packet,PacketSendListener listener,boolean flush,Operation<Void> original) {
        NativeLocalRootObservation.SourceScope scope=null;
        try{scope=NativeLocalRootObservation.sourceEntered((Connection)(Object)this,NativeLocalRootObservation.SEND);}catch(Throwable failure){try{NativeLocalRootObservation.observerFailed();}catch(Throwable ignored){}}
        boolean threw=true;
        try{original.call(packet,listener,flush);threw=false;}
        finally{try{NativeLocalRootObservation.sourceReturned(scope,threw);}catch(Throwable failure){try{NativeLocalRootObservation.observerFailed();}catch(Throwable ignored){}}}
    }
    @WrapMethod(method="runOnceConnected(Ljava/util/function/Consumer;)V")
    private void earthplatform$rootsSourceOnce(Consumer<Connection> action,Operation<Void> original) {
        NativeLocalRootObservation.SourceScope scope=null;
        try{scope=NativeLocalRootObservation.sourceEntered((Connection)(Object)this,NativeLocalRootObservation.ONCE);}catch(Throwable failure){try{NativeLocalRootObservation.observerFailed();}catch(Throwable ignored){}}
        boolean threw=true;
        try{original.call(action);threw=false;}
        finally{try{NativeLocalRootObservation.sourceReturned(scope,threw);}catch(Throwable failure){try{NativeLocalRootObservation.observerFailed();}catch(Throwable ignored){}}}
    }
    @WrapMethod(method="flushChannel()V")
    private void earthplatform$rootsSourceFlush(Operation<Void> original) {
        NativeLocalRootObservation.SourceScope scope=null;
        try{scope=NativeLocalRootObservation.sourceEntered((Connection)(Object)this,NativeLocalRootObservation.FLUSH);}catch(Throwable failure){try{NativeLocalRootObservation.observerFailed();}catch(Throwable ignored){}}
        boolean threw=true;
        try{original.call();threw=false;}
        finally{try{NativeLocalRootObservation.sourceReturned(scope,threw);}catch(Throwable failure){try{NativeLocalRootObservation.observerFailed();}catch(Throwable ignored){}}}
    }
    @WrapMethod(method="setupInboundProtocol(Lnet/minecraft/network/ProtocolInfo;Lnet/minecraft/network/PacketListener;)V")
    private void earthplatform$rootsSourceInbound(ProtocolInfo<?> protocol,PacketListener listener,Operation<Void> original) {
        NativeLocalRootObservation.SourceScope scope=null;
        try{scope=NativeLocalRootObservation.sourceEntered((Connection)(Object)this,NativeLocalRootObservation.INBOUND);}catch(Throwable failure){try{NativeLocalRootObservation.observerFailed();}catch(Throwable ignored){}}
        boolean threw=true;
        try{original.call(protocol,listener);threw=false;}
        finally{try{NativeLocalRootObservation.sourceReturned(scope,threw);}catch(Throwable failure){try{NativeLocalRootObservation.observerFailed();}catch(Throwable ignored){}}}
    }
    @WrapMethod(method="setupOutboundProtocol(Lnet/minecraft/network/ProtocolInfo;)V")
    private void earthplatform$rootsSourceOutbound(ProtocolInfo<?> protocol,Operation<Void> original) {
        NativeLocalRootObservation.SourceScope scope=null;
        try{scope=NativeLocalRootObservation.sourceEntered((Connection)(Object)this,NativeLocalRootObservation.OUTBOUND);}catch(Throwable failure){try{NativeLocalRootObservation.observerFailed();}catch(Throwable ignored){}}
        boolean threw=true;
        try{original.call(protocol);threw=false;}
        finally{try{NativeLocalRootObservation.sourceReturned(scope,threw);}catch(Throwable failure){try{NativeLocalRootObservation.observerFailed();}catch(Throwable ignored){}}}
    }
    @WrapOperation(method="send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;Z)V",at=@At(value="INVOKE",target="Ljava/util/Queue;add(Ljava/lang/Object;)Z",remap=false),require=1,allow=1)
    private boolean earthplatform$rootsQueueSend(Queue<?> queue,Object action,Operation<Boolean> original) {
        Object ticket=null;try{ticket=NativeLocalRootObservation.beforeQueued((Connection)(Object)this,queue,pendingActions,action,NativeLocalRootObservation.SEND);}catch(Throwable failure){try{NativeLocalRootObservation.observerFailed();}catch(Throwable ignored){}}
        boolean accepted=false,threw=true;
        try{accepted=original.call(queue,action);threw=false;return accepted;}
        finally{try{NativeLocalRootObservation.queuedReturned(ticket,NativeLocalRootObservation.SEND,accepted,threw);}catch(Throwable failure){try{NativeLocalRootObservation.observerFailed();}catch(Throwable ignored){}}}
    }
    @WrapOperation(method="runOnceConnected(Ljava/util/function/Consumer;)V",at=@At(value="INVOKE",target="Ljava/util/Queue;add(Ljava/lang/Object;)Z",remap=false),require=1,allow=1)
    private boolean earthplatform$rootsQueueOnce(Queue<?> queue,Object action,Operation<Boolean> original) {
        Object ticket=null;try{ticket=NativeLocalRootObservation.beforeQueued((Connection)(Object)this,queue,pendingActions,action,NativeLocalRootObservation.ONCE);}catch(Throwable failure){try{NativeLocalRootObservation.observerFailed();}catch(Throwable ignored){}}
        boolean accepted=false,threw=true;
        try{accepted=original.call(queue,action);threw=false;return accepted;}
        finally{try{NativeLocalRootObservation.queuedReturned(ticket,NativeLocalRootObservation.ONCE,accepted,threw);}catch(Throwable failure){try{NativeLocalRootObservation.observerFailed();}catch(Throwable ignored){}}}
    }
    @WrapOperation(method="flushChannel()V",at=@At(value="INVOKE",target="Ljava/util/Queue;add(Ljava/lang/Object;)Z",remap=false),require=1,allow=1)
    private boolean earthplatform$rootsQueueFlush(Queue<?> queue,Object action,Operation<Boolean> original) {
        Object ticket=null;try{ticket=NativeLocalRootObservation.beforeQueued((Connection)(Object)this,queue,pendingActions,action,NativeLocalRootObservation.FLUSH);}catch(Throwable failure){try{NativeLocalRootObservation.observerFailed();}catch(Throwable ignored){}}
        boolean accepted=false,threw=true;
        try{accepted=original.call(queue,action);threw=false;return accepted;}
        finally{try{NativeLocalRootObservation.queuedReturned(ticket,NativeLocalRootObservation.FLUSH,accepted,threw);}catch(Throwable failure){try{NativeLocalRootObservation.observerFailed();}catch(Throwable ignored){}}}
    }
    @WrapOperation(method="flushQueue()V",at=@At(value="INVOKE",target="Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V",remap=false),require=1,allow=1)
    private void earthplatform$rootsAcceptQueued(Consumer<?> action,Object operand,Operation<Void> original) {
        Object ticket=null;
        try{if(operand==(Object)this)ticket=NativeLocalRootObservation.beforeAccept((Connection)(Object)this,action,true);else NativeLocalRootObservation.observerFailed();}catch(Throwable failure){try{NativeLocalRootObservation.observerFailed();}catch(Throwable ignored){}}
        boolean threw=true;try{original.call(action,operand);threw=false;}
        finally{try{NativeLocalRootObservation.acceptReturned(ticket,true,threw);}catch(Throwable failure){try{NativeLocalRootObservation.observerFailed();}catch(Throwable ignored){}}}
    }
    @WrapOperation(method="runOnceConnected(Ljava/util/function/Consumer;)V",at=@At(value="INVOKE",target="Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V",remap=false),require=1,allow=1)
    private void earthplatform$rootsAcceptInline(Consumer<?> action,Object operand,Operation<Void> original) {
        Object ticket=null;
        try{if(operand==(Object)this)ticket=NativeLocalRootObservation.beforeAccept((Connection)(Object)this,action,false);else NativeLocalRootObservation.observerFailed();}catch(Throwable failure){try{NativeLocalRootObservation.observerFailed();}catch(Throwable ignored){}}
        boolean threw=true;try{original.call(action,operand);threw=false;}
        finally{try{NativeLocalRootObservation.acceptReturned(ticket,false,threw);}catch(Throwable failure){try{NativeLocalRootObservation.observerFailed();}catch(Throwable ignored){}}}
    }
    @WrapOperation(method="setupInboundProtocol(Lnet/minecraft/network/ProtocolInfo;Lnet/minecraft/network/PacketListener;)V",at=@At(value="INVOKE",target="Lio/netty/channel/Channel;writeAndFlush(Ljava/lang/Object;)Lio/netty/channel/ChannelFuture;",remap=false),require=1,allow=1)
    private ChannelFuture earthplatform$rootsConfigWriteInbound(Channel receiver,Object message,Operation<ChannelFuture> original) {
        Object ticket=null;try{ticket=NativeLocalRootObservation.beforeConfigWrite((Connection)(Object)this,receiver,channel,message,false);}catch(Throwable failure){try{NativeLocalRootObservation.observerFailed();}catch(Throwable ignored){}}
        ChannelFuture result=null;boolean threw=true;
        try{result=original.call(receiver,message);threw=false;return result;}
        finally{try{NativeLocalRootObservation.configWriteReturned(ticket,false,result,threw);}catch(Throwable failure){try{NativeLocalRootObservation.observerFailed();}catch(Throwable ignored){}}}
    }
    @WrapOperation(method="setupOutboundProtocol(Lnet/minecraft/network/ProtocolInfo;)V",at=@At(value="INVOKE",target="Lio/netty/channel/Channel;writeAndFlush(Ljava/lang/Object;)Lio/netty/channel/ChannelFuture;",remap=false),require=1,allow=1)
    private ChannelFuture earthplatform$rootsConfigWriteOutbound(Channel receiver,Object message,Operation<ChannelFuture> original) {
        Object ticket=null;try{ticket=NativeLocalRootObservation.beforeConfigWrite((Connection)(Object)this,receiver,channel,message,true);}catch(Throwable failure){try{NativeLocalRootObservation.observerFailed();}catch(Throwable ignored){}}
        ChannelFuture result=null;boolean threw=true;
        try{result=original.call(receiver,message);threw=false;return result;}
        finally{try{NativeLocalRootObservation.configWriteReturned(ticket,true,result,threw);}catch(Throwable failure){try{NativeLocalRootObservation.observerFailed();}catch(Throwable ignored){}}}
    }
}
