package io.earthplatform.compat.dh;

import com.seibel.distanthorizons.api.enums.config.EDhApiGrassSideRendering;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiDebugRendering;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodQuadBuilder;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import io.earthplatform.geography.survival.SurvivalMaterials;
import io.earthplatform.minecraft.lod.DhTerrainGeometry;
import io.earthplatform.minecraft.lod.SurvivalDhTerrainData.PageFace;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real pinned helpers provide all geometry/settings evidence. Throwing close/acquisition/copy
 * cases are explicitly injected MODEL faults, not evidence that upstream actually threw.
 * Poisoned test adapters and their exact retained references are never reset or reused.
 */
@Timeout(45)
class InstalledSurvivalDhOwnershipTest {
    private static final List<InstalledDhTerrainRuntime> RETAINED_POISONED=new ArrayList<>();
    private static InstalledDhResolver.Runtime installation()throws Exception {
        assertEquals("installed-dh",System.getProperty("earthplatform.test.partition"));
        String path=System.getProperty("earthplatform.installedDhTestJar");assertNotNull(path);
        Class<?> api=Class.forName("com.seibel.distanthorizons.api.DhApi");
        return new InstalledDhResolver.Runtime(new InstalledDhResolver.InstalledMod("3.3.3",Path.of(path),api.getClassLoader()),api);
    }
    private static InstalledDhTerrainRuntime isolated(InstalledDhTerrainRuntime.HelperCalls calls)throws Exception {
        return new InstalledDhTerrainRuntime(installation(),calls);
    }
    @FunctionalInterface private interface CheckedRun {void run()throws Exception;}
    private static void freshThread(CheckedRun work)throws Exception {
        var task=new FutureTask<Void>(()->{work.run();return null;});
        var thread=new Thread(task,"installed-dh-ownership-proof");thread.setDaemon(true);thread.start();
        try{task.get(30,TimeUnit.SECONDS);}finally{thread.join(5000);assertFalse(thread.isAlive());}
    }
    private static Object field(Object value,String name)throws Exception {
        Field field=value.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(value);
    }
    private static Object owner(InstalledDhTerrainRuntime runtime)throws Exception {return field(runtime,"buildOwner");}
    private static List<PageFace> faces() {
        return List.of(new PageFace(new DhTerrainGeometry.PageFace(DhTerrainGeometry.Direction.NORTH,
                -1,11,100,17,7,3,DhTerrainGeometry.stratifiedFaceArgb(DhTerrainGeometry.Direction.NORTH,0xff72974a)),13));
    }
    private static final class Modes implements AutoCloseable {
        final EDhApiGrassSideRendering grass=Config.Client.Advanced.Graphics.Quality.grassSideRendering.getTrueValue();
        final EDhApiDebugRendering debug=Config.Client.Advanced.Debugging.debugRenderingColors.getTrueValue();
        Modes(EDhApiGrassSideRendering next) {
            assertFalse(Config.Client.Advanced.Graphics.Quality.grassSideRendering.apiIsOverriding());
            assertFalse(Config.Client.Advanced.Debugging.debugRenderingColors.apiIsOverriding());
            grass(next);Config.Client.Advanced.Debugging.debugRenderingColors.setWithoutFiringEvents(EDhApiDebugRendering.OFF);
        }
        static void grass(EDhApiGrassSideRendering next){Config.Client.Advanced.Graphics.Quality.grassSideRendering.setWithoutFiringEvents(next);}
        @Override public void close(){grass(grass);Config.Client.Advanced.Debugging.debugRenderingColors.setWithoutFiringEvents(debug);}
    }
    private static IClientLevelWrapper colors() {
        return (IClientLevelWrapper)Proxy.newProxyInstance(IClientLevelWrapper.class.getClassLoader(),new Class<?>[]{IClientLevelWrapper.class},
                (proxy,method,args)->switch(method.getName()) {
                    case "getDirtBlockColor"->SurvivalMaterials.argb(SurvivalMaterials.DIRT);
                    case "getShade"->switch((EDhDirection)args[0]){case UP,DOWN->1f;case NORTH,SOUTH->.85f;case WEST,EAST->.72f;};
                    default->throw new UnsupportedOperationException("No reference world");
                });
    }
    private static byte[] officialReference()throws Exception {
        try(var builder=LodQuadBuilder.getBuilder(false,colors());var checkout=LodQuadBuilder.ARRAY_LIST_POOL.checkoutByteBuffers(1)) {
            var g=faces().getFirst().geometry();
            builder.addQuadAdj(EDhDirection.NORTH,(short)g.x(),(short)g.y(),(short)g.z(),(short)g.width(),(short)g.height(),
                    g.argb(),(short)0,(byte)13,(byte)15,(byte)0);
            var buffers=builder.makeOpaqueVertexBuffers(checkout);assertEquals(1,buffers.size());
            byte[] bytes=new byte[buffers.getFirst().remaining()];buffers.getFirst().get(bytes);return bytes;
        }
    }
    private static byte[] officialLegacyReference(List<DhTerrainGeometry.PageFace> faces,byte fallback)throws Exception {
        try(var builder=LodQuadBuilder.getBuilder(false,null);var checkout=LodQuadBuilder.ARRAY_LIST_POOL.checkoutByteBuffers(1)) {
            for(var face:faces) {
                byte category=face.material()==0?fallback:(byte)face.material();
                if(face.direction()==DhTerrainGeometry.Direction.DOWN)builder.addQuadDown((short)face.x(),(short)face.y(),(short)face.z(),
                        (short)face.width(),face.argb(),(short)0,category,(byte)15,(byte)0);
                else builder.addQuadAdj(EDhDirection.valueOf(face.direction().name()),(short)face.x(),(short)face.y(),(short)face.z(),
                        (short)face.width(),(short)face.height(),face.argb(),(short)0,category,(byte)15,(byte)0);
            }
            var buffers=builder.makeOpaqueVertexBuffers(checkout);int total=buffers.stream().mapToInt(ByteBuffer::remaining).sum();
            byte[] bytes=new byte[total];int offset=0;for(var buffer:buffers){int count=buffer.remaining();buffer.get(bytes,offset,count);offset+=count;}
            return bytes;
        }
    }
    private static class RecordingCalls implements InstalledDhTerrainRuntime.HelperCalls {
        Object builder,checkout;
        int borrows;
        final List<InstalledDhTerrainRuntime.Resource> closes=new ArrayList<>();
        @Override public Object borrow(Method method,Object target,Object... arguments)throws ReflectiveOperationException {
            borrows++;Object value=InstalledDhTerrainRuntime.HelperCalls.super.borrow(method,target,arguments);
            if(method.getName().equals("getBuilder"))builder=value;else checkout=value;return value;
        }
        @Override public void close(InstalledDhTerrainRuntime.Resource kind,Object value)throws Exception {
            closes.add(kind);InstalledDhTerrainRuntime.HelperCalls.super.close(kind,value);
        }
    }
    private static void assertDrained(InstalledDhTerrainRuntime runtime)throws Exception {
        assertTrue(runtime.physicalDrain());assertFalse(runtime.poisoned());Object owner=owner(runtime);
        assertNull(field(owner,"builder"));assertNull(field(owner,"checkout"));assertNull(field(owner,"thread"));
        assertFalse((boolean)field(owner,"acquisitionUncertain"));
    }
    private static void assertPoisonedNoReuse(InstalledDhTerrainRuntime runtime,RecordingCalls calls)throws Exception {
        assertTrue(runtime.poisoned());assertFalse(runtime.physicalDrain());int borrows=calls.borrows,closes=calls.closes.size();
        assertThrows(IllegalStateException.class,()->runtime.buildSurvivalRectangles(faces()));
        assertThrows(IllegalStateException.class,()->runtime.compileSurvivalRectangles(faces(),null,()->{}));
        assertThrows(IllegalStateException.class,()->runtime.buildRectangles(List.of(InstalledDhIndependentHelperTest.top(0,21))));
        assertThrows(IllegalStateException.class,()->runtime.build(List.of(new InstalledDhTerrainRuntime.Face("UP",0,1,0,-1))));
        assertEquals(borrows,calls.borrows);assertEquals(closes,calls.closes.size(),"No ambiguous close is retried");
        assertNotNull(field(owner(runtime),"thread"));
        synchronized(RETAINED_POISONED){RETAINED_POISONED.add(runtime);}
    }

    @Test void actualPrivateCaptureMatchesEveryOfficialGrassModeAndIndependentReference()throws Exception {
        freshThread(()->{
            var runtime=isolated(InstalledDhTerrainRuntime.HelperCalls.DIRECT);long previous=0;
            for(var mode:EDhApiGrassSideRendering.values())try(var ignored=new Modes(mode)) {
                var observed=runtime.observeSurvivalSettings();
                var result=runtime.compileSurvivalRectangles(faces(),observed,()->{});
                assertArrayEquals(officialReference(),result.bytes());assertSame(result.bytes(),result.bytes(),"No hidden second output copy");
                assertEquals(mode.name(),result.settings().grassSideMode());assertEquals("OFF",result.settings().debugMode());
                assertTrue(result.settings().captureOrdinal()>previous);previous=result.settings().captureOrdinal();
                assertTrue(result.settings().physicalSettingsIdentity().contains("actual-borrowed-settings-v2"));
                assertFalse(result.settings().physicalSettingsIdentity().contains("observed-config-only"));
                assertTrue(observed.diagnosticIdentity().contains("observed-config-only"));assertDrained(runtime);
            }
        });
    }
    @Test void actualLegacyGeometryIsUnchangedAcrossSuccessfulOwnedBorrows()throws Exception {
        freshThread(()->{
            var calls=new RecordingCalls();var runtime=isolated(calls);
            var legacy=List.of(InstalledDhIndependentHelperTest.top(0,27));
            for(byte material:new byte[]{0,2})assertArrayEquals(officialLegacyReference(legacy,material),runtime.buildRectangles(legacy,material,()->{}));
            var snow=List.of(new DhTerrainGeometry.PageFace(DhTerrainGeometry.Direction.UP,0,3,4,5,2,2,-1,8));
            assertArrayEquals(officialLegacyReference(snow,(byte)0),runtime.buildRectangles(snow));
            var unit=List.of(new InstalledDhTerrainRuntime.Face("DOWN",2,3,4,-1));
            assertArrayEquals(officialLegacyReference(List.of(new DhTerrainGeometry.PageFace(DhTerrainGeometry.Direction.DOWN,0,2,3,4,1,1,-1)),(byte)0),runtime.build(unit));
            assertEquals(List.of(InstalledDhTerrainRuntime.Resource.CHECKOUT,InstalledDhTerrainRuntime.Resource.BUILDER,
                    InstalledDhTerrainRuntime.Resource.CHECKOUT,InstalledDhTerrainRuntime.Resource.BUILDER,
                    InstalledDhTerrainRuntime.Resource.CHECKOUT,InstalledDhTerrainRuntime.Resource.BUILDER,
                    InstalledDhTerrainRuntime.Resource.CHECKOUT,InstalledDhTerrainRuntime.Resource.BUILDER),calls.closes);
            assertDrained(runtime);
        });
    }
    @Test void modelEachCloseFaultRetainsOnlyItsExactUncertainResourceAndPoisonsAllEntrypoints()throws Exception {
        for(var resource:InstalledDhTerrainRuntime.Resource.values())for(boolean afterRealClose:new boolean[]{false,true})for(boolean fatal:new boolean[]{false,true}) {
            freshThread(()->{
                Throwable signal=fatal?new AssertionError("MODEL close Error"):new IOException("MODEL close exception");
                var calls=new RecordingCalls(){
                    @Override public void close(InstalledDhTerrainRuntime.Resource kind,Object value)throws Exception {
                        closes.add(kind);
                        if(kind!=resource||afterRealClose)((AutoCloseable)value).close();
                        if(kind==resource){if(signal instanceof Error error)throw error;throw (Exception)signal;}
                    }
                };
                var runtime=isolated(calls);Throwable thrown=assertThrows(Throwable.class,()->runtime.buildSurvivalRectangles(faces()));
                if(fatal)assertSame(signal,thrown);else assertSame(signal,thrown.getCause());
                Object owner=owner(runtime);
                assertSame(resource==InstalledDhTerrainRuntime.Resource.CHECKOUT?calls.checkout:null,field(owner,"checkout"));
                assertSame(resource==InstalledDhTerrainRuntime.Resource.BUILDER?calls.builder:null,field(owner,"builder"));
                assertSame(signal,field(owner,resource==InstalledDhTerrainRuntime.Resource.CHECKOUT?"checkoutFailure":"builderFailure"));
                assertEquals(List.of(InstalledDhTerrainRuntime.Resource.CHECKOUT,InstalledDhTerrainRuntime.Resource.BUILDER),calls.closes);
                assertPoisonedNoReuse(runtime,calls);
            });
        }
    }
    @Test void modelTwoCloseFaultsPreservePrimaryCancellationAndSuppressionOrder()throws Exception {
        freshThread(()->{
            var checkoutFailure=new IOException("MODEL checkout failure");var builderFailure=new AssertionError("MODEL builder failure");
            var calls=new RecordingCalls(){
                @Override public void close(InstalledDhTerrainRuntime.Resource kind,Object value)throws Exception {
                    closes.add(kind);if(kind==InstalledDhTerrainRuntime.Resource.CHECKOUT)throw checkoutFailure;throw builderFailure;
                }
            };
            var runtime=isolated(calls);var signal=new CancellationException("Primary cancellation after acquisition");var count=new AtomicInteger();
            assertSame(signal,assertThrows(CancellationException.class,()->runtime.buildSurvivalRectangles(faces(),()->{
                if(count.incrementAndGet()==faces().size()+4)throw signal;
            })));
            assertArrayEquals(new Throwable[]{checkoutFailure},signal.getSuppressed());
            assertArrayEquals(new Throwable[]{builderFailure},checkoutFailure.getSuppressed());
            assertSame(calls.checkout,field(owner(runtime),"checkout"));assertSame(calls.builder,field(owner(runtime),"builder"));
            assertPoisonedNoReuse(runtime,calls);
        });
    }
    @Test void modelTwoCloseFaultsWithoutPrimaryPreserveFirstAndSuppressedSecond()throws Exception {
        freshThread(()->{
            var first=new AssertionError("MODEL first");var second=new IOException("MODEL second");
            var calls=new RecordingCalls(){
                @Override public void close(InstalledDhTerrainRuntime.Resource kind,Object value)throws Exception {
                    closes.add(kind);if(kind==InstalledDhTerrainRuntime.Resource.CHECKOUT)throw first;throw second;
                }
            };
            var runtime=isolated(calls);assertSame(first,assertThrows(AssertionError.class,()->runtime.buildSurvivalRectangles(faces())));
            assertArrayEquals(new Throwable[]{second},first.getSuppressed());assertPoisonedNoReuse(runtime,calls);
        });
    }
    @Test void modelInvokedAcquisitionFailureKeepsUnknownHandleTombstoneAndNeverRetries()throws Exception {
        for(boolean failBuilder:new boolean[]{true,false})for(boolean afterRealBorrow:new boolean[]{false,true})freshThread(()->{
            var signal=new AssertionError("MODEL upstream constructor/acquisition failure without returned handle");
            var calls=new RecordingCalls(){
                @Override public Object borrow(Method method,Object target,Object... arguments)throws ReflectiveOperationException {
                    if(method.getName().equals("getBuilder")==failBuilder){
                        if(afterRealBorrow)super.borrow(method,target,arguments);else borrows++;
                        // No handle returns to the runtime; only this test double knows it.
                        throw new InvocationTargetException(signal);
                    }
                    return super.borrow(method,target,arguments);
                }
            };
            var runtime=isolated(calls);var thrown=assertThrows(IllegalStateException.class,()->runtime.buildSurvivalRectangles(faces()));
            assertSame(signal,thrown.getCause());Object owner=owner(runtime);
            assertTrue((boolean)field(owner,"acquisitionUncertain"));assertInstanceOf(InvocationTargetException.class,field(owner,"acquisitionFailure"));
            assertNull(field(owner,"checkout"));assertNull(field(owner,"builder"));
            assertEquals(failBuilder?List.of():List.of(InstalledDhTerrainRuntime.Resource.BUILDER),calls.closes);
            assertPoisonedNoReuse(runtime,calls);
        });
    }
    @Test void modelPreInvocationAccessFailureHasNoUncertainBorrowAndDrainsNormally()throws Exception {
        for(boolean failBuilder:new boolean[]{true,false})freshThread(()->{
            var once=new AtomicBoolean(true);
            var calls=new RecordingCalls(){
                @Override public Object borrow(Method method,Object target,Object... arguments)throws ReflectiveOperationException {
                    if(method.getName().equals("getBuilder")==failBuilder&&once.getAndSet(false))throw new IllegalAccessException("MODEL pre-invocation refusal");
                    return super.borrow(method,target,arguments);
                }
            };
            var runtime=isolated(calls);assertThrows(IllegalStateException.class,()->runtime.buildSurvivalRectangles(faces()));
            assertDrained(runtime);assertArrayEquals(officialReference(),runtime.buildSurvivalRectangles(faces()));assertDrained(runtime);
        });
    }
    @Test void cancellationAtEveryActualCheckpointIncludingPartialAcquisitionDrainsNormally()throws Exception {
        freshThread(()->{
            var calls=new RecordingCalls();var runtime=isolated(calls);var visits=new AtomicInteger();
            byte[] expected=runtime.buildSurvivalRectangles(faces(),visits::incrementAndGet);int total=visits.get();
            for(int stop=1;stop<=total;stop++) {
                int target=stop;var seen=new AtomicInteger();var signal=new CancellationException("checkpoint "+stop);
                assertSame(signal,assertThrows(CancellationException.class,()->runtime.buildSurvivalRectangles(faces(),()->{
                    assertFalse(runtime.physicalDrain());if(seen.incrementAndGet()==target)throw signal;
                })));
                assertDrained(runtime);assertArrayEquals(expected,runtime.buildSurvivalRectangles(faces()));
            }
        });
    }
    @Test void modelFailureImmediatelyAfterBuilderAcquisitionClosesThatExactBuilder()throws Exception {
        freshThread(()->{
            var signal=new AssertionError("MODEL post-acquisition failure");
            var calls=new RecordingCalls(){
                @Override public void checkpoint(InstalledDhTerrainRuntime.Phase phase,Object value) {
                    if(phase==InstalledDhTerrainRuntime.Phase.BUILDER_ACQUIRED){assertSame(builder,value);throw signal;}
                }
            };
            var runtime=isolated(calls);assertSame(signal,assertThrows(AssertionError.class,()->runtime.buildSurvivalRectangles(faces())));
            assertEquals(List.of(InstalledDhTerrainRuntime.Resource.BUILDER),calls.closes);assertNull(calls.checkout);assertDrained(runtime);
        });
    }
    @Test void modelOutputCopyErrorPreservesPrimaryAndReturnsActualResources()throws Exception {
        freshThread(()->{
            var signal=new OutOfMemoryError("MODEL detached output copy failure");
            var once=new AtomicBoolean(true);
            var calls=new RecordingCalls(){
                @Override public void checkpoint(InstalledDhTerrainRuntime.Phase phase,Object value) {
                    if(phase==InstalledDhTerrainRuntime.Phase.OUTPUT_COPY&&once.getAndSet(false)){assertInstanceOf(ByteBuffer.class,value);throw signal;}
                }
            };
            var runtime=isolated(calls);assertSame(signal,assertThrows(OutOfMemoryError.class,()->runtime.buildSurvivalRectangles(faces())));
            assertDrained(runtime);assertArrayEquals(officialReference(),runtime.buildSurvivalRectangles(faces()));assertDrained(runtime);
        });
    }
    @Test void actualPublicConfigAbaWithDifferentPrivateCaptureCannotMintAReceipt()throws Exception {
        freshThread(()->{
            try(var ignored=new Modes(EDhApiGrassSideRendering.AS_GRASS)) {
                var once=new AtomicBoolean(true);
                var calls=new RecordingCalls(){
                    @Override public Object borrow(Method method,Object target,Object... arguments)throws ReflectiveOperationException {
                        if(method.getName().equals("getBuilder")&&once.getAndSet(false)) {
                            Modes.grass(EDhApiGrassSideRendering.AS_DIRT);
                            try{return super.borrow(method,target,arguments);}finally{Modes.grass(EDhApiGrassSideRendering.AS_GRASS);}
                        }
                        return super.borrow(method,target,arguments);
                    }
                };
                var runtime=isolated(calls);var observed=runtime.observeSurvivalSettings();
                assertThrows(IllegalStateException.class,()->runtime.compileSurvivalRectangles(faces(),observed,()->{}));
                assertEquals(observed,runtime.observeSurvivalSettings());assertNull(calls.checkout);assertDrained(runtime);
                var result=runtime.compileSurvivalRectangles(faces(),observed,()->{});
                assertEquals("AS_GRASS",result.settings().grassSideMode());assertArrayEquals(officialReference(),result.bytes());
            }
        });
    }
    @Test void actualHelperReuseWithConfigAbaBeforePackingRefusesCapturedSettingsMismatch()throws Exception {
        freshThread(()->{
            try(var ignored=new Modes(EDhApiGrassSideRendering.AS_GRASS)) {
                var calls=new RecordingCalls();var runtime=isolated(calls);var seen=new AtomicInteger();
                assertThrows(IllegalStateException.class,()->runtime.compileSurvivalRectangles(faces(),runtime.observeSurvivalSettings(),()->{
                    if(seen.incrementAndGet()==faces().size()+3) {
                        Modes.grass(EDhApiGrassSideRendering.AS_DIRT);
                        try{assertSame(calls.builder,LodQuadBuilder.getBuilder(false,colors()));}
                        finally{Modes.grass(EDhApiGrassSideRendering.AS_GRASS);}
                    }
                }));
                assertEquals(EDhApiGrassSideRendering.AS_GRASS,Config.Client.Advanced.Graphics.Quality.grassSideRendering.get());
                assertDrained(runtime);assertArrayEquals(officialReference(),runtime.buildSurvivalRectangles(faces()));
            }
        });
    }
    @Test void actualHelperReuseDuringOutputCopyCannotReturnStaleAttestation()throws Exception {
        freshThread(()->{
            try(var ignored=new Modes(EDhApiGrassSideRendering.AS_GRASS)) {
                var calls=new RecordingCalls(){
                    @Override public void checkpoint(InstalledDhTerrainRuntime.Phase phase,Object value) {
                        if(phase==InstalledDhTerrainRuntime.Phase.OUTPUT_COPY) {
                            Modes.grass(EDhApiGrassSideRendering.AS_DIRT);
                            try{assertSame(builder,LodQuadBuilder.getBuilder(false,colors()));}finally{Modes.grass(EDhApiGrassSideRendering.AS_GRASS);}
                        }
                    }
                };
                var runtime=isolated(calls);assertThrows(IllegalStateException.class,()->runtime.compileSurvivalRectangles(faces(),null,()->{}));
                assertDrained(runtime);
            }
        });
    }
    @Test void stalePublicObservationRefusesBeforeAnyBorrowAndCannotBecomeAttestation()throws Exception {
        freshThread(()->{
            try(var ignored=new Modes(EDhApiGrassSideRendering.AS_GRASS)) {
                var calls=new RecordingCalls();var runtime=isolated(calls);var old=runtime.observeSurvivalSettings();
                Modes.grass(EDhApiGrassSideRendering.AS_DIRT);
                assertThrows(IllegalStateException.class,()->runtime.compileSurvivalRectangles(faces(),old,()->{}));
                assertEquals(0,calls.borrows);assertDrained(runtime);
                assertTrue(Arrays.stream(InstalledDhTerrainRuntime.AttestedSurvivalSettings.class.getDeclaredConstructors()).allMatch(c->Modifier.isPrivate(c.getModifiers())));
                assertTrue(Arrays.stream(InstalledDhTerrainRuntime.SurvivalCompilation.class.getDeclaredConstructors()).allMatch(c->Modifier.isPrivate(c.getModifiers())));
            }
        });
    }
    @Test void actualCapturesRemainPairedWithDetachedBytesAcrossLaterHelperReuse()throws Exception {
        freshThread(()->{
            var runtime=isolated(InstalledDhTerrainRuntime.HelperCalls.DIRECT);
            try(var ignored=new Modes(EDhApiGrassSideRendering.AS_GRASS)) {
                var first=runtime.compileSurvivalRectangles(faces(),null,()->{});byte[] saved=first.bytes().clone();
                Modes.grass(EDhApiGrassSideRendering.AS_DIRT);var second=runtime.compileSurvivalRectangles(faces(),null,()->{});
                assertEquals("AS_GRASS",first.settings().grassSideMode());assertEquals("AS_DIRT",second.settings().grassSideMode());
                assertTrue(second.settings().captureOrdinal()>first.settings().captureOrdinal());assertNotSame(first.settings(),second.settings());
                assertNotEquals(first.settings().physicalSettingsIdentity(),second.settings().physicalSettingsIdentity());
                assertArrayEquals(saved,first.bytes());assertArrayEquals(officialReference(),second.bytes());assertFalse(Arrays.equals(first.bytes(),second.bytes()));
                assertDrained(runtime);
            }
        });
    }
    @Test void actualPrivateFieldDescriptorsAndClosedModuleAccessibilityPolicyAreExact()throws Exception {
        installation();
        for(var entry:List.of(new Object[]{"grassSideRenderingMode",EDhApiGrassSideRendering.class},new Object[]{"debugRenderingMode",EDhApiDebugRendering.class})) {
            Field field=LodQuadBuilder.class.getDeclaredField((String)entry[0]);
            assertEquals(Modifier.PRIVATE,field.getModifiers());assertSame(entry[1],field.getType());
            assertSame(LodQuadBuilder.class,field.getDeclaringClass());InstalledDhTerrainRuntime.allowPrivateRead(field);
        }
        // Actual closed java.base field tests policy only, not NeoForge named-module attestation.
        Field closed=String.class.getDeclaredField("value");
        assertFalse(String.class.getModule().isOpen("java.lang",InstalledDhTerrainRuntime.class.getModule()));
        assertThrows(IllegalAccessException.class,()->InstalledDhTerrainRuntime.allowPrivateRead(closed));
        assertFalse(closed.trySetAccessible(),"Test does not open modules");
    }
    @Test void modelOrdinalExhaustionRefusesBeforeAcquiringAnyHelper()throws Exception {
        freshThread(()->{
            var calls=new RecordingCalls();var runtime=isolated(calls);
            Field ordinal=InstalledDhTerrainRuntime.class.getDeclaredField("captureOrdinal");ordinal.setAccessible(true);ordinal.setLong(runtime,Long.MAX_VALUE);
            assertThrows(ArithmeticException.class,()->runtime.compileSurvivalRectangles(faces(),null,()->{}));
            assertEquals(0,calls.borrows);assertTrue(calls.closes.isEmpty());assertDrained(runtime);
        });
    }
    @Test void liveBuildReportsNoPhysicalDrainToAnotherThreadUntilActualCleanup()throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var runtimeRef=new AtomicReference<InstalledDhTerrainRuntime>();
        var task=new FutureTask<Void>(()->{
            var runtime=isolated(InstalledDhTerrainRuntime.HelperCalls.DIRECT);runtimeRef.set(runtime);var once=new AtomicBoolean();
            runtime.buildSurvivalRectangles(faces(),()->{
                if(once.compareAndSet(false,true)){entered.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}
                    catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}}
            });return null;
        });
        var worker=new Thread(task,"installed-dh-live-drain-proof");worker.setDaemon(true);worker.start();
        try {assertTrue(entered.await(10,TimeUnit.SECONDS));assertFalse(runtimeRef.get().physicalDrain());assertFalse(runtimeRef.get().poisoned());}
        finally {release.countDown();}
        task.get(10,TimeUnit.SECONDS);worker.join(5000);assertFalse(worker.isAlive());assertDrained(runtimeRef.get());
    }
}
