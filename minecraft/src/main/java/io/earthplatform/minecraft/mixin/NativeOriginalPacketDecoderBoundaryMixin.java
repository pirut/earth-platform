package io.earthplatform.minecraft.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import io.earthplatform.minecraft.survival.interaction.host.NativeCodecBoundaryAccess;
import io.earthplatform.minecraft.survival.interaction.host.NativeCodecBoundaryOwner;
import io.netty.channel.ChannelHandlerContext;
import io.netty.buffer.ByteBuf;
import java.util.List;
import io.netty.handler.codec.ByteToMessageDecoder;
import net.minecraft.network.PacketDecoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
@Mixin(PacketDecoder.class)
abstract class NativeOriginalPacketDecoderBoundaryMixin extends ByteToMessageDecoder implements NativeCodecBoundaryAccess {
    @Unique private volatile NativeCodecBoundaryOwner earthplatform$codecBoundary;
    public synchronized void earthplatform$bindCodecBoundary(Object exact,NativeCodecBoundaryOwner owner){
        if(exact!=(Object)this||owner==null||!owner.matches(exact)||earthplatform$codecBoundary!=null)throw new IllegalStateException("Codec boundary identity changed");
        earthplatform$codecBoundary=owner;
    }
    @Override public void channelRead(ChannelHandlerContext ctx,Object message)throws Exception{
        var owner=earthplatform$codecBoundary;if(owner==null){super.channelRead(ctx,message);return;}
        try{owner.readEnter(this,ctx,message);super.channelRead(ctx,message);owner.readExit(this,ctx);}
        catch(Exception|Error failure){owner.codecFailure(this,failure);throw failure;}
    }
    @WrapMethod(method="decode(Lio/netty/channel/ChannelHandlerContext;Lio/netty/buffer/ByteBuf;Ljava/util/List;)V")
    private void earthplatform$latchBeforeDecoderCatch(ChannelHandlerContext ctx,ByteBuf buffer,List<Object> out,Operation<Void> original)throws Exception{
        try{original.call(ctx,buffer,out);var owner=earthplatform$codecBoundary;if(owner!=null)owner.decodeExit(this,buffer);}catch(Exception|Error failure){var owner=earthplatform$codecBoundary;if(owner!=null)owner.codecFailure(this,failure);throw failure;}
    }
}
