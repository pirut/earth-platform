package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.PreferHeapByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.network.NoOpFrameDecoder;
import net.minecraft.network.NoOpFrameEncoder;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.neoforged.neoforge.gametest.GameTestHolder;

/** Only the inherited Minecraft codec boundary is tested. No Local peer, scheduler or Host claim. */
@GameTestHolder("earthplatform_codec_probe")
public final class NativeCodecBoundaryGameTests {
    private static Attempt retained;
    private static NativeDistanceCpuBudget.Reservation charge;
    @GameTestGenerator public static Collection<TestFunction> tests(){
        if(!Boolean.getBoolean("earthplatform.nativeCodecBoundaryProbe")||!Boolean.getBoolean("neoforge.gameTestServer"))return List.of();
        return List.of(new TestFunction("nativeCodecBoundaryProbe","nativecodecboundarygametests.original_encode_decode_outer_boundaries",
            "earthplatform_codec_probe:native_survival_empty",100,0,true,NativeCodecBoundaryGameTests::run));
    }
    private static synchronized Attempt open(GameTestHelper helper)throws Exception{
        if(retained!=null||charge!=null)throw new IllegalStateException("One codec attempt per process");
        charge=NativeDistanceCpuBudget.global().reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA,256*1024);
        retained=new Attempt(); // The precharge and failed/partial attempt are never released or replaced.
        try{
            NativeOriginalCodecPins.require();requireInactiveProfiler();
            var protocol=LoginProtocols.SERVERBOUND;
            retained.encoder=new PacketEncoder<>(protocol);retained.decoder=new PacketDecoder<>(protocol);
            if(!(retained.encoder instanceof NativeCodecBoundaryAccess)||!(retained.decoder instanceof NativeCodecBoundaryAccess))
                throw new IllegalStateException("Minecraft inherited codec transform marker missing");
            retained.allocator=new UnpooledByteBufAllocator(false,true);
            retained.heapAllocator=new PreferHeapByteBufAllocator(retained.allocator);
            retained.owner=new Observer(retained);
            ((NativeCodecBoundaryAccess)retained.encoder).earthplatform$bindCodecBoundary(retained.encoder,retained.owner);
            ((NativeCodecBoundaryAccess)retained.decoder).earthplatform$bindCodecBoundary(retained.decoder,retained.owner);
            // Registration is deliberately deferred until the exact channel is retained and configured.
            retained.channel=new EmbeddedChannel(false,false,new NoOpFrameDecoder(),new NoOpFrameEncoder(),retained.encoder,retained.decoder);
            retained.channel.config().setAllocator(retained.heapAllocator);
            retained.channel.register();
            return retained;
        }catch(Exception|Error failure){retained.fail(failure);throw failure;}
    }
    private static void requireInactiveProfiler(){
        var profiler=net.minecraft.util.profiling.jfr.JvmProfiler.INSTANCE;
        boolean exact=profiler.getClass()==net.minecraft.util.profiling.jfr.JvmProfiler.NoOpProfiler.class
                ||profiler.getClass()==net.minecraft.util.profiling.jfr.JfrProfiler.class;
        if(!exact||profiler.isRunning()||net.minecraft.util.profiling.jfr.event.PacketSentEvent.TYPE.isEnabled()
                ||net.minecraft.util.profiling.jfr.event.PacketReceivedEvent.TYPE.isEnabled()
                ||net.minecraft.util.profiling.jfr.event.NetworkSummaryEvent.TYPE.isEnabled())
            throw new IllegalStateException("Original codec diagnostic requires inactive native packet/summary profiling");
    }
    private static void run(GameTestHelper helper){
        Attempt attempt=null;Throwable primary=null;
        try{
            attempt=open(helper);
            for(int i=0;i<16;i++)round(attempt,i);
            attempt.owner.check(attempt.owner.writes==16&&attempt.owner.allocations==16&&attempt.owner.reads==16,"All original codec boundaries must run once per packet");
            requireOriginalDecoderFailure(attempt);
            requireCounterfeitOriginRefused(attempt);
            requireOriginalFailureCleanup(attempt);
        }catch(Exception failure){primary=failure;if(retained!=null)retained.fail(failure);throw new IllegalStateException("Original codec boundary probe failed",failure);}
        catch(Error failure){primary=failure;if(retained!=null)retained.fail(failure);throw failure;}
        finally{
            Attempt exact=attempt!=null?attempt:retained;
            if(exact!=null)try{exact.finish();}catch(RuntimeException|Error cleanup){exact.fail(cleanup);if(primary!=null)primary.addSuppressed(cleanup);else throw cleanup;}
        }
        helper.succeed();
    }
    private static void round(Attempt attempt,int sequence){
        Observer owner=attempt.owner;owner.check(owner.packet==null&&owner.buffer==null,"Previous codec round has unresolved aliases");
        owner.packet=new ServerboundHelloPacket("codec",new java.util.UUID(0,sequence));
        ChannelFuture returned=attempt.channel.writeAndFlush(owner.packet);
        owner.check(returned==owner.promise&&returned.isDone()&&returned.isSuccess(),"Original returned write promise differs or failed");
        Object outbound=attempt.channel.readOutbound();attempt.lastObserved=outbound;
        owner.check(outbound==owner.buffer&&outbound instanceof ByteBuf,"Original encoder buffer identity was replaced");
        ByteBuf buffer=(ByteBuf)outbound;
        owner.check(buffer.refCnt()==1&&!buffer.isDirect()&&buffer.capacity()==256&&buffer.readableBytes()>0&&buffer.readableBytes()<=31,"Scalar native wire/allocator bound differs");
        // Genuine original Embedded inbound operation owns this same buffer; never copy or synthesize it.
        attempt.channel.writeInbound(buffer);
        owner.check(owner.readDepth==0&&buffer.refCnt()==0,"Outer decoder return did not release exact input");
        Object decoded=attempt.channel.readInbound();attempt.lastObserved=decoded;
        owner.check(decoded!=owner.packet&&decoded instanceof ServerboundHelloPacket ack&&ack.name().equals("codec")&&ack.profileId().equals(new java.util.UUID(0,sequence)),"Original decoder did not produce the exact scalar image");
        owner.check(attempt.channel.readInbound()==null&&attempt.channel.readOutbound()==null,"Unexpected native codec output");
        owner.packet=null;owner.promise=null;owner.buffer=null;attempt.lastObserved=null;
    }
    /** Explicit negative diagnostic input. No production buffer or packet is changed. */
    private static void requireOriginalDecoderFailure(Attempt attempt){
        Observer owner=attempt.owner;owner.packet=new ServerboundHelloPacket("codec",new java.util.UUID(0,16));
        ChannelFuture returned=attempt.channel.writeAndFlush(owner.packet);
        owner.check(returned==owner.promise&&returned.isSuccess(),"Original failure-case encode promise differs");
        Object actual=attempt.channel.readOutbound();attempt.lastObserved=actual;
        owner.check(actual==owner.buffer&&owner.buffer.capacity()==256&&owner.buffer.readableBytes()<=31,"Failure-case original buffer differs");
        // One extra byte makes original PacketDecoder's full-consumption check fail, without growth.
        owner.buffer.writeByte(0);
        try{attempt.channel.writeInbound(owner.buffer);throw new IllegalStateException("Malformed scalar input unexpectedly decoded");}
        catch(io.netty.handler.codec.DecoderException expected){
            owner.check(attempt.firstFailure instanceof java.io.IOException&&expected.getCause()==attempt.firstFailure,
                    "Original decode failure was not latched before Netty's native catch/wrap");
            owner.check(owner.buffer.refCnt()==1&&owner.cumulative()==owner.buffer&&owner.buffer.readableBytes()==1
                    &&owner.readDepth==1&&owner.writes==17&&owner.allocations==17&&owner.reads==16,
                    "Original failed decode must retain its still-readable exact cumulation");
        }
        // Failed packet/promise/buffer/observer identities and the whole debit remain retained.
    }
    private static void requireCounterfeitOriginRefused(Attempt attempt){
        Observer owner=attempt.owner;Throwable originalFailure=attempt.firstFailure;int entries=owner.writeEntries;
        try{owner.writeEnter(attempt.encoder,attempt.channel.pipeline().context(attempt.encoder),owner.packet,owner.promise);
            throw new AssertionError("Direct helper invocation forged an original codec boundary");}
        catch(IllegalStateException expected){
            owner.check(expected.getMessage().equals("Original codec boundary invocation required")&&owner.writeEntries==entries
                    &&attempt.firstFailure==originalFailure,"Counterfeit origin entered callback or replaced first failure");
        }
    }
    /** Original channel close retries the remaining id byte, then releases cumulation in its finally. */
    private static void requireOriginalFailureCleanup(Attempt attempt){
        Observer owner=attempt.owner;Throwable original=attempt.firstFailure;
        owner.check(original instanceof java.io.IOException&&owner.cumulative()==owner.buffer&&owner.buffer.refCnt()==1,
                "Expected failed-flight ownership was lost before original cleanup");
        try{attempt.finish();throw new AssertionError("Residual scalar input unexpectedly survived native close without error");}
        catch(io.netty.handler.codec.DecoderException expected){
            attempt.cleanupFailure=expected;
            owner.check(attempt.originalCleanupDecodeFailure==expected&&expected.getCause() instanceof IndexOutOfBoundsException,
                    "Original residual-byte cleanup decoder failure identity differs");
            owner.check(attempt.firstFailure==original&&attempt.finished&&!attempt.finishing&&!attempt.channel.isOpen()
                    &&owner.buffer.refCnt()==0&&owner.cumulative()==null&&owner.readDepth==1
                    &&owner.writes==17&&owner.allocations==17&&owner.reads==16,
                    "Original cleanup did not release the retained input or replaced first failure");
            owner.check(attempt.channel.readInbound()==null&&attempt.channel.readOutbound()==null,
                    "Original finishAndReleaseAll left unexpected fixture output");
        }
    }
    private static final class Attempt {
        PacketEncoder<?> encoder;PacketDecoder<?> decoder;UnpooledByteBufAllocator allocator;
        PreferHeapByteBufAllocator heapAllocator;EmbeddedChannel channel;Observer owner;Object lastObserved;
        volatile Throwable firstFailure;Throwable originalCleanupDecodeFailure,cleanupFailure;boolean secondary,finished,finishing;
        synchronized void fail(Throwable failure){if(firstFailure==null)firstFailure=failure;else if(firstFailure!=failure&&!secondary){firstFailure.addSuppressed(failure);secondary=true;}}
        void finish(){if(finished)return;finished=true;if(channel!=null){finishing=true;try{channel.finishAndReleaseAll();}finally{finishing=false;}}}
    }
    private static final class Observer extends NativeCodecBoundaryOwner {
        private final Attempt attempt;private final Thread thread=Thread.currentThread();private final Field cumulation;
        private ServerboundHelloPacket packet;private ChannelPromise promise;private ByteBuf buffer;
        private int writeDepth,readDepth,writes,allocations,reads,writeEntries;
        Observer(Attempt attempt)throws Exception{super(attempt.encoder,attempt.decoder);this.attempt=attempt;cumulation=ByteToMessageDecoder.class.getDeclaredField("cumulation");cumulation.setAccessible(true);}
        private Object cumulative(){try{return cumulation.get(attempt.decoder);}catch(ReflectiveOperationException failure){attempt.fail(failure);throw new IllegalStateException(failure);}}
        void check(boolean value,String message){if(!value){var failure=new IllegalStateException(message);attempt.fail(failure);throw failure;}}
        private void context(ChannelHandlerContext ctx){requireInactiveProfiler();check(Thread.currentThread()==thread&&ctx.channel()==attempt.channel&&ctx.executor()==attempt.channel.eventLoop()&&ctx.alloc()==attempt.heapAllocator,"Exact confined codec channel/executor/allocator changed");}
        protected void beforeWrite(ChannelHandlerContext ctx,Object value,ChannelPromise exact){writeEntries++;context(ctx);check(writeDepth==0&&readDepth==0&&value==packet&&packet!=null&&buffer==null&&promise==null,"Encoder write was reentered or foreign");promise=exact;writeDepth=1;}
        protected void afterWrite(ChannelHandlerContext ctx){context(ctx);check(writeDepth==1&&buffer!=null&&!promise.isDone(),"Original inherited encoder write boundary changed");writeDepth=0;writes++;}
        protected void beforeAllocation(ChannelHandlerContext ctx,Object value,boolean direct){context(ctx);check(writeDepth==1&&value==packet&&buffer==null&&direct,"Original initial allocator request differs");}
        protected void afterAllocation(ChannelHandlerContext ctx,ByteBuf exact){buffer=exact;context(ctx);check(exact.refCnt()==1&&!exact.isDirect()&&exact.capacity()==256&&exact.readableBytes()==0,"Original heap allocation differs");allocations++;}
        protected void beforeRead(ChannelHandlerContext ctx,Object value){context(ctx);check(readDepth==0&&writeDepth==0&&value==buffer&&cumulative()==null&&buffer.refCnt()==1,"Decoder has a foreign input or prior cumulation");readDepth=1;}
        protected void afterRead(ChannelHandlerContext ctx){context(ctx);check(readDepth==1&&cumulative()==null&&buffer.refCnt()==0,"Original inherited decoder outer drain differs");readDepth=0;reads++;}
        protected void failed(Throwable failure){if(attempt.finishing&&attempt.originalCleanupDecodeFailure==null)attempt.originalCleanupDecodeFailure=failure;attempt.fail(failure);}
    }
}
