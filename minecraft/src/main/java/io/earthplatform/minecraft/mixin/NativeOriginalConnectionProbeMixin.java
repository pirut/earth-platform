package io.earthplatform.minecraft.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.survival.interaction.host.NativeOriginalConnectionAccess;
import io.earthplatform.minecraft.survival.interaction.host.NativeOriginalConnectionOutputs;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Explicit GameTest-only original Minecraft seam. No Netty target and no survival activation. */
@Mixin(Connection.class)
abstract class NativeOriginalConnectionProbeMixin implements NativeOriginalConnectionAccess {
    @Unique private volatile NativeOriginalConnectionOutputs earthplatform$originalOutputs;
    public synchronized void earthplatform$bindOriginalOutputs(Connection exact,NativeOriginalConnectionOutputs owner){
        if(exact!=(Object)this||owner==null||!owner.matches(exact)||earthplatform$originalOutputs!=null)
            throw new IllegalStateException("Original Connection owner attachment changed");
        earthplatform$originalOutputs=owner;
    }
    @WrapOperation(method="doSendPacket(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;Z)V",
        at=@At(value="INVOKE",target="Lio/netty/channel/Channel;write(Ljava/lang/Object;)Lio/netty/channel/ChannelFuture;",remap=false),require=1,allow=1)
    private ChannelFuture earthplatform$observeOriginalWrite(Channel channel,Object packet,Operation<ChannelFuture> original){
        var owner=earthplatform$originalOutputs;
        return owner==null?original.call(channel,packet):owner.originalWrite((Connection)(Object)this,channel,packet,false,original);
    }
    @WrapOperation(method="doSendPacket(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;Z)V",
        at=@At(value="INVOKE",target="Lio/netty/channel/Channel;writeAndFlush(Ljava/lang/Object;)Lio/netty/channel/ChannelFuture;",remap=false),require=1,allow=1)
    private ChannelFuture earthplatform$observeOriginalWriteAndFlush(Channel channel,Object packet,Operation<ChannelFuture> original){
        var owner=earthplatform$originalOutputs;
        return owner==null?original.call(channel,packet):owner.originalWrite((Connection)(Object)this,channel,packet,true,original);
    }
    @WrapOperation(method="tick()V",at=@At(value="INVOKE",target="Lio/netty/channel/Channel;flush()Lio/netty/channel/Channel;",remap=false),require=1,allow=1)
    private Channel earthplatform$observeOriginalTickFlush(Channel channel,Operation<Channel> original){
        var owner=earthplatform$originalOutputs;
        return owner==null?original.call(channel):owner.originalTickFlush((Connection)(Object)this,channel,original);
    }
    @Inject(method="exceptionCaught(Lio/netty/channel/ChannelHandlerContext;Ljava/lang/Throwable;)V",at=@At("HEAD"),require=1,allow=1)
    private void earthplatform$latchOriginalFailure(ChannelHandlerContext context,Throwable failure,CallbackInfo ci){
        var owner=earthplatform$originalOutputs;if(owner!=null)owner.nativeFailure(failure);
    }
}
