package io.earthplatform.minecraft.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.earthplatform.minecraft.survival.interaction.host.NativeCodecBoundaryAccess;
import io.earthplatform.minecraft.survival.interaction.host.NativeCodecBoundaryOwner;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.MessageToByteEncoder;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.codec.StreamCodec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
/** Minecraft target only. Original inherited Netty bodies run exactly once. */
@Mixin(PacketEncoder.class)
abstract class NativeOriginalPacketEncoderBoundaryMixin extends MessageToByteEncoder<Object> implements NativeCodecBoundaryAccess {
    @Unique private volatile NativeCodecBoundaryOwner earthplatform$codecBoundary;
    public synchronized void earthplatform$bindCodecBoundary(Object exact,NativeCodecBoundaryOwner owner){
        if(exact!=(Object)this||owner==null||!owner.matches(exact)||earthplatform$codecBoundary!=null)throw new IllegalStateException("Codec boundary identity changed");
        earthplatform$codecBoundary=owner;
    }
    @Override public void write(ChannelHandlerContext ctx,Object packet,ChannelPromise promise)throws Exception{
        var owner=earthplatform$codecBoundary;if(owner==null){super.write(ctx,packet,promise);return;}
        try{owner.writeEnter(this,ctx,packet,promise);super.write(ctx,packet,promise);owner.writeExit(this,ctx);}
        catch(Exception|Error failure){owner.codecFailure(this,failure);throw failure;}
    }
    @Override protected ByteBuf allocateBuffer(ChannelHandlerContext ctx,Object packet,boolean direct)throws Exception{
        var owner=earthplatform$codecBoundary;if(owner==null)return super.allocateBuffer(ctx,packet,direct);
        try{owner.allocationEnter(this,ctx,packet,direct);ByteBuf buffer=super.allocateBuffer(ctx,packet,direct);owner.allocated(this,ctx,buffer);return buffer;}
        catch(Exception|Error failure){owner.codecFailure(this,failure);throw failure;}
    }
    @WrapOperation(method="encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V",
        at=@At(value="INVOKE",target="Lnet/minecraft/network/codec/StreamCodec;encode(Ljava/lang/Object;Ljava/lang/Object;)V"),require=1,allow=1)
    private void earthplatform$latchBeforeEncoderCatch(StreamCodec<Object,Object> codec,Object buffer,Object packet,Operation<Void> original){
        try{original.call(codec,buffer,packet);}catch(RuntimeException|Error failure){var owner=earthplatform$codecBoundary;if(owner!=null)owner.codecFailure(this,failure);throw failure;}
    }
}
