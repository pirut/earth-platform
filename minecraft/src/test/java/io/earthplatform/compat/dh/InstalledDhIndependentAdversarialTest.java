package io.earthplatform.compat.dh;

import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.*;
import com.seibel.distanthorizons.api.methods.events.interfaces.IDhApiEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.*;
import java.io.RandomAccessFile;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Independent seam/real-event-object tests. They do not establish a live NeoForge or GL lifecycle. */
class InstalledDhIndependentAdversarialTest {
    static final ClassLoader LOADER=InstalledDhIndependentAdversarialTest.class.getClassLoader();
    static InstalledDhResolver.InstalledMod mod(){return new InstalledDhResolver.InstalledMod("3.3.3",Path.of("installed-test.jar"),LOADER);}
    static InstalledDhResolver.Runtime runtime(){return new InstalledDhResolver.Runtime(mod(),InstalledDhIndependentAdversarialTest.class);}
    static InstalledDhResolver.Capabilities caps(Class<?> api){return new InstalledDhResolver.Capabilities(api,"3.3.3","7.2.0",true,"OPEN_GL","OPEN_GL","DEFAULT",true);}
    static final class Resource implements DhCarrierRenderGate.Resources {
        int draws,closes;boolean failClose;Runnable draw=()->{},closing=()->{};
        public void draw(Object context){draws++;draw.run();}
        public void close(){closes++;closing.run();if(failClose)throw new IllegalStateException("retain GPU owner");}
    }
    static final class ForeignOpaque extends DhApiBeforeRenderEvent {
        @Override public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event){}
    }
    static final class Registry implements InstalledDhEvents.Registry {
        final Map<Class<? extends IDhApiEvent>,List<IDhApiEvent>> listeners=new LinkedHashMap<>();
        final List<Class<? extends IDhApiEvent>> unbound=new ArrayList<>();
        int bindCalls,failBind=-1;boolean failBeforeMutation;Class<?> failUnbind;
        public void bind(Class<? extends IDhApiEvent> type,IDhApiEvent value){
            boolean fail=++bindCalls==failBind;
            if(fail&&failBeforeMutation)throw new IllegalStateException("before binding");
            listeners.computeIfAbsent(type,k->new ArrayList<>()).add(value);
            if(fail)throw new IllegalStateException("after binding");
        }
        public boolean unbind(Class<? extends IDhApiEvent> type,Class<? extends IDhApiEvent> value){
            unbound.add(type);if(type==failUnbind)throw new IllegalStateException("unbinding failed");
            return listeners.getOrDefault(type,new ArrayList<>()).removeIf(l->l.getClass()==value);
        }
        public boolean onlyListener(Class<? extends IDhApiEvent> type,IDhApiEvent exact){
            var registered=listeners.getOrDefault(type,List.of());return registered.size()==1&&registered.getFirst()==exact;
        }
        IDhApiEvent owned(Class<? extends IDhApiEvent> type){return listeners.get(type).stream().filter(l->!(l instanceof ForeignOpaque)).findFirst().orElseThrow();}
        int size(){return listeners.values().stream().mapToInt(List::size).sum();}
    }
    static DhApiCancelableEventParam<DhApiRenderParam> event(Object level){
        var param=new DhApiRenderParam();
        param.clientLevelWrapper=(IDhApiLevelWrapper)Proxy.newProxyInstance(LOADER,new Class<?>[]{IDhApiLevelWrapper.class},(p,m,a)->{
            if(m.getName().equals("getWrappedMcObject"))return level;
            throw new UnsupportedOperationException(m.getName());
        });return new DhApiCancelableEventParam<>(param);
    }
    static InstalledDhEvents events(DhCarrierRenderGate gate,Registry registry,InstalledDhEvents.FrameSource frames){return new InstalledDhEvents(gate,frames,t->{},registry);}

    @Test void disappearanceNeverReusesCachedRuntimeAndReappearanceMustHaveSameIdentity(){
        var installed=new AtomicReference<>(Optional.of(mod()));var probes=new AtomicInteger();
        var resolver=new InstalledDhResolver(installed::get,l->{probes.incrementAndGet();return caps(getClass());},p->{});
        assertEquals(InstalledDhResolver.Status.READY,resolver.resolve().status());
        installed.set(Optional.empty());assertEquals(InstalledDhResolver.Status.ABSENT,resolver.resolve().status());assertEquals(1,probes.get());
        installed.set(Optional.of(new InstalledDhResolver.InstalledMod("3.3.3",Path.of("replacement.jar"),LOADER)));
        assertEquals(InstalledDhResolver.Status.UNSUPPORTED,resolver.resolve().status());assertEquals(1,probes.get());
    }
    @Test void changedApiClassPermanentlyRejectsEvenWithIdenticalReportedVersions(){
        var identity=new AtomicReference<Class<?>>(getClass());var probes=new AtomicInteger();
        var resolver=new InstalledDhResolver(()->Optional.of(mod()),l->{probes.incrementAndGet();return caps(identity.get());},p->{});
        assertEquals(InstalledDhResolver.Status.READY,resolver.resolve().status());identity.set(String.class);
        var rejected=resolver.resolve();assertEquals(InstalledDhResolver.Status.UNSUPPORTED,rejected.status());
        identity.set(getClass());assertSame(rejected,resolver.resolve());assertEquals(2,probes.get());
    }
    @Test void equalSizeWrongBytesFailActualArtifactHash(@TempDir Path temp)throws Exception{
        Path file=temp.resolve("not-upstream.jar");try(var out=new RandomAccessFile(file.toFile(),"rw")){out.setLength(InstalledDhResolver.ARTIFACT_BYTES);}
        var failure=assertThrows(java.io.IOException.class,()->InstalledDhResolver.verifyArtifact(file));assertTrue(failure.getMessage().contains("hash"));
    }
    @Test void equalLookingCarrierNeverMatchesObjectIdentity(){
        var gate=new DhCarrierRenderGate();var resources=new Resource();
        String owned=new String("earthplatform:cubic");String sameName=new String("earthplatform:cubic");
        gate.acquire(runtime(),owned,"world",1,1,resources);
        try{var cancels=new AtomicInteger();
            assertEquals(DhCarrierRenderGate.Result.PASS_THROUGH,gate.opaque(sameName,1,1,false,true,cancels::incrementAndGet,null));
            assertEquals(DhCarrierRenderGate.Result.PASS_THROUGH,gate.deferred(sameName,cancels::incrementAndGet));
            assertEquals(0,cancels.get());assertEquals(0,resources.draws);
        }finally{gate.close();}
    }
    @Test void noHistoricalLeaseRevivesWhenSameLevelObjectAndResourceNumbersAreReused(){
        var gate=new DhCarrierRenderGate();var carrier=new Object();var first=new Resource();
        var old=gate.acquire(runtime(),carrier,"world",1,1,first);gate.invalidate("reload");gate.close();
        var replacement=new Resource();var current=gate.acquire(runtime(),carrier,"world",1,1,replacement);
        try{assertFalse(gate.isCurrent(old));assertTrue(gate.isCurrent(current));
            assertEquals(DhCarrierRenderGate.Result.SUBMITTED,gate.opaque(carrier,1,1,false,true,()->{},null));
            assertEquals(0,first.draws);assertEquals(1,replacement.draws);
        }finally{gate.close();}
    }
    @Test void frameDeduplicationSpansDeferredCallbacksAndLargeActualFrameGaps(){
        var gate=new DhCarrierRenderGate();var carrier=new Object();var resources=new Resource();gate.acquire(runtime(),carrier,"world",1,1,resources);
        try{for(long frame:new long[]{0,1,400,Long.MAX_VALUE}){
            assertEquals(DhCarrierRenderGate.Result.CANCELLED,gate.deferred(carrier,()->{}));
            assertEquals(DhCarrierRenderGate.Result.SUBMITTED,gate.opaque(carrier,frame,1,false,true,()->{},null));
            for(int i=0;i<8;i++){gate.deferred(carrier,()->{});assertEquals(DhCarrierRenderGate.Result.CANCELLED,gate.opaque(carrier,frame,1,false,true,()->{},null));}
        }assertEquals(4,resources.draws);assertEquals(32,gate.counters().duplicateFrames());
        }finally{gate.close();}
    }
    @Test void failedCancellationRevokesAuthorityWithoutDrawing(){
        var gate=new DhCarrierRenderGate();var carrier=new Object();var resources=new Resource();var lease=gate.acquire(runtime(),carrier,"world",1,1,resources);
        try{assertThrows(IllegalStateException.class,()->gate.opaque(carrier,1,1,false,true,()->{throw new IllegalStateException("cancel unavailable");},null));
            assertFalse(gate.isCurrent(lease));assertEquals(0,resources.draws);
            assertEquals(DhCarrierRenderGate.Result.CANCELLED,gate.deferred(carrier,()->{}));
        }finally{gate.close();}
    }
    @Test void cleanupRevokesBeforeCallingOwnedResourcesAndRetainsFailedOwner(){
        var gate=new DhCarrierRenderGate();var carrier=new Object();var resources=new Resource();var lease=gate.acquire(runtime(),carrier,"world",1,1,resources);
        resources.closing=()->{assertFalse(gate.isCurrent(lease));assertTrue(gate.owns(carrier));
            assertEquals(DhCarrierRenderGate.Result.CANCELLED,gate.opaque(carrier,2,1,false,true,()->{},null));
            assertEquals(DhCarrierRenderGate.Result.CANCELLED,gate.deferred(carrier,()->{}));
            assertThrows(IllegalStateException.class,gate::close);};
        resources.failClose=true;assertThrows(IllegalStateException.class,gate::close);assertEquals(0,resources.draws);
        assertTrue(gate.counters().cleanupPending());resources.failClose=false;gate.close();assertEquals(2,resources.closes);assertFalse(gate.owns(carrier));
    }
    @Test void foreignOpaqueDoesNotReadFrameOrConfigAndPreservesPriorCancellation(){
        var gate=new DhCarrierRenderGate();var carrier=new Object();gate.acquire(runtime(),carrier,"world",1,1,new Resource());
        var registry=new Registry();var reads=new AtomicInteger();var events=events(gate,registry,()->{reads.incrementAndGet();throw new IllegalStateException("must not inspect foreign world");});events.start();
        try{var opaque=(DhApiBeforeRenderEvent)registry.owned(DhApiBeforeRenderEvent.class);
            var ordinary=event(new Object());opaque.beforeRender(ordinary);assertFalse(ordinary.isEventCanceled());
            var cancelled=event(new Object());cancelled.cancelEvent();opaque.beforeRender(cancelled);assertTrue(cancelled.isEventCanceled());
            assertEquals(0,reads.get());assertFalse(gate.counters().fenced());
        }finally{events.close();}
    }
    @Test void failureBeforeFrameIsAvailableStillCancelsBothOwnedPasses(){
        var gate=new DhCarrierRenderGate();var carrier=new Object();var resources=new Resource();gate.acquire(runtime(),carrier,"world",1,1,resources);
        var registry=new Registry();var errors=new AtomicInteger();var events=new InstalledDhEvents(gate,()->{throw new IllegalStateException("frame source failed");},t->{errors.incrementAndGet();throw new IllegalStateException("diagnostic failed too");},registry);events.start();
        try{var e=event(carrier);assertDoesNotThrow(()->((DhApiBeforeRenderEvent)registry.owned(DhApiBeforeRenderEvent.class)).beforeRender(e));
            assertTrue(e.isEventCanceled());assertTrue(gate.counters().fenced());assertEquals(1,errors.get());assertEquals(0,resources.draws);
            var deferred=event(carrier);((DhApiBeforeDeferredRenderEvent)registry.owned(DhApiBeforeDeferredRenderEvent.class)).beforeRender(deferred);assertTrue(deferred.isEventCanceled());
        }finally{events.close();}
    }
    @Test void precancelledRealEventNeverBecomesDrawPermit(){
        var gate=new DhCarrierRenderGate();var carrier=new Object();var resources=new Resource();gate.acquire(runtime(),carrier,"world",1,1,resources);
        var registry=new Registry();var events=events(gate,registry,()->new InstalledDhEvents.Frame(1,1,true));events.start();
        try{var e=event(carrier);e.cancelEvent();((DhApiBeforeRenderEvent)registry.owned(DhApiBeforeRenderEvent.class)).beforeRender(e);
            assertEquals(0,resources.draws);assertTrue(gate.counters().fenced());
        }finally{events.close();}
    }
    @Test void partialUnbindKeepsOnlyFailedSubscriptionAndNeverRemovesForeignListener(){
        var registry=new Registry();var foreign=new ForeignOpaque();
        var events=events(new DhCarrierRenderGate(),registry,()->new InstalledDhEvents.Frame(1,1,true));events.start();registry.bind(DhApiBeforeRenderEvent.class,foreign);registry.failUnbind=DhApiBeforeDeferredRenderEvent.class;
        try{assertThrows(IllegalStateException.class,events::close);assertEquals(1,events.retainedBindings());assertEquals(2,registry.size());assertTrue(registry.listeners.get(DhApiBeforeRenderEvent.class).contains(foreign));
            int firstAttempts=registry.unbound.size();registry.failUnbind=null;events.close();assertEquals(firstAttempts+1,registry.unbound.size());assertEquals(1,registry.size());
        }finally{registry.failUnbind=null;events.close();}
    }
    @Test void everyPartialRegistrationPositionRollsBackBeforeAndAfterMutation(){
        for(boolean before:new boolean[]{true,false})for(int position=1;position<=4;position++){
            var registry=new Registry();registry.failBind=position;registry.failBeforeMutation=before;
            var events=events(new DhCarrierRenderGate(),registry,()->new InstalledDhEvents.Frame(1,1,true));
            assertThrows(IllegalStateException.class,events::start);assertEquals(0,events.retainedBindings());assertEquals(0,registry.size());events.close();
        }
    }
}
