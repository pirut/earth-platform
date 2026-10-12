package io.earthplatform.compat.dh;

import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodQuadBuilder;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.api.enums.config.EDhApiGrassSideRendering;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiDebugRendering;
import io.earthplatform.minecraft.lod.DhTerrainGeometry;
import io.earthplatform.minecraft.lod.SurvivalDhTerrainData.PageFace;
import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Real installed helper/checkout/workspace. Injected failures prove ownership only, not real upstream faults. */
@Timeout(45)
class InstalledSurvivalDhWorkspaceTest {
    private static final List<InstalledDhTerrainRuntime> RETAINED=new ArrayList<>();
    private static InstalledDhTerrainRuntime isolated(InstalledDhTerrainRuntime.HelperCalls calls)throws Exception {
        assertEquals("installed-dh",System.getProperty("earthplatform.test.partition"));
        Class<?> api=Class.forName("com.seibel.distanthorizons.api.DhApi");
        var mod=new InstalledDhResolver.InstalledMod("3.3.3",Path.of(System.getProperty("earthplatform.installedDhTestJar")),api.getClassLoader());
        var result=new InstalledDhTerrainRuntime(new InstalledDhResolver.Runtime(mod,api),calls);RETAINED.add(result);return result;
    }
    private static Object field(Object owner,String name)throws Exception{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private static List<PageFace> faces(){return List.of(new PageFace(new DhTerrainGeometry.PageFace(DhTerrainGeometry.Direction.DOWN,0,1,7,1,7,3,0xff898989),2));}
    private static NativeDistanceCpuBudget budget(){return new NativeDistanceCpuBudget(NativeDistanceCpuBudget.MAX_BYTES);}
    static final class Calls implements InstalledDhTerrainRuntime.HelperCalls {
        Object builder,checkout;Thread thread;int factoryCalls,checkoutCalls,closes;boolean failAcquire,failStart,failClose;
        public Object borrow(Method method,Object target,Object... args)throws ReflectiveOperationException{
            Object result=method.invoke(target,args);factoryCalls++;if(builder==null)builder=result;else assertSame(builder,result);
            if(thread==null)thread=Thread.currentThread();else assertSame(thread,Thread.currentThread());return result;
        }
        public Object createCheckout(Constructor<?> constructor,Object pool)throws ReflectiveOperationException{
            checkoutCalls++;checkout=constructor.newInstance(pool);if(failAcquire)throw new InvocationTargetException(new IllegalStateException("after fresh constructor"));return checkout;
        }
        public void startCheckout(Method method,Object checkout)throws ReflectiveOperationException{
            if(failStart)throw new IllegalAccessException("checked failure after fresh constructor");method.invoke(checkout);
        }
        public void close(InstalledDhTerrainRuntime.Resource resource,Object object)throws Exception{
            assertEquals(InstalledDhTerrainRuntime.Resource.BUILDER,resource,"Held checkout must never be requeued");closes++;((AutoCloseable)object).close();if(failClose)throw new IllegalStateException("after builder close");
        }
    }
    static final class Modes implements AutoCloseable {
        final EDhApiGrassSideRendering grass=Config.Client.Advanced.Graphics.Quality.grassSideRendering.getTrueValue();
        final EDhApiDebugRendering debug=Config.Client.Advanced.Debugging.debugRenderingColors.getTrueValue();
        Modes(){Config.Client.Advanced.Graphics.Quality.grassSideRendering.setWithoutFiringEvents(EDhApiGrassSideRendering.AS_GRASS);Config.Client.Advanced.Debugging.debugRenderingColors.setWithoutFiringEvents(EDhApiDebugRendering.OFF);}
        public void close(){Config.Client.Advanced.Graphics.Quality.grassSideRendering.setWithoutFiringEvents(grass);Config.Client.Advanced.Debugging.debugRenderingColors.setWithoutFiringEvents(debug);}
    }
    @Test void actualFreshWorkspaceReusesExactTwoMibBufferAndNeverReturnsItToPool()throws Exception{
        try(var modes=new Modes()){
            var calls=new Calls();var runtime=isolated(calls);var cpu=budget();var workspace=runtime.managedSurvivalWorkspace(cpu);
            long charged=cpu.evidence().bytes();assertEquals(InstalledDhTerrainRuntime.SURVIVAL_WORKSPACE_BYTES+InstalledDhTerrainRuntime.SURVIVAL_WORKSPACE_METADATA_BYTES,charged);
            assertSame(workspace,runtime.managedSurvivalWorkspace(cpu));assertThrows(IllegalStateException.class,()->runtime.managedSurvivalWorkspace(budget()));
            var transientCharge=cpu.reserve(NativeDistanceCpuBudget.Pool.DH_RESULTS,1L<<20);
            ByteBuffer exactBuffer=null;byte[] expected=null;
            for(int i=0;i<128;i++){
                var call=workspace.compile(faces(),null,()->{});assertTrue(call.operationCompleted());byte[] bytes=call.compilation().bytes();
                if(expected==null)expected=bytes.clone();else assertArrayEquals(expected,bytes);
                var wrappers=(List<?>)field(calls.checkout,"byteBufferWrapperList");assertEquals(1,wrappers.size());
                var buffer=(ByteBuffer)field(wrappers.getFirst(),"buffer");assertEquals(2<<20,buffer.capacity());
                if(exactBuffer==null)exactBuffer=buffer;else assertSame(exactBuffer,buffer);
                assertEquals(charged+(1L<<20),cpu.evidence().bytes());assertTrue(workspace.operationReady());assertFalse(runtime.physicalDrain());
            }
            assertNotSame(Thread.currentThread(),calls.thread);assertEquals(1,calls.checkoutCalls);assertEquals(128,calls.closes);
            assertThrows(IllegalStateException.class,()->runtime.buildSurvivalRectangles(faces()));
            assertThrows(IllegalStateException.class,()->runtime.buildRectangles(List.of(faces().getFirst().geometry())));
            assertThrows(IllegalStateException.class,()->runtime.build(List.of(new InstalledDhTerrainRuntime.Face("UP",0,1,0,-1))));
            // Local test arrays are no longer retained before releasing their transient allowance.
            expected=null;transientCharge.releaseAfterPhysicalDrain();assertEquals(charged,cpu.evidence().bytes());
        }
    }
    @Test void cancellationReturnsAnExactCompletedCallAndKeepsWorkspaceCharged()throws Exception{
        try(var modes=new Modes()){
            var calls=new Calls();var runtime=isolated(calls);var cpu=budget();var workspace=runtime.managedSurvivalWorkspace(cpu);
            var allowance=cpu.reserve(NativeDistanceCpuBudget.Pool.DH_RESULTS,1L<<20);var signal=new CancellationException("cancel");
            var call=workspace.compile(faces(),null,()->{throw signal;});assertTrue(call.operationCompleted());assertSame(signal,assertThrows(CancellationException.class,call::compilation));
            assertTrue(workspace.compile(faces(),null,()->{}).operationCompleted());assertFalse(runtime.poisoned());assertFalse(runtime.physicalDrain());
            allowance.releaseAfterPhysicalDrain();assertEquals(InstalledDhTerrainRuntime.SURVIVAL_WORKSPACE_BYTES,cpu.evidence().pools().get(NativeDistanceCpuBudget.Pool.DH_RESULTS).longValue());
        }
    }
    @Test void capturedActualPackingModesCanNeverMixAcrossExpectedGenerationSettings()throws Exception{
        try(var modes=new Modes()){
            var runtime=isolated(new Calls());var cpu=budget();var workspace=runtime.managedSurvivalWorkspace(cpu);var allowance=cpu.reserve(NativeDistanceCpuBudget.Pool.DH_RESULTS,1L<<20);
            var first=workspace.compile(faces(),null,()->{});var settings=first.compilation().settings();assertEquals("AS_GRASS",settings.grassSideMode());
            Config.Client.Advanced.Graphics.Quality.grassSideRendering.setWithoutFiringEvents(EDhApiGrassSideRendering.AS_DIRT);
            var expected=new InstalledDhTerrainRuntime.ObservedSurvivalSettings(settings.grassSideMode(),settings.debugMode());
            var changed=workspace.compile(faces(),expected,()->{});assertTrue(changed.operationCompleted());assertThrows(IllegalStateException.class,changed::compilation);
            first=null;changed=null;allowance.releaseAfterPhysicalDrain();
        }
    }
    @Test void uncertainConstructorCheckedInitializationAndClosePoisonWithoutRetryOrChargeRelease()throws Exception{
        try(var modes=new Modes()){
            for(int stage=0;stage<3;stage++){
                var calls=new Calls();calls.failAcquire=stage==0;calls.failStart=stage==1;calls.failClose=stage==2;
                var runtime=isolated(calls);var cpu=budget();var workspace=runtime.managedSurvivalWorkspace(cpu);cpu.reserve(NativeDistanceCpuBudget.Pool.DH_RESULTS,1L<<20);
                long before=cpu.evidence().bytes();var outcome=workspace.compile(faces(),null,()->{});assertFalse(outcome.operationCompleted());assertThrows(IllegalStateException.class,outcome::compilation);
                assertTrue(runtime.poisoned());assertFalse(workspace.operationReady());assertFalse(runtime.physicalDrain());int checkouts=calls.checkoutCalls,closes=calls.closes;
                var repeated=workspace.compile(faces(),null,()->{});assertFalse(repeated.operationCompleted());assertThrows(IllegalStateException.class,repeated::compilation);
                assertEquals(checkouts,calls.checkoutCalls);assertEquals(closes,calls.closes);assertEquals(before,cpu.evidence().bytes());
            }
        }
    }
    @Test void workspaceAdmissionRefusesBeforeCreatingAnyHelperAndKeepsLegacyCallsAvailable()throws Exception{
        try(var modes=new Modes()){
            var calls=new Calls();var runtime=isolated(calls);var cpu=new NativeDistanceCpuBudget(1L<<20);
            assertThrows(IllegalStateException.class,()->runtime.managedSurvivalWorkspace(cpu));assertEquals(0,cpu.evidence().bytes());assertEquals(0,calls.factoryCalls);assertEquals(0,calls.checkoutCalls);assertTrue(runtime.physicalDrain());
        }
    }
}
