package io.earthplatform.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.survival.interaction.host.NativeLocalCausalObservation;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import java.net.SocketAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Original real-client memory route only. Every original call remains exactly once. */
@Mixin(Connection.class)
abstract class NativeLocalConnectionObservationMixin {
    @WrapMethod(method="connectToLocalServer(Ljava/net/SocketAddress;)Lnet/minecraft/network/Connection;")
    private static Connection earthplatform$observeLocalConnect(SocketAddress address, Operation<Connection> original) {
        try { NativeLocalCausalObservation.beginConnect(address); } catch (Throwable failure) { try { NativeLocalCausalObservation.observerFailed(); } catch (Throwable ignored) {} }
        Connection result = null; boolean failed = true;
        try { result = original.call(address); failed = false; return result; }
        finally { try { NativeLocalCausalObservation.connectReturned(result, failed); } catch (Throwable failure) { try { NativeLocalCausalObservation.observerFailed(); } catch (Throwable ignored) {} } }
    }
    @WrapOperation(method="connectToLocalServer(Ljava/net/SocketAddress;)Lnet/minecraft/network/Connection;",
            at=@At(value="NEW", target="(Lnet/minecraft/network/protocol/PacketFlow;)Lnet/minecraft/network/Connection;"), require=1, allow=1)
    private static Connection earthplatform$observeLocalConstructor(PacketFlow flow, Operation<Connection> original) {
        Connection result = original.call(flow);
        try { NativeLocalCausalObservation.clientConstructed(result, flow); } catch (Throwable failure) { try { NativeLocalCausalObservation.observerFailed(); } catch (Throwable ignored) {} }
        return result;
    }
    @Inject(method="channelActive(Lio/netty/channel/ChannelHandlerContext;)V", at=@At("RETURN"), require=1, allow=1)
    private void earthplatform$observeLocalActive(ChannelHandlerContext context, CallbackInfo ci) {
        try { NativeLocalCausalObservation.channelActiveReturned((Connection)(Object)this, context); } catch (Throwable failure) { try { NativeLocalCausalObservation.observerFailed(); } catch (Throwable ignored) {} }
    }
    @Inject(method="channelInactive(Lio/netty/channel/ChannelHandlerContext;)V", at=@At("HEAD"), require=1, allow=1)
    private void earthplatform$observeLocalInactive(ChannelHandlerContext context, CallbackInfo ci) {
        try { NativeLocalCausalObservation.channelInactive((Connection)(Object)this); } catch (Throwable failure) { try { NativeLocalCausalObservation.observerFailed(); } catch (Throwable ignored) {} }
    }
    @WrapMethod(method="sendPacket(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;Z)V")
    private void earthplatform$observeSendOrigin(Packet<?> packet, PacketSendListener listener, boolean flush, Operation<Void> original) {
        NativeLocalCausalObservation.Origin scope = null;
        try { scope = NativeLocalCausalObservation.sendOrigin((Connection)(Object)this); } catch (Throwable failure) { try { NativeLocalCausalObservation.observerFailed(); } catch (Throwable ignored) {} }
        try { original.call(packet, listener, flush); }
        catch(Throwable failure){try{NativeLocalCausalObservation.originFailed(scope,false);}catch(Throwable ignored){}throw failure;}
        finally { try { NativeLocalCausalObservation.originReturned(scope, false); } catch (Throwable failure) { try { NativeLocalCausalObservation.observerFailed(); } catch (Throwable ignored) {} } }
    }
    @WrapOperation(method="sendPacket(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;Z)V",
            at=@At(value="INVOKE", target="Lio/netty/channel/EventLoop;execute(Ljava/lang/Runnable;)V", remap=false), require=1, allow=1)
    private void earthplatform$observeSendSubmit(EventLoop executor, Runnable task, Operation<Void> original) {
        Runnable actual = task;
        try { actual = NativeLocalCausalObservation.taskAtSubmission(executor, task, false); } catch (Throwable failure) { try { NativeLocalCausalObservation.observerFailed(); } catch (Throwable ignored) {} }
        boolean failed = true;
        try { original.call(executor, actual); failed = false; }
        finally { try { NativeLocalCausalObservation.submissionReturned(false, failed); } catch (Throwable failure) { try { NativeLocalCausalObservation.observerFailed(); } catch (Throwable ignored) {} } }
    }
    @WrapOperation(method="genericsFtw(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;)V",
            at=@At(value="INVOKE",target="Lnet/minecraft/network/protocol/Packet;handle(Lnet/minecraft/network/PacketListener;)V"),require=1,allow=1)
    private static void earthplatform$observeDirectFailure(Packet<?> packet,net.minecraft.network.PacketListener listener,Operation<Void> original){
        try{original.call(packet,listener);}
        catch(Throwable failure){
            if(failure!=net.minecraft.server.RunningOnDifferentThreadException.RUNNING_ON_DIFFERENT_THREAD)
                try{NativeLocalCausalObservation.directHandlerFailed();}catch(Throwable ignored){}
            throw failure;
        }
    }
    @WrapMethod(method="channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V")
    private void earthplatform$observeReadOrigin(ChannelHandlerContext context, Packet<?> packet, Operation<Void> original) {
        NativeLocalCausalObservation.ReadScope scope = null;
        try { scope = NativeLocalCausalObservation.readEntered((Connection)(Object)this, packet); } catch (Throwable failure) { try { NativeLocalCausalObservation.observerFailed(); } catch (Throwable ignored) {} }
        boolean failed = true;
        try { original.call(context, packet); failed = false; }
        finally { try { NativeLocalCausalObservation.readReturned(scope, failed); } catch (Throwable failure) { try { NativeLocalCausalObservation.observerFailed(); } catch (Throwable ignored) {} } }
    }
}
