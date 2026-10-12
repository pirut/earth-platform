package io.earthplatform.compat.dh;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeDeferredRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelUnloadEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiWorldUnloadEvent;
import com.seibel.distanthorizons.api.methods.events.interfaces.IDhApiEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.eventHandlers.IgnoredDimensionCsvHandler;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent negative controls using the pinned official singleton and real dispatcher.
 * These are plain-JVM API contracts, not evidence of NeoForge startup or a GPU draw. */
class InstalledDhStockPolicyIndependentReviewTest {
    private static final String CARRIER="fixture@earthplatform:cubic";
    static InstalledDhResolver.Runtime runtime(){return InstalledDhIndependentDispatcherTest.runtime();}
    static final class Ours extends DhApiBeforeRenderEvent {
        @Override public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event){}
    }
    static final class Deferred extends DhApiBeforeDeferredRenderEvent {
        @Override public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event){}
    }
    static InstalledDhEvents bridge(DhCarrierRenderGate gate,AtomicLong frame,List<Throwable> failures){
        return new InstalledDhEvents(runtime(),gate,()->new InstalledDhEvents.Frame(frame.get(),1,true),failures::add);
    }
    static DhApiBeforeRenderEvent ownedOpaque(){return DhApi.events.getAll(DhApiBeforeRenderEvent.class).stream()
            .filter(value->value.getClass().getName().equals(InstalledDhEvents.class.getName()+"$Opaque")).findFirst().orElseThrow();}
    static DhApiBeforeDeferredRenderEvent ownedDeferred(){return DhApi.events.getAll(DhApiBeforeDeferredRenderEvent.class).stream()
            .filter(value->value.getClass().getName().equals(InstalledDhEvents.class.getName()+"$Deferred")).findFirst().orElseThrow();}

    static List<IDhApiEvent> ownedBindings(){
        var result=new ArrayList<IDhApiEvent>();
        for(var type:List.of(DhApiBeforeRenderEvent.class,DhApiBeforeDeferredRenderEvent.class,DhApiLevelUnloadEvent.class,DhApiWorldUnloadEvent.class))
            DhApi.events.getAll(type).stream().filter(value->value!=null&&value.getClass().getName().startsWith(InstalledDhEvents.class.getName()+"$")).forEach(result::add);
        assertEquals(4,result.size());return result;
    }
    static void assertReleasedBindings(List<IDhApiEvent> previous){
        assertEquals(4,previous.size());
        for(var type:List.of(DhApiBeforeRenderEvent.class,DhApiBeforeDeferredRenderEvent.class,DhApiLevelUnloadEvent.class,DhApiWorldUnloadEvent.class)){
            var actual=DhApi.events.getAll(type);
            for(var owned:previous)assertFalse(actual.stream().anyMatch(value->value==owned),"Retained owned listener after cleanup: "+type.getName());
            if(type!=DhApiBeforeRenderEvent.class)
                assertEquals(java.util.Collections.singletonList(null),actual,"Pinned DH represents no registered listeners with exactly one null sentinel");
        }
    }

    @Test void exactStockAndOwnedPairOnlyIsAcceptedInEitherOrder()throws Exception{
        try(var fixture=new InstalledDhStockPolicyFixture()){
            fixture.ignored();var policy=new InstalledDhStockRenderPolicy(runtime());var ours=new Ours();var deferred=new Deferred();
            assertTrue(policy.supportedListeners(DhApiBeforeRenderEvent.class,List.of(fixture.stock,ours),ours));
            assertTrue(policy.supportedListeners(DhApiBeforeRenderEvent.class,List.of(ours,fixture.stock),ours));
            assertFalse(policy.supportedListeners(DhApiBeforeRenderEvent.class,List.of(ours),ours),"Stock listener absence is not an alternate supported setup");
            assertFalse(policy.supportedListeners(DhApiBeforeRenderEvent.class,List.of(fixture.stock,ours,new Ours()),ours));
            assertFalse(policy.supportedListeners(DhApiBeforeRenderEvent.class,List.of(fixture.stock,new Ours()),ours));
            assertFalse(policy.supportedListeners(DhApiBeforeRenderEvent.class,List.of(fixture.stock,fixture.stock),ours));
            assertFalse(policy.supportedListeners(DhApiBeforeRenderEvent.class,null,ours));
            assertFalse(policy.supportedListeners(DhApiBeforeRenderEvent.class,List.of(fixture.stock,ours),null));
            assertTrue(policy.supportedListeners(DhApiBeforeDeferredRenderEvent.class,List.of(deferred),deferred));
            assertFalse(policy.supportedListeners(DhApiBeforeDeferredRenderEvent.class,List.of(fixture.stock,deferred),deferred));
            assertFalse(policy.supportedListeners(DhApiWorldUnloadEvent.class,List.of(fixture.stock,ours),ours));
        }
    }
    @Test void anotherInstanceOfTheOfficialClassIsStillForeign()throws Exception{
        try(var fixture=new InstalledDhStockPolicyFixture()){
            var policy=new InstalledDhStockRenderPolicy(runtime());var ours=new Ours();var other=InstalledDhStockPolicyFixture.anotherStockInstance();
            assertSame(fixture.stock.getClass(),other.getClass());assertNotSame(fixture.stock,other);
            assertFalse(policy.supportedListeners(DhApiBeforeRenderEvent.class,List.of(other,ours),ours));
        }
    }
    @Test void replacedOrNullSingletonRevokesBothListenerAndPolicyChecks()throws Exception{
        try(var fixture=new InstalledDhStockPolicyFixture()){
            var policy=new InstalledDhStockRenderPolicy(runtime());var ours=new Ours();var deferred=new Deferred();
            for(var replacement:new IgnoredDimensionCsvHandler[]{InstalledDhStockPolicyFixture.anotherStockInstance(),null}){
                IgnoredDimensionCsvHandler.INSTANCE=replacement;
                assertThrows(IllegalStateException.class,()->policy.supportedListeners(DhApiBeforeRenderEvent.class,List.of(fixture.stock,ours),ours));
                assertThrows(IllegalStateException.class,()->policy.supportedListeners(DhApiBeforeDeferredRenderEvent.class,List.of(deferred),deferred));
                assertThrows(IllegalStateException.class,()->policy.carrierAllowed(InstalledDhStockPolicyFixture.event(new Object(),CARRIER).value));
            }
        }
    }
    @Test void sameNamedSameCodeSourceClassFromAnotherLoaderIsRejectedBeforeInitialization()throws Exception{
        String name=InstalledDhStockRenderPolicy.HANDLER;byte[] bytes;
        try(var input=DhApi.class.getClassLoader().getResourceAsStream(name.replace('.','/')+".class")){assertNotNull(input);bytes=input.readAllBytes();}
        class LookalikeLoader extends ClassLoader {
            LookalikeLoader(){super(DhApi.class.getClassLoader());}
            @Override protected Class<?> loadClass(String requested,boolean resolve)throws ClassNotFoundException{
                if(!requested.equals(name))return super.loadClass(requested,resolve);
                synchronized(getClassLoadingLock(requested)){
                    var loaded=findLoadedClass(requested);
                    if(loaded==null)loaded=defineClass(requested,bytes,0,bytes.length,IgnoredDimensionCsvHandler.class.getProtectionDomain());
                    if(resolve)resolveClass(loaded);return loaded;
                }
            }
        }
        var loader=new LookalikeLoader();var duplicate=Class.forName(name,false,loader);
        assertEquals(name,duplicate.getName());assertNotSame(IgnoredDimensionCsvHandler.class,duplicate);
        assertEquals(IgnoredDimensionCsvHandler.class.getProtectionDomain().getCodeSource(),duplicate.getProtectionDomain().getCodeSource());
        var mod=new InstalledDhResolver.InstalledMod("3.3.3",runtime().installation().path(),loader);
        var failure=assertThrows(IllegalStateException.class,()->new InstalledDhStockRenderPolicy(new InstalledDhResolver.Runtime(mod,DhApi.class)));
        assertTrue(failure.getMessage().contains("class identity"));
    }
    @Test void validClassCannotBorrowAnUnrelatedArtifactOrNamedModule()throws Exception{
        var actual=runtime();
        for(var installation:List.of(new InstalledDhResolver.InstalledMod("3.3.3",Path.of("not-official.jar"),actual.loader()),
                new InstalledDhResolver.InstalledMod("3.3.3",actual.installation().path(),actual.loader(),"distanthorizons",actual.installation().path().toUri())))
            assertThrows(IllegalStateException.class,()->new InstalledDhStockRenderPolicy(new InstalledDhResolver.Runtime(installation,DhApi.class)));
    }
    @Test void officialPolicyIsReadLiveWithUpstreamCaseAndPrefixSemanticsWithoutConfigWrites()throws Exception{
        try(var fixture=new InstalledDhStockPolicyFixture()){
            fixture.ignored();var policy=new InstalledDhStockRenderPolicy(runtime());var context=InstalledDhStockPolicyFixture.event(new Object(),CARRIER).value;
            var fog=Config.Client.Advanced.Graphics.Fog.enableVanillaFog;var fade=Config.Client.Advanced.Graphics.Quality.vanillaFadeMode;
            Object fogValue=fog.getApiValue(),fadeValue=fade.getApiValue();String fogUser=fog.getApiUser(),fadeUser=fade.getApiUser();
            assertTrue(policy.carrierAllowed(context));fixture.ignored("EARTHPLATFORM:CUBIC");assertFalse(policy.carrierAllowed(context));
            assertTrue(policy.carrierAllowed(InstalledDhStockPolicyFixture.event(new Object(),"earthplatform:cubic2").value));
            fixture.ignored(" earthplatform:cubic ");assertTrue(policy.carrierAllowed(context),"Do not invent trimming absent from the pinned DH method");
            fixture.ignored();assertTrue(policy.carrierAllowed(context));
            assertSame(fogValue,fog.getApiValue());assertSame(fadeValue,fade.getApiValue());assertEquals(fogUser,fog.getApiUser());assertEquals(fadeUser,fade.getApiUser());
            assertThrows(NullPointerException.class,()->policy.carrierAllowed(InstalledDhStockPolicyFixture.event(new Object(),null).value));
        }
    }
    @Test void realOfficialDispatcherWithDefaultStockPolicyAdmitsExactlyOneCompositionPerFrame()throws Exception{
        try(var fixture=new InstalledDhStockPolicyFixture()){
            fixture.ignored();var carrier=new Object();var resource=new InstalledDhIndependentAdversarialTest.Resource();var gate=new DhCarrierRenderGate();
            gate.acquire(runtime(),carrier,"world",1,1,resource);var failures=new ArrayList<Throwable>();var frame=new AtomicLong(1);List<IDhApiEvent> owned;
            try(var events=bridge(gate,frame,failures)){
                events.start();owned=ownedBindings();assertEquals(4,events.retainedBindings());var payload=InstalledDhStockPolicyFixture.event(carrier,CARRIER).value;
                var stockDirect=new DhApiCancelableEventParam<>(payload);assertDoesNotThrow(()->fixture.stock.beforeRender(stockDirect));assertFalse(stockDirect.isEventCanceled());
                assertTrue(DhApi.events.fireAllEvents(DhApiBeforeRenderEvent.class,payload));
                assertTrue(DhApi.events.fireAllEvents(DhApiBeforeRenderEvent.class,payload));
                assertTrue(DhApi.events.fireAllEvents(DhApiBeforeDeferredRenderEvent.class,payload));
                assertEquals(1,resource.draws);assertEquals(1,gate.counters().duplicateFrames());assertFalse(gate.counters().fenced());
                frame.incrementAndGet();assertTrue(DhApi.events.fireAllEvents(DhApiBeforeRenderEvent.class,payload));assertEquals(2,resource.draws);assertTrue(failures.isEmpty(),failures.toString());
            }
            assertEquals(1,resource.closes);assertEquals(List.of(fixture.stock),DhApi.events.getAll(DhApiBeforeRenderEvent.class));
            assertReleasedBindings(owned);
        }
    }
    @Test void ignoredCarrierNeverDrawsRegardlessOfStockDispatcherOrder()throws Exception{
        for(boolean stockLast:new boolean[]{false,true})try(var fixture=new InstalledDhStockPolicyFixture()){
            fixture.ignored("earthplatform:cubic");var carrier=new Object();var resource=new InstalledDhIndependentAdversarialTest.Resource();var gate=new DhCarrierRenderGate();
            gate.acquire(runtime(),carrier,"world",1,1,resource);var reads=new AtomicInteger();var failures=new ArrayList<Throwable>();
            try(var events=new InstalledDhEvents(runtime(),gate,()->{reads.incrementAndGet();return new InstalledDhEvents.Frame(1,1,true);},failures::add)){
                events.start();if(stockLast)fixture.moveStockToEnd();var payload=InstalledDhStockPolicyFixture.event(carrier,CARRIER).value;
                var stockDirect=new DhApiCancelableEventParam<>(payload);assertDoesNotThrow(()->fixture.stock.beforeRender(stockDirect));assertTrue(stockDirect.isEventCanceled());
                assertTrue(DhApi.events.fireAllEvents(DhApiBeforeRenderEvent.class,payload));assertTrue(DhApi.events.fireAllEvents(DhApiBeforeDeferredRenderEvent.class,payload));
                assertEquals(0,resource.draws);assertEquals(0,reads.get());assertTrue(gate.counters().fenced());assertEquals(2,failures.size());
                fixture.ignored();assertTrue(DhApi.events.fireAllEvents(DhApiBeforeRenderEvent.class,payload));assertEquals(0,resource.draws,"Policy repair does not revive a fenced lease");
            }
        }
    }
    @Test void changingIgnoredPolicyFencesOnTheNextOwnedOpaqueOrDeferredCallback()throws Exception{
        for(boolean deferredFirst:new boolean[]{false,true})try(var fixture=new InstalledDhStockPolicyFixture()){
            fixture.ignored();var carrier=new Object();var resource=new InstalledDhIndependentAdversarialTest.Resource();var gate=new DhCarrierRenderGate();
            var lease=gate.acquire(runtime(),carrier,"world",1,1,resource);var frame=new AtomicLong(1);var failures=new ArrayList<Throwable>();
            try(var events=bridge(gate,frame,failures)){
                events.start();var payload=InstalledDhStockPolicyFixture.event(carrier,CARRIER).value;
                assertTrue(DhApi.events.fireAllEvents(DhApiBeforeRenderEvent.class,payload));assertEquals(1,resource.draws);assertTrue(gate.isCurrent(lease));
                frame.incrementAndGet();fixture.ignored("earthplatform:cubic");
                assertTrue(deferredFirst?DhApi.events.fireAllEvents(DhApiBeforeDeferredRenderEvent.class,payload):DhApi.events.fireAllEvents(DhApiBeforeRenderEvent.class,payload));
                assertFalse(gate.isCurrent(lease));assertEquals(1,resource.draws);assertEquals(1,failures.size());
            }
        }
    }
    @Test void ordinaryLevelsRemainUntouchedEvenWithChangedSingletonAndThrowingFrameSource()throws Exception{
        try(var fixture=new InstalledDhStockPolicyFixture()){
            fixture.ignored();var carrier=new Object();var resource=new InstalledDhIndependentAdversarialTest.Resource();var gate=new DhCarrierRenderGate();
            gate.acquire(runtime(),carrier,"world",1,1,resource);var failures=new ArrayList<Throwable>();
            try(var events=new InstalledDhEvents(runtime(),gate,()->{throw new AssertionError("Ordinary levels must not inspect frame/config");},failures::add)){
                events.start();var opaque=ownedOpaque();var deferred=ownedDeferred();IgnoredDimensionCsvHandler.INSTANCE=null;
                var ordinary=InstalledDhStockPolicyFixture.event(new Object(),null);opaque.beforeRender(ordinary);deferred.beforeRender(ordinary);assertFalse(ordinary.isEventCanceled());
                var precancelled=InstalledDhStockPolicyFixture.event(new Object(),null);precancelled.cancelEvent();opaque.beforeRender(precancelled);deferred.beforeRender(precancelled);assertTrue(precancelled.isEventCanceled());
                assertEquals(0,resource.draws);assertFalse(gate.counters().fenced());assertTrue(failures.isEmpty());
            }
        }
    }
    @Test void failedGpuCleanupRetainsCallbacksAndStockUntilRetryThenOrdinaryDispatchStillWorks()throws Exception{
        try(var fixture=new InstalledDhStockPolicyFixture()){
            fixture.ignored();var carrier=new Object();var resource=new InstalledDhIndependentAdversarialTest.Resource();var gate=new DhCarrierRenderGate();
            gate.acquire(runtime(),carrier,"world",1,1,resource);var failures=new ArrayList<Throwable>();var events=bridge(gate,new AtomicLong(1),failures);events.start();var owned=ownedBindings();
            try{
                resource.failClose=true;assertThrows(IllegalStateException.class,events::close);assertEquals(4,events.retainedBindings());assertTrue(gate.counters().cleanupPending());
                var payload=InstalledDhStockPolicyFixture.event(carrier,CARRIER).value;
                assertTrue(DhApi.events.fireAllEvents(DhApiBeforeRenderEvent.class,payload));assertTrue(DhApi.events.fireAllEvents(DhApiBeforeDeferredRenderEvent.class,payload));assertEquals(0,resource.draws);
                resource.failClose=false;events.close();assertEquals(0,events.retainedBindings());assertEquals(2,resource.closes);assertFalse(gate.owns(carrier));
                assertEquals(List.of(fixture.stock),DhApi.events.getAll(DhApiBeforeRenderEvent.class));
                assertReleasedBindings(owned);
                assertFalse(DhApi.events.fireAllEvents(DhApiBeforeRenderEvent.class,InstalledDhStockPolicyFixture.event(new Object(),"minecraft:overworld").value));
                assertTrue(failures.isEmpty(),failures.toString());
            }finally{resource.failClose=false;events.close();}
        }
    }
    @Test void compiledPolicyHasNoUpstreamMutationsEventInvocationOrFallbackLoader()throws Exception{
        String resource=InstalledDhStockRenderPolicy.class.getName().replace('.','/')+".class";
        try(var input=getClass().getClassLoader().getResourceAsStream(resource)){
            assertNotNull(input);var node=new ClassNode();new ClassReader(input).accept(node,0);
            for(var method:node.methods)for(var instruction:method.instructions){
                if(instruction instanceof MethodInsnNode call){
                    assertFalse(call.owner.contains("URLClassLoader"),"No diagnostic loader fallback");
                    assertFalse(call.name.equals("beforeRender")||call.name.equals("setAccessible")||call.name.equals("trySetAccessible"));
                    assertFalse(call.owner.equals("java/lang/reflect/Field")&&call.name.startsWith("set"));
                    assertFalse(call.owner.startsWith("com/seibel/")&&List.of("set","setValue","setApiValue","bind","unbind","clear").contains(call.name));
                }
                if(instruction instanceof FieldInsnNode field&&(field.getOpcode()==Opcodes.PUTFIELD||field.getOpcode()==Opcodes.PUTSTATIC))
                    assertEquals(node.name,field.owner,"All field writes must remain within our policy instance");
            }
        }
    }
}
