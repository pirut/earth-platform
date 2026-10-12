package io.earthplatform.minecraft.survival.interaction.host;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.PacketEncoder;

/** Finite ordinary ownership observation. The index owns no buffer, promise, packet or codec.
 * Native executors/pipelines retain their original payloads; unresolved weak entries never settle.
 * Success distinguishes sender completion, decoder completion and original outer release return.
 */
final class NativeLocalByteObservation {
    private static final int CAPACITY=16_384, MAX_CODECS=32;
    private final Codec[] codecs=new Codec[MAX_CODECS];
    private final Flight[] flights=new Flight[CAPACITY];
    private int codecCount, flightCount, failure;
    private long promiseCompleted, decoded, released, bundleCalls, passThroughs, passThroughReturns;
    private int activeWrites;
    private final boolean[] partialBundle=new boolean[2];
    private static final class Flight {
        final int side;
        final WeakReference<ChannelPromise> promise;
        WeakReference<ByteBuf> buffer;
        final NativeOrdinaryPromiseFrontier completion=new NativeOrdinaryPromiseFrontier();
        boolean writeReturned, decodeReturned, outerReturned;
        Flight(int side,ChannelPromise promise){this.side=side;this.promise=new WeakReference<>(promise);}
    }
    /** Native codec strongly owns this observer; global index holds only its weak identity. */
    private static final class Codec {
        final WeakReference<Object> identity;
        final WeakReference<Object> protocol;
        final boolean outbound;
        Flight current;
        WriteScope writes;
        boolean cleanInput;
        int side=-1;
        String[] observedProfile;
        boolean profileAttempted;
        Codec(Object codec,Object protocol,boolean outbound){this.identity=new WeakReference<>(codec);this.protocol=new WeakReference<>(protocol);this.outbound=outbound;}
    }
    private static final class WriteScope extends NativeOrdinaryInvocationScope {
        final Flight flight;
        final Object configurationTicket; // Weak/scalar original root metadata, never the native task.
        WriteScope(WriteScope previous,Flight flight,Object configurationTicket){super(previous);this.flight=flight;this.configurationTicket=configurationTicket;}
    }
    private static final class Completion implements io.netty.channel.ChannelFutureListener {
        private final NativeLocalByteObservation index;
        private final Flight flight;
        Completion(NativeLocalByteObservation index,Flight flight){this.index=index;this.flight=flight;}
        @Override public void operationComplete(io.netty.channel.ChannelFuture future){
            if(!flight.completion.beginCompletion()){try{index.fail(3);}catch(Throwable ignored){}return;}
            boolean success=false;
            try{success=check(future);}catch(Throwable failure){try{index.fail(5);}catch(Throwable ignored){}}
            future=null; // Added native payload alias is gone before the field-only publication tail.
            synchronized(index){if(!success)index.fail(4);index.promiseCompleted++;flight.completion.completionReturned(success);}
        }
        private boolean check(io.netty.channel.ChannelFuture future){
            synchronized(index){if(future!=flight.promise.get()){index.fail(3);return false;}}
            return future.isSuccess();
        }
    }
    private final class Owner extends NativeCodecBoundaryOwner {
        private final Codec record;
        Owner(Object exact,Codec record){super(exact,record.outbound);this.record=record;}
        @Override protected boolean observationalOnly(){return true;}
        @Override protected boolean observesDecodeReturn(){return true;}
        @Override protected Object beforeWrite(ChannelHandlerContext context,Object packet,ChannelPromise promise){
            if(!NativeLocalInstalledPins.contextClass(context.getClass())){fail(23);return null;}
            int side=NativeLocalCausalObservation.sideOf(context.channel());
            observeProfile(record,context,side);
            final Flight flight;final WriteScope scope;
            synchronized(NativeLocalByteObservation.this){
                if(side<0){fail(1);return null;}
                record.side=side;
                if(!NativeOrdinaryInvocationScope.canEnter(record.writes)){fail(28);return null;}
                if(!NativeOriginalPacketWriteShape.accepts(packet)){
                    Object root=NativeLocalRootObservation.configurationPassThrough(context.channel(),packet);
                    if(root==null){fail(29);return null;}
                    scope=new WriteScope(record.writes,null,root);record.writes=scope;activeWrites++;passThroughs++;
                    return scope;
                }
                if(record.current!=null||flightCount==CAPACITY||promise.getClass()!=DefaultChannelPromise.class){fail(2);return null;}
                flight=new Flight(side,promise);flights[flightCount++]=flight;record.current=flight;
                scope=new WriteScope(record.writes,flight,null);record.writes=scope;activeWrites++;
            }
            boolean succeeded=false;
            try{
                synchronized(promise){
                    if(!freshPromise(promise,context)){fail(25);return scope;}
                    promise.addListener(new Completion(NativeLocalByteObservation.this,flight));succeeded=true;
                }
            }
            finally{synchronized(NativeLocalByteObservation.this){if(!succeeded)fail(24);flight.completion.registrationReturned(succeeded);}}
            return scope;
        }
        @Override protected void afterWrite(ChannelHandlerContext context,Object opaque){finishWrite(opaque,false);}
        @Override protected void afterFailedWrite(ChannelHandlerContext context,Object opaque,Throwable original){
            fail(18);finishWrite(opaque,true);
        }
        private void finishWrite(Object opaque,boolean failed){synchronized(NativeLocalByteObservation.this){
            if(!(opaque instanceof WriteScope scope)||!scope.returnedFrom(record.writes)){fail(30);return;}
            if(scope.flight==null){
                if(scope.configurationTicket==null){fail(29);return;}
                if(!failed)passThroughReturns++;
            }else{
                if(record.current!=scope.flight){fail(6);return;}
                // No allocation on an accepted Packet is a classifier/native matcher disagreement.
                if(!failed&&scope.flight.buffer==null)fail(31);
                if(!failed)scope.flight.writeReturned=true;
                record.current=null; // Flight remains indexed and charged on every failure.
            }
            record.writes=(WriteScope)scope.previous;activeWrites--;
        }}
        @Override protected void beforeAllocation(ChannelHandlerContext context,Object packet,boolean direct){
            synchronized(NativeLocalByteObservation.this){
                if(record.writes==null||record.writes.flight==null||record.current!=record.writes.flight
                        ||!NativeOriginalPacketWriteShape.accepts(packet))fail(7);
            }
        }
        @Override protected void afterAllocation(ChannelHandlerContext context,ByteBuf buffer){synchronized(NativeLocalByteObservation.this){
            if(record.writes==null||record.writes.flight==null||record.current!=record.writes.flight||record.current.buffer!=null){fail(8);return;}
            record.current.buffer=new WeakReference<>(buffer);
        }}
        @Override protected void beforeRead(ChannelHandlerContext context,Object message){synchronized(NativeLocalByteObservation.this){
            if(!NativeLocalInstalledPins.contextClass(context.getClass())){fail(23);return;}
                int side=NativeLocalCausalObservation.sideOf(context.channel()); record.side=side;
            observeProfile(record,context,side);
            if(side<0||record.current!=null||!(message instanceof ByteBuf buffer)){fail(9);return;}
            Object exact=record.identity.get();
            if(exact==null||exact.getClass()!=PacketDecoder.class||!NativeLocalInstalledPins.byteBufClass(buffer.getClass())){fail(10);return;}
            record.cleanInput=cumulation(exact)==null;
            if(!record.cleanInput){fail(11);return;}
            for(int i=0;i<flightCount;i++){
                Flight candidate=flights[i];
                if(candidate.buffer!=null&&candidate.buffer.get()==buffer&&!candidate.decodeReturned){
                    if(candidate.side==side){fail(12);return;}record.current=candidate;return;
                }
            }
            fail(13);
        }}
        @Override protected void afterDecode(ByteBuf buffer){synchronized(NativeLocalByteObservation.this){
            Flight flight=record.current;
            if(flight==null||flight.buffer.get()!=buffer||!record.cleanInput||!NativeLocalInstalledPins.byteBufClass(buffer.getClass())){fail(14);return;}
            // The exact successfully returned installed PacketDecoder body already establishes
            // its trailing-byte check (or its empty-input early return). Do not add a virtual
            // read through a possibly leak-aware buffer/delegate graph here.
            if(flight.decodeReturned){fail(16);return;}decoded++;flight.decodeReturned=true;
        }}
        @Override protected void afterRead(ChannelHandlerContext context){synchronized(NativeLocalByteObservation.this){
            Flight flight=record.current;Object exact=record.identity.get();
            if(flight==null||!flight.decodeReturned||exact==null||cumulation(exact)!=null){fail(17);return;}
            // Source-pinned ByteToMessageDecoder finally released/null'd its clean-input cumulation.
            // Do not call refCnt on a pooled object after release: another thread may already recycle it.
            flight.outerReturned=true;released++;record.current=null;record.cleanInput=false;
        }}
        @Override protected void failed(Throwable ignored){fail(18);}
    }
    synchronized void constructor(Object exact,Object protocol,boolean outbound){
        if(codecCount==MAX_CODECS||exact.getClass()!=(outbound?PacketEncoder.class:PacketDecoder.class)
                ||!(exact instanceof NativeCodecBoundaryAccess access)){fail(19);return;}
        if(outbound&&!NativeOriginalPacketWriteShape.exactMatcher(exact)){fail(32);return;}
        Codec record=new Codec(exact,protocol,outbound);codecs[codecCount++]=record;
        Owner owner=new Owner(exact,record);access.earthplatform$bindCodecBoundary(exact,owner);
    }
    synchronized void bundle(int side,boolean partial,boolean failed){
        if(side<0||side>1){fail(20);return;}bundleCalls++;partialBundle[side]=partial;if(failed)fail(21);
    }
    private synchronized void observeProfile(Codec record,ChannelHandlerContext context,int side){
        if(record.profileAttempted||side<0)return;record.profileAttempted=true;
        try{record.observedProfile=NativeLocalPipelineObservation.capture(context.channel(),record.protocol.get(),side);}
        catch(ReflectiveOperationException|RuntimeException failure){fail(27);}
    }
    synchronized void appendProfiles(java.util.List<String> lines){
        for(int i=0;i<codecCount;i++)if(codecs[i].observedProfile!=null)
            for(String line:codecs[i].observedProfile)lines.add(line);
    }
    private boolean freshPromise(ChannelPromise promise,ChannelHandlerContext context){
        try{
            Field listeners=io.netty.util.concurrent.DefaultPromise.class.getDeclaredField("listeners");listeners.setAccessible(true);
            Field executor=io.netty.util.concurrent.DefaultPromise.class.getDeclaredField("executor");executor.setAccessible(true);
            Object actualExecutor=context.executor();
            return listeners.get(promise)==null&&executor.get(promise)==actualExecutor
                    &&NativeLocalInstalledPins.loopClass(actualExecutor.getClass(),NativeLocalCausalObservation.sideOf(context.channel()))
                    &&promise.channel()==context.channel();
        }catch(ReflectiveOperationException|RuntimeException failure){fail(26);return false;}
    }
    private Object cumulation(Object decoder){
        try {Field field=ByteToMessageDecoder.class.getDeclaredField("cumulation");field.setAccessible(true);return field.get(decoder);}
        catch(ReflectiveOperationException|RuntimeException ignored){fail(22);return this;}
    }
    private synchronized void fail(int code){if(failure==0)failure=code;}
    synchronized String summary(){
        int unresolved=0;for(int i=0;i<flightCount;i++){Flight f=flights[i];if(!f.completion.observedSettled()||!f.writeReturned||!f.decodeReturned||!f.outerReturned)unresolved++;}
        return "LOCAL_BYTES codecs="+codecCount+" flights="+flightCount+" senderCompletions="+promiseCompleted+" decodeReturns="+decoded
                +" outerReleaseReturns="+released+" pending="+unresolved+" bundleCalls="+bundleCalls
                +" passThroughs="+passThroughs+" passThroughReturns="+passThroughReturns+" activeWriteScopes="+activeWrites
                +" clientPartialBundle="+partialBundle[0]+" serverPartialBundle="+partialBundle[1]+" failure="+failure;
    }
}
