package io.earthplatform.minecraft.survival.runtime;

import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import net.neoforged.bus.EventBus;
import net.neoforged.bus.GeneratedEventListener;
import net.neoforged.bus.ListenerList;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventListener;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;

/** Diagnostic startup census, conditional on the separately reviewed finite joined-writer cut.
 * No live fallback: this method is callable only by the genuine pre-start marker. No native
 * factory/cache/getter/callback invocation and no installed Approval or lifetime authority.
 * Retains the original native Method, MethodHandle, Class, wrapper and owner identities.
 */
final class InstalledCallbackStartupCensus {
    static final int MAX_MAP=512, MAX_PHASE=64, MAX_PHASES=5, MAX_CALLBACK_REFS=2048;
    static final long CHARGE_BYTES=4L<<20;
    private static volatile Capture retained;
    private static volatile Capture published;
    private static volatile boolean repeated;
    private static volatile Class<?> reservationFailureClass;
    private InstalledCallbackStartupCensus() {}
    record Factory(Method method,MethodHandle constructor,Class<?> handlerClass) {}
    record Registry(Class<?> event,ListenerList list) {}
    record ListCopy(ListenerList list,Object parent,Object[][] priorities) {}
    record OwnerCopy(Object owner,Object nativeSynchronizedList) {}
    record SourceCheck(String kind,String key,String expected,String actual) {}
    // Arrays are probe-owned, bounded and never exposed outside the package. A failed/partial
    // capture remains strongly owned and its process-lifetime reservation cannot be recycled.
    static final class Capture {
        final NativeDistanceCpuBudget.Reservation reservation;
        final Factory[] factories=new Factory[MAX_MAP];
        final Registry[] registry=new Registry[MAX_MAP];
        final ListCopy[] lists=new ListCopy[MAX_MAP];
        final OwnerCopy[] owners=new OwnerCopy[MAX_MAP];
        final InstalledProbeMetadataBudget allocation=new InstalledProbeMetadataBudget(CHARGE_BYTES);
        final Object[] retainedNativeRoots=new Object[9];
        int factoryCount,registryCount,listCount,ownerCount,callbackReferences;
        boolean complete;
        String stage="SOURCE_CUT";
        Class<?> failureClass;
        SourceCheck sourceCheck;
        Capture(NativeDistanceCpuBudget.Reservation reservation){
            this.reservation=reservation;allocation.reserve(1L<<20); // Fixed roots + all bounded pin/hash/reflection scratch.
        }
    }
    static Capture observed(){return published;}
    static boolean repeated(){return repeated;}
    static Class<?> reservationFailureClass(){return reservationFailureClass;}
    /** Package-private and exact-origin authenticated. There is no public request to take a census. */
    static void captureFromMarker() {
        if(retained!=null){repeated=true;return;}
        // The global reservation is made before any copied graph, fixed array or stack record.
        NativeDistanceCpuBudget.Reservation prepaid;
        try { prepaid=NativeDistanceCpuBudget.global().reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA,CHARGE_BYTES); }
        catch(RuntimeException failure){reservationFailureClass=failure.getClass();return;}
        Capture capture=new Capture(prepaid);retained=capture;prepaid.claimRetainedOwner(capture);
        try {
            var frames=StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                    .walk(stream->stream.skip(1).limit(1).toList());
            if(frames.size()!=1 || frames.getFirst().getDeclaringClass()!=InstalledCallbackStartupMarker.class
                    || !frames.getFirst().getMethodName().equals("observe")
                    || InstalledCallbackStartupMarker.invalid())throw new IllegalStateException("Unauthenticated startup census cut");
            InstalledCallbackStartupSourcePins.requireSourceCut(capture);
            if(NeoForge.EVENT_BUS.getClass()!=EventBus.class || !(boolean)field(EventBus.class,NeoForge.EVENT_BUS,"shutdown"))
                throw new IllegalStateException("Global event bus already started");
            capture.stage="NATIVE_MAP_TYPES";
            Class<?> helper=type("net.neoforged.bus.LockHelper");
            Object listHolder=field(EventBus.class,NeoForge.EVENT_BUS,"listenerLists");
            Object factoryHolder=field(type("net.neoforged.bus.EventListenerFactory"),null,"eventListenerFactories");
            if(listHolder==null || listHolder.getClass()!=helper || factoryHolder==null || factoryHolder.getClass()!=helper)
                throw new IllegalStateException("Unexpected startup native holder type");
            Object listMap=field(helper,listHolder,"backingMap");
            Object factoryMap=field(helper,factoryHolder,"backingMap");
            Object ownerMap=field(EventBus.class,NeoForge.EVENT_BUS,"listeners");
            // Exact classes are checked before even size/iterator; no virtual foreign Map call.
            if(listMap==null || listMap.getClass()!=IdentityHashMap.class
                    || factoryMap==null || factoryMap.getClass()!=HashMap.class
                    || ownerMap==null || ownerMap.getClass()!=ConcurrentHashMap.class)
                throw new IllegalStateException("Unexpected startup native map type");
            var lists=(IdentityHashMap<?,?>)listMap;var factories=(HashMap<?,?>)factoryMap;
            var owners=(ConcurrentHashMap<?,?>)ownerMap;
            if(lists.size()>MAX_MAP || factories.size()>MAX_MAP || owners.size()>MAX_MAP)
                throw new IllegalStateException("Startup registry aggregate count bound");
            capture.retainedNativeRoots[0]=NeoForge.EVENT_BUS;capture.retainedNativeRoots[1]=listHolder;
            capture.retainedNativeRoots[2]=factoryHolder;capture.retainedNativeRoots[3]=listMap;
            capture.retainedNativeRoots[4]=factoryMap;capture.retainedNativeRoots[5]=ownerMap;
            capture.retainedNativeRoots[6]=field(helper,listHolder,"lock");
            capture.retainedNativeRoots[7]=field(helper,factoryHolder,"lock");
            capture.retainedNativeRoots[8]=InstalledCallbackStartupMarker.snapshot();
            capture.stage="FACTORY_COPY";
            for(var entry:factories.entrySet()){
                if(capture.factoryCount>=MAX_MAP || entry.getKey()==null || entry.getKey().getClass()!=Method.class
                        || !(entry.getValue() instanceof MethodHandle))throw new IllegalStateException("Unexpected startup factory entry");
                Method method=(Method)entry.getKey();MethodHandle constructor=(MethodHandle)entry.getValue();
                Class<?> generated=constructor.type().returnType();
                if(!generated.isHidden() || generated.getSuperclass()!=GeneratedEventListener.class
                        || generated.getClassLoader()!=GeneratedEventListener.class.getClassLoader()
                        || generated.getModule()!=GeneratedEventListener.class.getModule())throw new IllegalStateException("Foreign native factory return class");
                capture.allocation.reserve(256);
                capture.factories[capture.factoryCount++]=new Factory(method,constructor,generated);
            }
            capture.stage="LIST_REGISTRY_COPY";
            for(var entry:lists.entrySet()){
                if(capture.registryCount>=MAX_MAP || entry.getKey()==null || entry.getKey().getClass()!=Class.class
                        || entry.getValue()==null || entry.getValue().getClass()!=ListenerList.class)
                    throw new IllegalStateException("Unexpected startup list-registry entry");
                Class<?> event=(Class<?>)entry.getKey();if(!Event.class.isAssignableFrom(event))throw new IllegalStateException("Foreign startup event key");
                ListenerList list=(ListenerList)entry.getValue();capture.allocation.reserve(128);
                capture.registry[capture.registryCount++]=new Registry(event,list);
                boolean found=false;for(int i=0;i<capture.listCount;i++)if(capture.lists[i].list()==list){found=true;break;}
                if(!found)copyList(capture,list);
            }
            capture.stage="OWNER_MAP_COPY";
            Class<?> nativeOwnerList=Class.forName("java.util.Collections$SynchronizedRandomAccessList",false,null);
            for(var entry:owners.entrySet()){
                if(capture.ownerCount>=MAX_MAP || entry.getKey()==null || entry.getValue()==null
                        || entry.getValue().getClass()!=nativeOwnerList)throw new IllegalStateException("Unexpected startup owner entry");
                // Stock bus wraps ArrayList in Collections.synchronizedList. Its methods acquire
                // a Java monitor, and java.base internals have no supported raw-field opening here.
                // Retain identity only: do not call size/toArray or claim its membership observed.
                capture.allocation.reserve(128);
                capture.owners[capture.ownerCount++]=new OwnerCopy(entry.getKey(),entry.getValue());
            }
            // This sanity recheck is not a lock or a lifetime/exclusivity argument; the finite
            // source-cut proof is the only reason reading the backing containers was eligible.
            if(!(boolean)field(EventBus.class,NeoForge.EVENT_BUS,"shutdown")
                    || field(helper,listHolder,"backingMap")!=listMap || field(helper,factoryHolder,"backingMap")!=factoryMap
                    || field(helper,listHolder,"lock")!=capture.retainedNativeRoots[6]
                    || field(helper,factoryHolder,"lock")!=capture.retainedNativeRoots[7]
                    || lists.size()!=capture.registryCount || factories.size()!=capture.factoryCount || owners.size()!=capture.ownerCount)
                throw new IllegalStateException("Startup registry changed inside source-proved cut");
            capture.stage="STARTUP_CAPTURED_NO_LIFETIME_APPROVAL";capture.complete=true;
        }catch(ReflectiveOperationException | java.io.IOException | java.security.NoSuchAlgorithmException | RuntimeException failure){
            capture.failureClass=failure.getClass(); // No message, callback, cause graph or foreign toString.
        } finally {
            published=capture; // Release-publish every completed or failed copy; retained owns it throughout.
        }
    }
    private static void copyList(Capture capture,ListenerList list)throws ReflectiveOperationException {
        if(capture.listCount>=MAX_MAP)throw new IllegalStateException("Unique startup ListenerList bound");
        Object lock=field(ListenerList.class,list,"writeLock");
        if(lock==null || lock.getClass()!=Semaphore.class || !((Semaphore)lock).tryAcquire())
            throw new IllegalStateException("Startup listener writer has not joined");
        try {
            Object raw=field(ListenerList.class,list,"priorities");
            if(raw==null || raw.getClass()!=ArrayList.class || ((ArrayList<?>)raw).size()!=MAX_PHASES
                    || EventPriority.values().length!=MAX_PHASES)throw new IllegalStateException("Native priority shape changed");
            var groups=(ArrayList<?>)raw;int count=0;
            for(Object group:groups){
                if(group==null || group.getClass()!=ArrayList.class || ((ArrayList<?>)group).size()>MAX_PHASE)
                    throw new IllegalStateException("Native priority count/type differs");
                count=Math.addExact(count,((ArrayList<?>)group).size());
            }
            chargeReferences(capture,count);capture.allocation.reserve(512+16L*count);
            Object[][] copied=new Object[MAX_PHASES][];
            for(int i=0;i<MAX_PHASES;i++)copied[i]=((ArrayList<?>)groups.get(i)).toArray();
            capture.lists[capture.listCount++]=new ListCopy(list,field(ListenerList.class,list,"parent"),copied);
        }finally {((Semaphore)lock).release();}
    }
    private static void chargeReferences(Capture capture,int count){
        if(count<0 || count>MAX_CALLBACK_REFS-capture.callbackReferences)throw new IllegalStateException("Startup aggregate callback-reference bound");
        capture.callbackReferences+=count;
    }
    private static Class<?> type(String name)throws ClassNotFoundException {
        return Class.forName(name,false,InstalledCallbackStartupCensus.class.getClassLoader());
    }
    private static Object field(Class<?> owner,Object receiver,String name)throws ReflectiveOperationException {
        Field field=owner.getDeclaredField(name);if(!field.trySetAccessible())throw new IllegalStateException("Startup field inaccessible");return field.get(receiver);
    }
}
