package io.earthplatform.minecraft.survival.interaction.host;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.PacketDecoder;
/** Reusable Minecraft-owned observer. It does not send, flush, replay callbacks or grant readiness.
 * A concrete adapter must prepay before factories and retain every uncertain physical alias.
 */
public abstract class NativeCodecBoundaryOwner {
    private static final StackWalker WALKER=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private final Object encoder,decoder;
    protected NativeCodecBoundaryOwner(Object encoder,Object decoder){
        if(encoder==null||encoder.getClass()!=PacketEncoder.class||decoder==null||decoder.getClass()!=PacketDecoder.class)
            throw new IllegalArgumentException("Exact original codec constructors required");
        this.encoder=encoder;this.decoder=decoder;
    }
    /** A single constructor-bound codec avoids retaining a replaced peer codec in ordinary observation. */
    protected NativeCodecBoundaryOwner(Object codec,boolean outbound){
        if(codec==null||codec.getClass()!=(outbound?PacketEncoder.class:PacketDecoder.class))
            throw new IllegalArgumentException("Exact single codec constructor required");
        this.encoder=outbound?codec:null;this.decoder=outbound?null:codec;
    }
    /** Observation may record failure without changing the original ordinary pipeline behavior. */
    protected boolean observationalOnly(){return false;}
    public final boolean matches(Object exact){return exact==encoder||exact==decoder;}
    private void nativeOrigin(Object exact,boolean outbound,String method,String entry){
        Class<?> target=outbound?PacketEncoder.class:PacketDecoder.class;
        if(exact!=(outbound?encoder:decoder)||!WALKER.walk(frames->{var chain=frames.limit(3).toList();return chain.size()==3
                &&chain.get(0).getDeclaringClass()==NativeCodecBoundaryOwner.class&&chain.get(0).getMethodName().equals("nativeOrigin")
                &&chain.get(1).getDeclaringClass()==NativeCodecBoundaryOwner.class&&chain.get(1).getMethodName().equals(entry)
                &&chain.get(2).getDeclaringClass()==target&&chain.get(2).getMethodName().equals(method);}))
            {var failure=new IllegalStateException("Original codec boundary invocation required");if(observationalOnly())diagnosticFailure(failure);else failed(failure);throw failure;}
    }
    public final Object writeEnter(Object codec,ChannelHandlerContext ctx,Object packet,ChannelPromise promise){try{nativeOrigin(codec,true,"write","writeEnter");return beforeWrite(ctx,packet,promise);}catch(RuntimeException|Error failure){if(!observationalOnly())throw failure;diagnosticFailure(failure);return null;}}
    public final void writeExit(Object codec,ChannelHandlerContext ctx,Object scope){try{nativeOrigin(codec,true,"write","writeExit");afterWrite(ctx,scope);}catch(RuntimeException|Error failure){if(!observationalOnly())throw failure;diagnosticFailure(failure);}}
    /** The original inherited write has unwound; failure was already latched at an inner codec hook if present. */
    public final void writeFailure(Object codec,ChannelHandlerContext ctx,Object scope,Throwable original){
        try{nativeOrigin(codec,true,"write","writeFailure");afterFailedWrite(ctx,scope,original);}
        catch(RuntimeException|Error bookkeeping){if(!observationalOnly())throw bookkeeping;diagnosticFailure(bookkeeping);}
    }
    protected void afterFailedWrite(ChannelHandlerContext ctx,Object scope,Throwable original){failed(original);}
    public final void allocationEnter(Object codec,ChannelHandlerContext ctx,Object packet,boolean direct){try{nativeOrigin(codec,true,"allocateBuffer","allocationEnter");beforeAllocation(ctx,packet,direct);}catch(RuntimeException|Error failure){if(!observationalOnly())throw failure;diagnosticFailure(failure);}}
    public final void allocated(Object codec,ChannelHandlerContext ctx,ByteBuf buffer){try{nativeOrigin(codec,true,"allocateBuffer","allocated");afterAllocation(ctx,buffer);}catch(RuntimeException|Error failure){if(!observationalOnly())throw failure;diagnosticFailure(failure);}}
    public final void readEnter(Object codec,ChannelHandlerContext ctx,Object message){try{nativeOrigin(codec,false,"channelRead","readEnter");beforeRead(ctx,message);}catch(RuntimeException|Error failure){if(!observationalOnly())throw failure;diagnosticFailure(failure);}}
    public final void readExit(Object codec,ChannelHandlerContext ctx){try{nativeOrigin(codec,false,"channelRead","readExit");afterRead(ctx);}catch(RuntimeException|Error failure){if(!observationalOnly())throw failure;diagnosticFailure(failure);}}
    public final void codecFailure(Object codec,Throwable failure){
        if(observationalOnly()){diagnosticFailure(failure);return;}
        if(!matches(codec))throw new IllegalStateException("Foreign codec failure");failed(failure);
    }
    private void diagnosticFailure(Throwable failure){try{failed(failure);}catch(Throwable ignored){}}
    protected boolean observesDecodeReturn(){return false;}
    public final void decodeExit(Object codec,ByteBuf buffer){
        if(!observesDecodeReturn())return;
        try {
            if(codec!=decoder||!WALKER.walk(frames->{var chain=frames.limit(3).toList();return chain.size()==3
                    &&chain.get(0).getDeclaringClass()==NativeCodecBoundaryOwner.class
                    &&chain.get(1).getDeclaringClass()==PacketDecoder.class&&chain.get(1).getMethodName().contains("earthplatform$latchBeforeDecoderCatch")
                    &&chain.get(2).getDeclaringClass()==PacketDecoder.class&&chain.get(2).getMethodName().equals("decode");}))
                throw new IllegalStateException("Original decode-return boundary required");
            afterDecode(buffer);
        }catch(RuntimeException|Error failure){if(!observationalOnly())throw failure;diagnosticFailure(failure);}
    }
    protected void afterDecode(ByteBuf buffer){}
    protected abstract Object beforeWrite(ChannelHandlerContext ctx,Object packet,ChannelPromise promise);
    protected abstract void afterWrite(ChannelHandlerContext ctx,Object scope);
    protected abstract void beforeAllocation(ChannelHandlerContext ctx,Object packet,boolean direct);
    protected abstract void afterAllocation(ChannelHandlerContext ctx,ByteBuf buffer);
    protected abstract void beforeRead(ChannelHandlerContext ctx,Object message);
    protected abstract void afterRead(ChannelHandlerContext ctx);
    protected abstract void failed(Throwable failure);
}
