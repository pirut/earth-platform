package io.earthplatform.minecraft.survival.interaction.host;

import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import java.lang.ref.WeakReference;
import java.util.List;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.ClientPayloadContext;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.handling.MainThreadPayloadHandler;

/** Weak/scalar baseline observer of the original MAIN payload source and private callback bodies.
 * It never replaces the native Runnable, future, executor, callback or exception.
 */
public final class NativeLocalPayloadObservation {
    public static final String FLAG="earthplatform.nativeLocalPayloadObservation";
    static final long BYTES=5_308_416L;
    private static NativeDistanceCpuBudget.Reservation charge;
    private static volatile Owner retained;
    private static volatile int seedFailure;
    private NativeLocalPayloadObservation(){}
    private static final class Owner {
        final NativeOrdinaryPayloadFrontier frontier=new NativeOrdinaryPayloadFrontier(8192);
        final WeakReference<?>[] endpoints=new WeakReference<?>[2],threads=new WeakReference<?>[4];
        final Scope[] sources=new Scope[4],callbacks=new Scope[4];
        final StackWalker walker=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
        int threadCount;volatile int failure;
        String failedSite,failedHelper,failedOriginal;
        synchronized void fail(int code){if(failure==0)failure=code;}
        synchronized int thread(boolean admit){
            Thread current=Thread.currentThread();for(int i=0;i<threadCount;i++)if(threads[i].get()==current)return i;
            if(!admit||threadCount==threads.length){fail(1);return -1;}
            threads[threadCount]=new WeakReference<>(current);return threadCount++;
        }
        synchronized Scope current(boolean callback){int i=thread(false);return i<0?null:(callback?callbacks:sources)[i];}
        synchronized void current(boolean callback,Scope scope){int i=thread(false);if(i>=0)(callback?callbacks:sources)[i]=scope;}
        synchronized boolean bound(ClientPayloadContext context){
            Object listener=context.listener(); // Exact final record accessor, after class admission.
            for(var reference:endpoints)if(reference!=null){Object c=reference.get();
                if(c!=null&&((Connection)c).getPacketListener()==listener)return true; // Pinned volatile getter only.
            }
            return false;
        }
    }
    public static final class Scope {
        private final Owner owner;
        private final NativeOrdinaryPayloadFrontier.Ticket ticket;
        private final Scope previous;
        private final WeakReference<Thread> thread=new WeakReference<>(Thread.currentThread());
        private final boolean callback;
        private final int depth;
        private Scope(Owner owner,NativeOrdinaryPayloadFrontier.Ticket ticket,Scope previous,boolean callback){
            this.owner=owner;this.ticket=ticket;this.previous=previous;this.callback=callback;depth=previous==null?1:previous.depth+1;
        }
    }
    /** Only called by the authenticated, already charged genuine-connect source before native factories. */
    static synchronized void seed(){
        if(charge!=null||retained!=null){if(retained!=null)retained.fail(2);return;}
        try{
            charge=NativeDistanceCpuBudget.global().reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA,BYTES);
            retained=new Owner();charge.claimRetainedOwner(retained);
            if(!NativeLocalPayloadPins.check())retained.fail(3);
        }catch(Throwable failure){seedFailure=1;if(retained!=null)retained.fail(4);}
    }
    /** Immutable weak lineage supplied only by the existing exact original constructor capture. */
    static synchronized void bind(int side,Connection connection){
        Owner owner=retained;if(owner==null)return;
        synchronized(owner){
            if(side<0||side>1||connection==null||connection.getClass()!=Connection.class||owner.endpoints[side]!=null){owner.fail(5);return;}
            owner.endpoints[side]=new WeakReference<>(connection);
        }
    }
    private static String frame(List<StackWalker.StackFrame> frames,int index){
        if(index>=frames.size())return "MISSING";var f=frames.get(index);String c=f.getClassName(),m=f.getMethodName();
        return c.substring(0,Math.min(120,c.length()))+"#"+m.substring(0,Math.min(120,m.length()));
    }
    private static boolean originalName(String name,String expected){
        if(name.equals(expected))return true;String prefix=expected+"$mixinextras$wrapped";
        if(!name.startsWith(prefix))return false;String rest=name.substring(prefix.length());if(rest.isEmpty())return true;
        if(rest.length()<2||rest.length()>6||rest.charAt(0)!='$')return false;
        for(int i=1;i<rest.length();i++)if(rest.charAt(i)<'0'||rest.charAt(i)>'9')return false;return true;
    }
    static boolean matchesFrames(List<StackWalker.StackFrame> frames,String helper,String original){
        return frames.size()==4&&frames.get(0).getDeclaringClass()==NativeLocalPayloadObservation.class
                &&frames.get(1).getDeclaringClass()==NativeLocalPayloadObservation.class
                &&frames.get(2).getDeclaringClass()==MainThreadPayloadHandler.class&&frames.get(2).getMethodName().endsWith(helper)
                &&frames.get(3).getDeclaringClass()==MainThreadPayloadHandler.class&&originalName(frames.get(3).getMethodName(),original);
    }
    private static boolean site(String helper,String original){
        Owner owner=retained;if(owner==null||!NativeLocalInstalledPins.qualified()||!NativeLocalPayloadPins.qualified()||owner.thread(true)<0)return false;
        return owner.walker.walk(stream->{var frames=stream.limit(4).toList();boolean ok=matchesFrames(frames,helper,original);
            if(!ok)synchronized(owner){if(owner.failedSite==null){owner.failedSite=helper;owner.failedHelper=frame(frames,2);owner.failedOriginal=frame(frames,3);}owner.fail(6);}return ok;});
    }
    public static Scope sourceEntered(Object wrapper,CustomPacketPayload payload,IPayloadContext context){
        Owner owner=retained;if(owner==null||!NativeLocalInstalledPins.qualified()||!NativeLocalPayloadPins.qualified())return null;
        // Global native handler also serves unrelated/server contexts. They grant no client observation.
        if(context==null||context.getClass()!=ClientPayloadContext.class||!owner.bound((ClientPayloadContext)context))return null;
        if(!site("earthplatform$payloadSource","handle"))return null;
        if(wrapper==null||wrapper.getClass()!=MainThreadPayloadHandler.class){owner.fail(7);return null;}
        Scope previous=owner.current(false);if(previous!=null&&previous.depth>=16){owner.fail(9);return null;}
        var ticket=owner.frontier.beforeSource(wrapper,payload,context);if(ticket==null)return null;
        Scope scope=new Scope(owner,ticket,previous,false);owner.current(false,scope);return scope;
    }
    public static Scope beforeEnqueue(IPayloadContext context,Runnable task){
        Owner owner=retained;if(owner==null||!NativeLocalInstalledPins.qualified()||!NativeLocalPayloadPins.qualified()||context==null||context.getClass()!=ClientPayloadContext.class)return null;
        if(!owner.bound((ClientPayloadContext)context))return null;
        if(!site("earthplatform$payloadEnqueue","handle"))return null;
        Scope scope=owner.current(false);if(scope==null){owner.fail(10);return null;}
        scope.owner.frontier.beforeEnqueue(scope.ticket,context,task);return scope;
    }
    public static void enqueueReturned(Scope scope,Object future,boolean failed){
        if(scope==null||!site("earthplatform$payloadEnqueue","handle"))return;
        if(scope.thread.get()!=Thread.currentThread()||scope.owner.current(false)!=scope){scope.owner.fail(11);return;}
        scope.owner.frontier.enqueueReturned(scope.ticket,future,failed);
    }
    public static void sourceReturned(Scope scope,boolean failed){
        if(scope==null||!site("earthplatform$payloadSource","handle"))return;
        if(scope.callback||scope.thread.get()!=Thread.currentThread()||scope.owner.current(false)!=scope){scope.owner.fail(11);return;}
        scope.owner.current(false,scope.previous);scope.owner.frontier.sourceReturned(scope.ticket,failed);
    }
    public static Scope callbackEntered(Object wrapper,CustomPacketPayload payload,IPayloadContext context){
        Owner owner=retained;if(owner==null||!NativeLocalInstalledPins.qualified()||!NativeLocalPayloadPins.qualified()||context==null||context.getClass()!=ClientPayloadContext.class)return null;
        if(!owner.frontier.hasTriple(wrapper,payload,context)&&!owner.bound((ClientPayloadContext)context))return null;
        if(!site("earthplatform$payloadCallback","lambda$handle$0"))return null;
        if(wrapper==null||wrapper.getClass()!=MainThreadPayloadHandler.class){owner.fail(7);return null;}
        Scope previous=owner.current(true);if(previous!=null&&previous.depth>=16){owner.fail(9);return null;}
        var ticket=owner.frontier.callbackEntered(wrapper,payload,context);if(ticket==null)return null;
        Scope scope=new Scope(owner,ticket,previous,true);owner.current(true,scope);return scope;
    }
    public static void callbackReturned(Scope scope,boolean failed){
        if(scope==null||!site("earthplatform$payloadCallback","lambda$handle$0"))return;
        if(!scope.callback||scope.thread.get()!=Thread.currentThread()||scope.owner.current(true)!=scope){scope.owner.fail(11);return;}
        scope.owner.current(true,scope.previous);scope.owner.frontier.callbackReturned(scope.ticket,failed);
    }
    public static void observerFailed(){Owner owner=retained;if(owner!=null)owner.fail(12);}
    static void append(List<String> lines){
        Owner owner=retained;
        if(owner==null){lines.add("LOCAL_PAYLOAD absent seedFailure="+seedFailure);return;}
        lines.add("LOCAL_PAYLOAD lineage="+owner.frontier.lineage()+" pending="+owner.frontier.pending()
                +" originalBodiesReturned="+(owner.failure==0&&owner.frontier.originalBodiesReturned())+" failure="+owner.failure
                +" callbackFailure="+owner.frontier.failure()+" chargedBytes="+BYTES+" executorAliasJoin=false handlerAdmission=false serverPayloadCoverage=false splitterCallbackCoverage=false");
        lines.add(NativeLocalPayloadPins.failureSummary());
        synchronized(owner){if(owner.failedSite!=null)lines.add("LOCAL_PAYLOAD_SITE_FAILURE site="+owner.failedSite+" helper="+owner.failedHelper+" original="+owner.failedOriginal);}
    }
}
