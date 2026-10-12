package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.concurrent.DefaultPromise;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;

/** Original Minecraft-callsite adapter for the reusable physical ledger.
 * The only factory is a narrowly confined executable seam probe, with no player, Host, registration,
 * bootstrap or production admission. One diagnostic 256 KiB existing-global-pool debit prepays
 * the constructor graph, fixed 24 KiB ledger envelope, stack/callback scratch and failure retention.
 * Extending the factory to real sessions requires prepaid shared ownership and producer closure.
 */
public final class NativeOriginalConnectionOutputs {
    private static final StackWalker WALKER=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static NativeDistanceCpuBudget.Reservation diagnosticCharge;
    private static Attempt attempt;
    private static final class Attempt {
        Connection connection;Channel channel;NativeOriginalConnectionOutputs owner;volatile Throwable firstFailure;
        synchronized void fail(Throwable failure){if(firstFailure==null)firstFailure=failure;else if(firstFailure!=failure&&firstFailure.getSuppressed().length==0)firstFailure.addSuppressed(failure);}
    }
    private final Connection connection;
    private final EmbeddedChannel channel;
    private final Thread thread=Thread.currentThread();
    private final NativePhysicalOutputLedger ledger=new NativePhysicalOutputLedger();
    private final NativePhysicalOutputLedger.Cell[] cells=new NativePhysicalOutputLedger.Cell[12];
    private final ChannelFutureListener[] observers=new ChannelFutureListener[12];
    private int retained,retiring;
    private long boundarySequence,boundary,writes,flushes,consumed;
    private boolean writing,flushing,closed;
    private NativeOriginalConnectionOutputs(Attempt retainedAttempt,Connection connection,EmbeddedChannel channel){
        retainedAttempt.owner=this;this.connection=connection;this.channel=channel;
        if(connection.getClass()!=Connection.class||channel.getClass()!=EmbeddedChannel.class
                ||connection.channel()!=channel||!channel.isOpen()||connection.getPacketListener()!=null)
            throw new IllegalStateException("Original Connection seam has foreign constructor lineage");
        if(!(connection instanceof NativeOriginalConnectionAccess access))
            throw new IllegalStateException("Original Minecraft Connection callsite transform is absent");
        access.earthplatform$bindOriginalOutputs(connection,this);
    }
    static synchronized NativeOriginalConnectionOutputs openProbe(GameTestHelper helper){
        if(!Boolean.getBoolean("earthplatform.nativeConnectionOutputProbe")||!Boolean.getBoolean("neoforge.gameTestServer")
                ||!helper.getLevel().getServer().isSameThread())throw new IllegalStateException("Exact server-owned seam probe required");
        if(diagnosticCharge!=null)throw new IllegalStateException("One original Connection seam attempt per process");
        // This is the existing process-global cap, not a new pool or claimed survival Host allowance.
        // The reservation and attempt are never cleared, including every failed/partial constructor.
        diagnosticCharge=NativeDistanceCpuBudget.global().reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA,256*1024);
        attempt=new Attempt();
        try{
            NativeOriginalConnectionPins.require();
            attempt.connection=new Connection(PacketFlow.SERVERBOUND);
            EmbeddedChannel channel=new EmbeddedChannel(attempt.connection);attempt.channel=channel;
            return new NativeOriginalConnectionOutputs(attempt,attempt.connection,channel);
        }catch(RuntimeException|Error failure){
            attempt.fail(failure);
            // channelActive may have published a partially constructed native channel before new returned.
            if(attempt.channel==null&&attempt.connection!=null)attempt.channel=attempt.connection.channel();
            try{if(attempt.channel instanceof EmbeddedChannel channel)channel.finishAndReleaseAll();}
            catch(RuntimeException|Error cleanup){attempt.fail(cleanup);}
            throw failure;
        }
    }
    public boolean matches(Connection exact){return exact==connection;}
    private void owner(){
        if(Thread.currentThread()!=thread||closed||connection.channel()!=channel||connection.getPacketListener()!=null)
            throw refusal("Original Connection seam owner changed");
        ledger.healthy();
    }
    private void nativeCaller(String method){
        boolean exact=WALKER.walk(frames->{var path=frames.limit(12).toList();
            return path.size()>2&&path.get(2).getDeclaringClass()==Connection.class
                &&path.stream().skip(2).anyMatch(frame->frame.getDeclaringClass()==Connection.class&&frame.getMethodName().equals(method));});
        if(!exact)throw refusal("Original Connection observer lacks the exact transformed callsite");
    }
    // The probe constructs only this scalar packet, within the fixed diagnostic ledger envelope (not a claimed Host debit).
    void sendUnflushed(int sequence){sendScalar(sequence,false);}
    void sendImmediate(int sequence){sendScalar(sequence,true);}
    private void sendScalar(int sequence,boolean flush){
        owner();ledger.requireCapacity(NativePhysicalOutputLedger.Lane.ACTION,1);
        if(retained==cells.length)throw refusal("Original Connection seam capacity exhausted");
        Packet<?> packet=new ClientboundBlockChangedAckPacket(sequence);
        cells[retained++]=ledger.reserve(NativePhysicalOutputLedger.Lane.ACTION,packet,this);
        connection.send(packet,null,flush);ledger.healthy();
    }
    public ChannelFuture originalWrite(Connection exact,Channel actual,Object packet,boolean flush,Operation<ChannelFuture> original){
        owner();nativeCaller("doSendPacket");
        if(exact!=connection||actual!=channel||writing||flushing)throw refusal("Original write receiver/order changed");
        var cell=ledger.reserved(packet);writing=true;
        if(flush){
            if(boundarySequence==Long.MAX_VALUE)throw refusal("Original flush sequence exhausted");
            flushing=true;boundary=++boundarySequence;ledger.enterFlush(boundary);
        }
        try{
            ChannelFuture returned=original.call(actual,packet);
            if(returned.getClass()!=DefaultChannelPromise.class||returned.channel()!=channel)
                throw refusal("Original unflushed write returned a foreign promise");
            if(promiseField(returned,"listener")!=null||promiseField(returned,"listeners")!=null
                    ||promiseField(returned,"executor")!=channel.eventLoop())throw refusal("Original promise constructor/listener lineage changed");
            ledger.writeEntered(cell,returned,boundary);ledger.accepted(cell,returned);
            int index=-1;for(int i=0;i<retained;i++)if(cells[i]==cell){index=i;break;}
            if(index<0)throw refusal("Original write lost its exact prepared cell");
            ChannelFutureListener observer=future->{
                try{ledger.promiseCompleted(cell,future,future.isSuccess(),future.cause());}
                catch(RuntimeException|Error failure){ledger.fail(failure);throw failure;}
            };
            observers[index]=observer;
            // doSendPacket adds native failure listeners only after this original write returns.
            returned.addListener(observer);writes++;ledger.healthy();
            if(!flush&&returned.isDone())throw refusal("Original unflushed promise completed before its genuine flush");
            if(flush){ledger.exitFlush(boundary);flushes++;}return returned;
        }catch(RuntimeException|Error failure){ledger.fail(failure);throw failure;}
        finally{writing=false;if(flush){flushing=false;boundary=0;}}
    }
    public Channel originalTickFlush(Connection exact,Channel actual,Operation<Channel> original){
        owner();nativeCaller("tick");
        if(exact!=connection||actual!=channel||writing||flushing)throw refusal("Original tick flush receiver/order changed");
        if(boundarySequence==Long.MAX_VALUE)throw refusal("Original flush sequence exhausted");
        flushing=true;boundary=++boundarySequence;ledger.enterFlush(boundary);
        try{Channel returned=original.call(actual);if(returned!=actual)throw refusal("Original tick flush replaced channel identity");
            ledger.healthy();ledger.exitFlush(boundary);flushes++;return returned;
        }catch(RuntimeException|Error failure){ledger.fail(failure);throw failure;}
        finally{flushing=false;boundary=0;}
    }
    /** Called from Connection.exceptionCaught HEAD before the original native handler can catch/report. */
    public void nativeFailure(Throwable failure){ledger.fail(failure);attempt.fail(failure);}
    void tickOriginal(){owner();connection.tick();ledger.healthy();}
    void requireEnqueuedOnly(int expected){
        owner();if(writes!=expected||ledger.pending()!=retained||ledger.nextForConsumption()!=null||ledger.settled())
            throw refusal("Original accepted enqueue was conflated with physical completion");
        for(int i=0;i<retained;i++)if(cells[i].state()!=NativePhysicalOutputLedger.State.ACCEPTED_ENQUEUE
                ||((ChannelFuture)cells[i].promise()).isDone())throw refusal("Original unflushed promise completed early");
    }
    void consumeOriginal(){
        owner();if(writing||flushing||retiring>=retained)throw refusal("Consumption entered before original native call returned");
        var cell=ledger.nextForConsumption();if(cell!=cells[retiring])throw refusal("Original flush/promise order diverged");
        Object actual=channel.readOutbound();ChannelFuture promise=(ChannelFuture)cell.promise();
        if(!promise.isSuccess())throw refusal("Original successful flush lost its promise success");
        if(promiseField(promise,"listener")!=null||promiseField(promise,"listeners")!=null
                ||!Boolean.FALSE.equals(promiseField(promise,"notifyingListeners")))throw refusal("Original promise listener aliases have not returned");
        ledger.consumed(cell,actual,promise);promise.removeListener(observers[retiring]);observers[retiring]=null;consumed++;
        if(retiring==retained-1&&channel.readOutbound()!=null)throw refusal("Original fixture has unowned output");
        // All packet/promise method locals die before retireConsumed is called by the test.
    }
    void retireConsumed(){
        owner();if(writing||flushing||retiring>=retained)throw refusal("Original callsite aliases remain live");
        if(observers[retiring]!=null||cells[retiring].state()!=NativePhysicalOutputLedger.State.CONSUMED)
            throw refusal("Original native packet aliases have not drained");
        ledger.release(cells[retiring]);cells[retiring]=null;retiring++;
        if(retiring==retained){retained=0;retiring=0;if(!ledger.settled())throw refusal("Exact consumption did not settle the reusable owner");}
    }
    void requireCompleted(int packets,int boundaries){
        owner();if(writes!=packets||consumed!=packets||flushes!=boundaries||!ledger.settled())throw refusal("Original seam counts changed");
    }
    void requireCounterfeitFlushRefused(){
        owner();long before=flushes;int[] invocations={0};IllegalStateException refused=null;
        try{originalTickFlush(connection,channel,args->{invocations[0]++;return channel;});}
        catch(IllegalStateException expected){refused=expected;}
        if(refused==null||ledger.failure()!=refused||invocations[0]!=0||flushes!=before)
            throw new IllegalStateException("Public helper counterfeited native flush authority");
    }
    void finishOriginal(){
        if(closed)return;closed=true;
        // Cleanup never pretends unresolved ledger ownership completed and never reopens this owner.
        try{channel.finishAndReleaseAll();}catch(RuntimeException|Error failure){ledger.fail(failure);attempt.fail(failure);throw failure;}
    }
    private static Object promiseField(ChannelFuture promise,String name){
        try{var field=DefaultPromise.class.getDeclaredField(name);field.setAccessible(true);return field.get(promise);}
        catch(ReflectiveOperationException failure){throw new IllegalStateException("Original promise field unavailable: "+name,failure);}
    }
    private IllegalStateException refusal(String text){var failure=new IllegalStateException(text);ledger.fail(failure);attempt.fail(failure);return failure;}
}
