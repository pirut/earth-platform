package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.local.LocalChannel;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.network.Connection;
import net.minecraft.network.UnconfiguredPipelineHandler;

/** Additional weak/scalar observation of unchanged ordinary configuration/Consumer objects.
 * No callback replacement, extra listener, host binding, task cancellation or native queue mutation.
 */
public final class NativeLocalRootObservation {
    static final long BYTES=1_048_576L;
    public static final int SEND=1, ONCE=2, FLUSH=3, INBOUND=4, OUTBOUND=5;
    private static NativeDistanceCpuBudget.Reservation charge;
    private static volatile Owner retained;
    private static volatile int seedFailure;
    private NativeLocalRootObservation(){}

    private static final class Owner {
        final NativeOrdinaryRootFrontier consumers=new NativeOrdinaryRootFrontier(512);
        final NativeOrdinaryRootFrontier configurations=new NativeOrdinaryRootFrontier(128);
        final WeakReference<?>[] endpoints=new WeakReference<?>[2],threads=new WeakReference<?>[4];
        final SourceScope[] sources=new SourceScope[4];
        final HandlerScope[] handlers=new HandlerScope[4];
        final StackWalker walker=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
        final Field listeners;
        int threadCount,activeSources,activeHandlers;volatile int failure;
        String failedSite,failedHelper,failedOriginal;
        Owner() throws ReflectiveOperationException {
            listeners=io.netty.util.concurrent.DefaultPromise.class.getDeclaredField("listeners");listeners.setAccessible(true);
        }
        synchronized int thread(boolean admit){
            Thread current=Thread.currentThread();for(int i=0;i<threadCount;i++)if(threads[i].get()==current)return i;
            if(!admit||threadCount==4){fail(1);return -1;}threads[threadCount]=new WeakReference<>(current);return threadCount++;
        }
        synchronized void fail(int code){if(failure==0)failure=code;}
        synchronized boolean bound(Connection receiver){return endpoints[0]!=null&&endpoints[0].get()==receiver||endpoints[1]!=null&&endpoints[1].get()==receiver;}
        synchronized SourceScope source(){int i=thread(false);return i<0?null:sources[i];}
        synchronized HandlerScope handler(){int i=thread(false);return i<0?null:handlers[i];}
        synchronized void source(SourceScope scope){int i=thread(false);if(i>=0)sources[i]=scope;}
        synchronized void handler(HandlerScope scope){int i=thread(false);if(i>=0)handlers[i]=scope;}
    }
    public static final class SourceScope {
        private final Owner owner;
        private final WeakReference<Connection> connection;
        private final WeakReference<Thread> thread=new WeakReference<>(Thread.currentThread());
        private final SourceScope previous;
        private final int kind,depth;
        private NativeOrdinaryRootFrontier.Ticket ticket;
        private SourceScope(Owner owner,Connection connection,int kind,SourceScope previous){
            this.owner=owner;this.connection=new WeakReference<>(connection);this.kind=kind;this.previous=previous;this.depth=previous==null?1:previous.depth+1;
        }
    }
    public static final class HandlerScope {
        private final Owner owner;
        private final NativeOrdinaryRootFrontier.Ticket ticket;
        private final WeakReference<Object> context;
        private final WeakReference<Thread> thread=new WeakReference<>(Thread.currentThread());
        private final HandlerScope previous;
        private final int depth;
        private final boolean outbound;
        private HandlerScope(Owner owner,NativeOrdinaryRootFrontier.Ticket ticket,Object context,boolean outbound,HandlerScope previous){
            this.owner=owner;this.ticket=ticket;this.context=new WeakReference<>(context);this.outbound=outbound;this.previous=previous;this.depth=previous==null?1:previous.depth+1;
        }
    }
    /** Package-private call only from the already charged genuine-connect seed, before native factories. */
    static synchronized void seed() {
        if(charge!=null||retained!=null){if(retained!=null)retained.fail(2);return;}
        try {
            charge=NativeDistanceCpuBudget.global().reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA,BYTES);
            retained=new Owner();charge.claimRetainedOwner(retained);
        }catch(Throwable failure){seedFailure=1;}
    }
    /** Called only by the already-authenticated constructor capture. Endpoints are never exposed. */
    static synchronized void bind(int side,Connection connection){
        Owner owner=retained;if(owner==null)return;
        if(side<0||side>1||connection==null||connection.getClass()!=Connection.class||owner.endpoints[side]!=null){owner.fail(3);return;}
        synchronized(owner){owner.endpoints[side]=new WeakReference<>(connection);}
    }
    private static String sourceMethod(int kind){return switch(kind){case SEND->"send";case ONCE->"runOnceConnected";case FLUSH->"flushChannel";case INBOUND->"setupInboundProtocol";case OUTBOUND->"setupOutboundProtocol";default->"INVALID";};}
    private static String frame(List<StackWalker.StackFrame> frames,int i){
        if(i>=frames.size())return "MISSING";var f=frames.get(i);String c=f.getClassName(),m=f.getMethodName();return c.substring(0,Math.min(c.length(),120))+"#"+m.substring(0,Math.min(m.length(),120));
    }
    private static boolean originalName(String name,String expected){
        if(name.equals(expected))return true;String prefix=expected+"$mixinextras$wrapped";if(!name.startsWith(prefix))return false;
        String rest=name.substring(prefix.length());if(rest.isEmpty())return true;if(rest.length()>5||rest.charAt(0)!='$')return false;
        for(int i=1;i<rest.length();i++)if(rest.charAt(i)<'0'||rest.charAt(i)>'9')return false;return rest.length()>1;
    }
    private static boolean site(Class<?> target,String helper,String original){
        Owner owner=retained;if(owner==null||!NativeLocalInstalledPins.qualified()||owner.thread(true)<0)return false;
        return owner.walker.walk(stream->{var f=stream.limit(4).toList();boolean ok=f.size()==4&&f.get(0).getDeclaringClass()==NativeLocalRootObservation.class&&f.get(1).getDeclaringClass()==NativeLocalRootObservation.class&&f.get(2).getDeclaringClass()==target&&f.get(2).getMethodName().contains(helper)&&f.get(3).getDeclaringClass()==target&&originalName(f.get(3).getMethodName(),original);
            if(!ok)synchronized(owner){if(owner.failedSite==null){owner.failedSite=helper;owner.failedHelper=frame(f,2);owner.failedOriginal=frame(f,3);}owner.fail(4);}return ok;});
    }
    public static SourceScope sourceEntered(Connection connection,int kind){
        Owner owner=retained;if(owner==null||!owner.bound(connection)||!site(Connection.class,"earthplatform$rootsSource",sourceMethod(kind)))return null;
        synchronized(owner){
            SourceScope previous=owner.source();if(previous!=null&&previous.depth>=16){owner.fail(5);return null;}
            SourceScope scope=new SourceScope(owner,connection,kind,previous);owner.activeSources++;owner.source(scope);return scope;
        }
    }
    public static void sourceReturned(SourceScope scope,boolean threw){
        if(scope==null||!site(Connection.class,"earthplatform$rootsSource",sourceMethod(scope.kind)))return;
        if(scope.thread.get()!=Thread.currentThread()||scope.owner.source()!=scope){scope.owner.fail(6);return;}
        scope.owner.source(scope.previous);
        if(scope.ticket!=null)scope.ticket.owner.sourceReturned(scope.ticket,threw);
        else if(threw)scope.owner.fail(7);
        synchronized(scope.owner){scope.owner.activeSources--;}
    }
    public static Object beforeQueued(Connection connection,Object queue,Object expectedQueue,Object consumer,int kind){
        Owner owner=retained;if(owner==null||!owner.bound(connection)||!site(Connection.class,"earthplatform$rootsQueue",sourceMethod(kind)))return null;
        SourceScope scope=owner.source();
        if(scope==null||scope.connection.get()!=connection||scope.kind!=kind||scope.ticket!=null||queue!=expectedQueue||queue==null||queue.getClass()!=ConcurrentLinkedQueue.class){owner.fail(8);return null;}
        scope.ticket=owner.consumers.beforePublication(NativeOrdinaryRootFrontier.Kind.QUEUED_CONSUMER,connection,consumer,null);return scope.ticket;
    }
    public static void queuedReturned(Object opaque,int kind,boolean accepted,boolean threw){
        if(opaque==null||!site(Connection.class,"earthplatform$rootsQueue",sourceMethod(kind)))return;
        if(opaque instanceof NativeOrdinaryRootFrontier.Ticket ticket&&ticket.owner==retained.consumers)ticket.owner.queuedPublicationReturned(ticket,accepted,threw);else retained.fail(8);
    }
    public static Object beforeAccept(Connection connection,Object consumer,boolean queued){
        Owner owner=retained;if(owner==null||!owner.bound(connection)||!site(Connection.class,queued?"earthplatform$rootsAcceptQueued":"earthplatform$rootsAcceptInline",queued?"flushQueue":"runOnceConnected"))return null;
        NativeOrdinaryRootFrontier.Ticket ticket;
        if(queued)ticket=owner.consumers.find(connection,consumer);
        else {
            SourceScope scope=owner.source();if(scope==null||scope.connection.get()!=connection||scope.kind!=ONCE||scope.ticket!=null){owner.fail(9);return null;}
            ticket=owner.consumers.beforePublication(NativeOrdinaryRootFrontier.Kind.INLINE_CONSUMER,connection,consumer,null);scope.ticket=ticket;
        }
        owner.consumers.callbackEntered(ticket,connection,consumer);return ticket;
    }
    public static void acceptReturned(Object opaque,boolean queued,boolean threw){
        if(opaque==null||!site(Connection.class,queued?"earthplatform$rootsAcceptQueued":"earthplatform$rootsAcceptInline",queued?"flushQueue":"runOnceConnected"))return;
        if(opaque instanceof NativeOrdinaryRootFrontier.Ticket ticket&&ticket.owner==retained.consumers)ticket.owner.callbackReturned(ticket,threw);else retained.fail(9);
    }
    public static Object beforeConfigWrite(Connection connection,Channel channel,Channel expectedChannel,Object task,boolean outbound){
        Owner owner=retained;int kind=outbound?OUTBOUND:INBOUND;
        if(owner==null||!owner.bound(connection)||!site(Connection.class,"earthplatform$rootsConfigWrite",sourceMethod(kind)))return null;
        SourceScope scope=owner.source();
        if(scope==null||scope.connection.get()!=connection||scope.kind!=kind||scope.ticket!=null||channel!=expectedChannel||channel==null||channel.getClass()!=LocalChannel.class){owner.fail(10);return null;}
        scope.ticket=owner.configurations.beforePublication(outbound?NativeOrdinaryRootFrontier.Kind.OUTBOUND_CONFIGURATION:NativeOrdinaryRootFrontier.Kind.INBOUND_CONFIGURATION,connection,task,channel);return scope.ticket;
    }
    public static void configWriteReturned(Object opaque,boolean outbound,Object future,boolean threw){
        if(opaque==null||!site(Connection.class,"earthplatform$rootsConfigWrite",sourceMethod(outbound?OUTBOUND:INBOUND)))return;
        if(opaque instanceof NativeOrdinaryRootFrontier.Ticket ticket&&ticket.owner==retained.configurations)ticket.owner.configPublicationReturned(ticket,future,threw);else retained.fail(10);
    }
    private static Class<?> handlerClass(boolean outbound){return outbound?UnconfiguredPipelineHandler.Outbound.class:UnconfiguredPipelineHandler.Inbound.class;}
    public static HandlerScope handlerEntered(ChannelHandlerContext context,Object message,Object promise,boolean outbound){
        Owner owner=retained;if(owner==null||!site(handlerClass(outbound),"earthplatform$rootsConfigHandler","write"))return null;
        if(!(outbound?message instanceof UnconfiguredPipelineHandler.OutboundConfigurationTask:message instanceof UnconfiguredPipelineHandler.InboundConfigurationTask))return null;
        if(!NativeLocalInstalledPins.contextClass(context.getClass())){owner.fail(11);return null;}
        Channel channel=context.channel();if(channel.getClass()!=LocalChannel.class||NativeLocalCausalObservation.sideOf(channel)<0)return null;
        NativeOrdinaryRootFrontier.Ticket ticket=owner.configurations.findConfiguration(channel,message);
        if(ticket==null)return null;
        if(ticket.kind!=(outbound?NativeOrdinaryRootFrontier.Kind.OUTBOUND_CONFIGURATION:NativeOrdinaryRootFrontier.Kind.INBOUND_CONFIGURATION)){owner.fail(12);return null;}
        if(promise==null||promise.getClass()!=DefaultChannelPromise.class){owner.fail(12);return null;}
        HandlerScope previous=owner.handler();if(previous!=null&&previous.depth>=16){owner.fail(5);return null;}
        owner.configurations.handlerEntered(ticket,channel,message,promise);
        HandlerScope scope=new HandlerScope(owner,ticket,context,outbound,previous);
        synchronized(owner){owner.activeHandlers++;owner.handler(scope);}return scope;
    }
    public static void handlerReturned(HandlerScope scope,boolean threw){
        if(scope==null||!site(handlerClass(scope.outbound),"earthplatform$rootsConfigHandler","write"))return;
        if(scope.thread.get()!=Thread.currentThread()||scope.owner.handler()!=scope){scope.owner.fail(13);return;}
        scope.owner.handler(scope.previous);scope.ticket.owner.handlerReturned(scope.ticket,threw);
        synchronized(scope.owner){scope.owner.activeHandlers--;}
    }
    public static void beforeConfigTask(ChannelHandlerContext context,Object task,boolean outbound){
        Owner owner=retained;if(owner==null||!site(handlerClass(outbound),"earthplatform$rootsConfigTask","write"))return;
        HandlerScope scope=owner.handler();if(scope==null)return;
        if(scope.outbound!=outbound||scope.context.get()!=context||scope.ticket.payload.get()!=task){owner.fail(14);return;}
        scope.ticket.owner.callbackEntered(scope.ticket,scope.ticket.receiver.get(),task);
    }
    public static void configTaskReturned(boolean outbound,boolean threw){
        Owner owner=retained;if(owner==null||!site(handlerClass(outbound),"earthplatform$rootsConfigTask","write"))return;
        HandlerScope scope=owner.handler();if(scope!=null)scope.ticket.owner.callbackReturned(scope.ticket,threw);
    }
    public static void configReleaseReturned(Object message,boolean outbound,boolean released,boolean threw){
        Owner owner=retained;if(owner==null||!site(handlerClass(outbound),"earthplatform$rootsConfigRelease","write"))return;
        HandlerScope scope=owner.handler();if(scope!=null&&scope.ticket.payload.get()==message)scope.ticket.owner.releaseReturned(scope.ticket,message,released,threw);
    }
    /** Raw timed field value only: no synchronization, freshness/atomicity or notification-quiescence guarantee. */
    public static boolean promiseListenersObserved(Object promise,boolean outbound){
        Owner owner=retained;if(owner==null||!site(handlerClass(outbound),"earthplatform$rootsConfigSuccess","write"))return false;
        HandlerScope scope=owner.handler();if(scope==null)return false;
        try{return promise!=null&&promise.getClass()==DefaultChannelPromise.class&&scope.ticket.promise!=null&&scope.ticket.promise.get()==promise&&owner.listeners.get(promise)==null;}
        catch(ReflectiveOperationException failure){owner.fail(15);return false;}
    }
    public static void configPromiseReturned(Object promise,Object returned,boolean outbound,boolean listenersObserved,boolean threw){
        Owner owner=retained;if(owner==null||!site(handlerClass(outbound),"earthplatform$rootsConfigSuccess","write"))return;
        HandlerScope scope=owner.handler();if(scope!=null)scope.ticket.owner.promiseReturned(scope.ticket,promise,returned,listenersObserved,threw);
    }
    /** Same-package codec observer correlates an unchanged non-Packet object before inherited write.
     * The original config task/promise/handler/source owner remains authoritative for its milestones. */
    static Object configurationPassThrough(Channel channel,Object task){
        Owner owner=retained;
        if(owner==null||!(task instanceof UnconfiguredPipelineHandler.InboundConfigurationTask
                ||task instanceof UnconfiguredPipelineHandler.OutboundConfigurationTask))return null;
        return owner.configurations.findConfiguration(channel,task);
    }
    public static void observerFailed(){Owner owner=retained;if(owner!=null)owner.fail(16);}
    static synchronized void append(List<String> lines){
        Owner owner=retained;if(owner==null){lines.add("LOCAL_ROOTS absent seedFailure="+seedFailure);return;}
        synchronized(owner){
        lines.add("LOCAL_ROOTS chargedBytes="+BYTES+" sourceScopes="+owner.activeSources+" handlerScopes="+owner.activeHandlers+" localMilestonesReturned="+(owner.failure==0&&owner.activeSources==0&&owner.activeHandlers==0&&owner.consumers.observedInvocationsReturned()&&owner.configurations.observedInvocationsReturned())+" failure="+owner.failure+" consumerLineage="+owner.consumers.lineage()+" consumerPending="+owner.consumers.pending()+" consumerReturned="+owner.consumers.observedInvocationsReturned()+" consumerFailure="+owner.consumers.failure()+" configLineage="+owner.configurations.lineage()+" configPending="+owner.configurations.pending()+" configReturned="+owner.configurations.observedInvocationsReturned()+" configFailure="+owner.configurations.failure()+" promiseListeners=timed_observation_only handlerCallbackJoin=false");
        }
        synchronized(owner){if(owner.failedSite!=null)lines.add("LOCAL_ROOT_SITE_FAILURE site="+owner.failedSite+" helper="+owner.failedHelper+" original="+owner.failedOriginal);}
    }
}
