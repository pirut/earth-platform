package io.earthplatform.compat.dh;

import com.seibel.distanthorizons.api.enums.config.EDhApiGrassSideRendering;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiBlockMaterial;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiDebugRendering;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodQuadBuilder;
import com.seibel.distanthorizons.core.enums.EDhDirection;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.coreapi.util.ColorUtil;
import io.earthplatform.geography.survival.SurvivalMaterials;
import io.earthplatform.minecraft.lod.DhTerrainGeometry;
import io.earthplatform.minecraft.lod.DhTerrainGeometry.Direction;
import io.earthplatform.minecraft.lod.SurvivalDhTerrainData.PageFace;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Installed official helper bytes only. No GL, native publication, or config-attestation claim. */
@Timeout(45)
class InstalledSurvivalDhPackingTest {
    private static final int[] CATEGORIES={1,2,3,5,13};
    private static InstalledDhTerrainRuntime runtime()throws Exception {
        assertEquals("installed-dh",System.getProperty("earthplatform.test.partition"));
        return InstalledDhIndependentHelperTest.runtime();
    }
    private static PageFace face(Direction direction,int page,int x,int y,int z,int width,int height,int category) {
        int base=switch(category){case 1->0xff4f7934;case 2->0xff898989;case 3->0xff6e5532;case 5->0xff866043;case 13->0xff72974a;default->-1;};
        return new PageFace(new DhTerrainGeometry.PageFace(direction,page,x,y,z,width,height,
                DhTerrainGeometry.stratifiedFaceArgb(direction,base)),category);
    }
    private static List<PageFace> fixture() {
        var values=new ArrayList<PageFace>();int x=0;
        for(int category:CATEGORIES)for(Direction direction:Direction.values()) {
            values.add(face(direction,-1,x,100,300,7,3,category));x+=16;
        }
        return List.copyOf(values);
    }
    /** Test-only configuration mutation: never save or fire listeners, always restore exact true values. */
    private static final class Modes implements AutoCloseable {
        final EDhApiGrassSideRendering grass=Config.Client.Advanced.Graphics.Quality.grassSideRendering.getTrueValue();
        final EDhApiDebugRendering debug=Config.Client.Advanced.Debugging.debugRenderingColors.getTrueValue();
        Modes(EDhApiGrassSideRendering mode) {
            assertFalse(Config.Client.Advanced.Graphics.Quality.grassSideRendering.apiIsOverriding());
            assertFalse(Config.Client.Advanced.Debugging.debugRenderingColors.apiIsOverriding());
            Config.Client.Advanced.Graphics.Quality.grassSideRendering.setWithoutFiringEvents(mode);
            Config.Client.Advanced.Debugging.debugRenderingColors.setWithoutFiringEvents(EDhApiDebugRendering.OFF);
        }
        @Override public void close() {
            Config.Client.Advanced.Graphics.Quality.grassSideRendering.setWithoutFiringEvents(grass);
            Config.Client.Advanced.Debugging.debugRenderingColors.setWithoutFiringEvents(debug);
        }
    }
    private static float shade(Direction direction) {
        return switch(direction){case UP,DOWN->1f;case NORTH,SOUTH->.85f;case WEST,EAST->.72f;};
    }
    /** Independent reference supplies the same declared source data, never calls the adapter. */
    private static IClientLevelWrapper referenceColors() {
        return (IClientLevelWrapper)Proxy.newProxyInstance(IClientLevelWrapper.class.getClassLoader(),
                new Class<?>[]{IClientLevelWrapper.class},(proxy,method,args)->{
                    if(method.getName().equals("getDirtBlockColor")&&method.getParameterCount()==0)
                        return SurvivalMaterials.argb(SurvivalMaterials.DIRT);
                    if(method.getName().equals("getShade")&&method.getParameterCount()==1)
                        return shade(Direction.valueOf(((EDhDirection)args[0]).name()));
                    throw new UnsupportedOperationException("No reference world: "+method);
                });
    }
    private static byte[] officialReference(List<PageFace> squaresAndOtherFaces)throws Exception {
        try(var builder=LodQuadBuilder.getBuilder(false,referenceColors());
                var checkout=LodQuadBuilder.ARRAY_LIST_POOL.checkoutByteBuffers(1)) {
            for(var face:squaresAndOtherFaces) {
                var g=face.geometry();
                if(g.direction()==Direction.DOWN) {
                    assertEquals(g.width(),g.height(),"Reference DOWN inputs must already be explicit squares");
                    builder.addQuadDown((short)g.x(),(short)g.y(),(short)g.z(),(short)g.width(),g.argb(),(short)0,(byte)face.category(),(byte)15,(byte)0);
                } else builder.addQuadAdj(EDhDirection.valueOf(g.direction().name()),(short)g.x(),(short)g.y(),(short)g.z(),
                        (short)g.width(),(short)g.height(),g.argb(),(short)0,(byte)face.category(),(byte)15,(byte)0);
            }
            var buffers=builder.makeOpaqueVertexBuffers(checkout);int size=buffers.stream().mapToInt(ByteBuffer::remaining).sum();
            byte[] result=new byte[size];int offset=0;
            for(var buffer:buffers){int count=buffer.remaining();buffer.get(result,offset,count);offset+=count;}
            return result;
        }
    }
    /** Hard-coded independent cover of the 7x3 fixture, rather than repeating production tiling. */
    private static List<PageFace> referenceSevenByThree(PageFace face) {
        var g=face.geometry();if(g.direction()!=Direction.DOWN)return List.of(face);
        assertEquals(7,g.width());assertEquals(3,g.height());
        return List.of(square(face,0,0,3),square(face,3,0,3),square(face,6,0,1),square(face,6,1,1),square(face,6,2,1));
    }
    private static PageFace square(PageFace source,int dx,int dz,int side) {
        var g=source.geometry();return new PageFace(new DhTerrainGeometry.PageFace(Direction.DOWN,g.pageY2048(),
                g.x()+dx,g.y(),g.z()+dz,side,side,g.argb()),source.category());
    }
    private static int unsigned(ByteBuffer bytes,int vertex,int coordinate) {return Short.toUnsignedInt(bytes.getShort(vertex*16+coordinate*2));}
    private static int color(byte[] packed,int vertex) {
        int at=vertex*16+8;return Byte.toUnsignedInt(packed[at+3])<<24|Byte.toUnsignedInt(packed[at])<<16
                |Byte.toUnsignedInt(packed[at+1])<<8|Byte.toUnsignedInt(packed[at+2]);
    }
    private static void materialAndNormal(byte[] packed,int category,Direction direction) {
        assertTrue(packed.length>0);assertEquals(0,packed.length%64);
        for(int at=0;at<packed.length;at+=16) {
            assertEquals(category,Byte.toUnsignedInt(packed[at+12]));
            assertEquals(EDhDirection.valueOf(direction.name()).faceIndex,Byte.toUnsignedInt(packed[at+13]));
        }
    }
    /** Raster census is independent of the subdivision and validates no holes, overlaps or plane shift. */
    private static void exactSmallRectangle(PageFace face,byte[] packed) {
        var g=face.geometry();assertTrue(g.width()<=32&&g.height()<=32);
        int[][] covered=new int[g.width()][g.height()];var bytes=ByteBuffer.wrap(packed).order(ByteOrder.nativeOrder());
        for(int q=0;q<packed.length/64;q++) {
            int uMin=Integer.MAX_VALUE,vMin=Integer.MAX_VALUE,uMax=-1,vMax=-1;
            for(int corner=0;corner<4;corner++) {
                int vertex=q*4+corner,x=unsigned(bytes,vertex,0),y=unsigned(bytes,vertex,1),z=unsigned(bytes,vertex,2),u,v;
                switch(g.direction()) {
                    case UP,DOWN->{assertEquals(g.y(),y);u=x-g.x();v=z-g.z();}
                    case NORTH,SOUTH->{assertEquals(g.z(),z);u=x-g.x();v=y-g.y();}
                    case WEST,EAST->{assertEquals(g.x(),x);u=z-g.z();v=y-g.y();}
                    default->throw new AssertionError();
                }
                assertTrue(u>=0&&u<=g.width());assertTrue(v>=0&&v<=g.height());
                uMin=Math.min(uMin,u);uMax=Math.max(uMax,u);vMin=Math.min(vMin,v);vMax=Math.max(vMax,v);
            }
            assertTrue(uMax>uMin&&vMax>vMin);
            for(int u=uMin;u<uMax;u++)for(int v=vMin;v<vMax;v++)covered[u][v]++;
        }
        for(int[] column:covered)for(int count:column)assertEquals(1,count,"Exact rectangular face coverage");
    }
    private static int availableCheckouts()throws Exception {
        var field=LodQuadBuilder.ARRAY_LIST_POOL.getClass().getDeclaredField("pooledCheckoutsRefs");field.setAccessible(true);
        return ((java.util.Queue<?>)field.get(LodQuadBuilder.ARRAY_LIST_POOL)).size();
    }
    private static final class Worker implements AutoCloseable {
        final ThreadPoolExecutor executor=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(2),
                runnable->{var thread=new Thread(runnable,"installed-survival-dh-proof");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
        @Override public void close()throws Exception {executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
    }

    @Test void pinnedOfficialAdjacentPathRefusesDownAndSquarePathPreservesDown()throws Exception {
        runtime();
        try(var builder=LodQuadBuilder.getBuilder(false,referenceColors())) {
            assertThrows(IllegalArgumentException.class,()->builder.addQuadAdj(EDhDirection.DOWN,(short)1,(short)2,(short)3,
                    (short)7,(short)3,-1,(short)0,(byte)2,(byte)15,(byte)0));
        }
        var down=face(Direction.DOWN,0,1,2,3,3,3,2);
        assertArrayEquals(officialReference(List.of(down)),runtime().buildSurvivalRectangles(List.of(down)));
    }
    @Test void everyTruthfulCategoryDirectionAndGrassModeMatchesExactOfficialReferenceBytes()throws Exception {
        var runtime=runtime();
        assertArrayEquals(CATEGORIES,new int[]{EDhApiBlockMaterial.LEAVES.index,EDhApiBlockMaterial.STONE.index,
                EDhApiBlockMaterial.WOOD.index,EDhApiBlockMaterial.DIRT.index,EDhApiBlockMaterial.GRASS.index});
        for(var mode:EDhApiGrassSideRendering.values())try(var ignored=new Modes(mode)) {
            for(var face:fixture()) {
                byte[] actual=runtime.buildSurvivalRectangles(List.of(face));
                assertArrayEquals(officialReference(referenceSevenByThree(face)),actual,mode+" "+face);
                materialAndNormal(actual,face.category(),face.geometry().direction());exactSmallRectangle(face,actual);
            }
        }
    }
    @Test void grassReplacementUsesExactlyOneSourceDerivedShadeWithoutChangingCategory()throws Exception {
        var runtime=runtime();
        for(var mode:EDhApiGrassSideRendering.values())try(var ignored=new Modes(mode)) {
            for(Direction direction:Direction.values()) {
                var face=face(direction,0,11,101,17,7,3,13);var g=face.geometry();
                byte[] packed=runtime.buildSurvivalRectangles(List.of(face));var bytes=ByteBuffer.wrap(packed).order(ByteOrder.nativeOrder());
                for(int vertex=0;vertex<packed.length/16;vertex++) {
                    boolean dirt=mode!=EDhApiGrassSideRendering.AS_GRASS&&direction!=Direction.UP
                            &&(mode==EDhApiGrassSideRendering.AS_DIRT||direction==Direction.DOWN||unsigned(bytes,vertex,1)==g.y());
                    int expected=dirt?ColorUtil.applyShade(SurvivalMaterials.argb(SurvivalMaterials.DIRT),shade(direction)):g.argb();
                    assertEquals(expected,color(packed,vertex),mode+" "+direction+" vertex "+vertex);
                }
                materialAndNormal(packed,13,direction);
            }
        }
    }
    @Test void signedPagesNativeExtremesAndAllPositiveNegativeBoundaryPlanesRemainExact()throws Exception {
        var runtime=runtime();
        for(int page:new int[]{-6,-1,0,4})for(Direction direction:Direction.values()) {
            int y=page==-6?1280:page==4?608:0;
            if(direction==Direction.UP)y=page==-6?1281:page==4?672:2048;
            int x=direction==Direction.WEST||direction==Direction.EAST?4096:4089;
            int z=direction==Direction.NORTH||direction==Direction.SOUTH?4096:4093;
            if(direction==Direction.WEST||direction==Direction.EAST)z=4089;
            var face=face(direction,page,x,y,z,7,3,2);byte[] packed=runtime.buildSurvivalRectangles(List.of(face));
            exactSmallRectangle(face,packed);materialAndNormal(packed,2,direction);
            assertArrayEquals(officialReference(referenceSevenByThree(face)),packed);
            var bytes=ByteBuffer.wrap(packed).order(ByteOrder.nativeOrder());
            for(int v=0;v<packed.length/16;v++) {
                long nativeY=(long)page*2048+unsigned(bytes,v,1);
                assertTrue(nativeY>=DhTerrainGeometry.MIN_Y&&nativeY<=DhTerrainGeometry.MAX_Y_EXCLUSIVE);
            }
        }
        for(Direction direction:Direction.values()) {
            int y=direction==Direction.UP?2048:0;
            assertArrayEquals(runtime.buildSurvivalRectangles(List.of(face(direction,-1,0,y,0,7,3,2))),
                    runtime.buildSurvivalRectangles(List.of(face(direction,0,0,y,0,7,3,2))),"Signed page never wraps into vertex shorts");
        }
    }
    @Test void everySmallRectangularDownShapeHasExactAreaAndNoOverlaps()throws Exception {
        var runtime=runtime();
        for(int width=1;width<=16;width++)for(int height=1;height<=16;height++) {
            var face=face(Direction.DOWN,-1,4080,0,4080,width,height,3);
            var packed=runtime.buildSurvivalRectangles(List.of(face));exactSmallRectangle(face,packed);materialAndNormal(packed,3,Direction.DOWN);
            assertTrue(packed.length<=4096*64);
        }
    }
    @Test void maximumSubmittedDownSquaresAndMaximumInputFacesKeepTheExistingOutputCeiling()throws Exception {
        var runtime=runtime();var longDown=face(Direction.DOWN,0,0,0,4095,4096,1,2);
        byte[] packed=runtime.buildSurvivalRectangles(List.of(longDown));assertEquals(262144,packed.length);
        var bytes=ByteBuffer.wrap(packed).order(ByteOrder.nativeOrder());
        for(int q=0;q<4096;q++) {
            int minX=4096,maxX=0;
            for(int v=q*4;v<q*4+4;v++){int x=unsigned(bytes,v,0);minX=Math.min(minX,x);maxX=Math.max(maxX,x);assertEquals(0,unsigned(bytes,v,1));}
            assertEquals(q,minX);assertEquals(q+1,maxX);
        }
        var faces=new ArrayList<PageFace>();
        for(int i=0;i<4096;i++)faces.add(face(Direction.DOWN,0,i%64*2,17,i/64*2,1,1,2));
        assertEquals(262144,runtime.buildSurvivalRectangles(faces).length);
        assertEquals(4096,InstalledDhTerrainRuntime.SURVIVAL_MAX_FACES);
        assertEquals(262144,InstalledDhTerrainRuntime.SURVIVAL_MAX_OUTPUT_BYTES);
        assertEquals(33024,InstalledDhTerrainRuntime.SURVIVAL_INPUT_COPY_BYTES);
    }
    @Test void overBudgetSubdivisionAndInvalidInputsRefuseBeforeTouchingAnOfficialCheckout()throws Exception {
        var runtime=runtime();var good=face(Direction.UP,0,0,1,0,1,1,2);
        try(var builder=LodQuadBuilder.getBuilder(false,null);var checkout=LodQuadBuilder.ARRAY_LIST_POOL.checkoutByteBuffers(1)) {
            builder.addQuadUp((short)12,(short)13,(short)14,(short)1,-1,(short)0,(byte)2,(byte)15,(byte)0);
            int available=availableCheckouts();
            assertThrows(IllegalArgumentException.class,()->runtime.buildSurvivalRectangles(List.of()));
            assertThrows(IllegalArgumentException.class,()->runtime.buildSurvivalRectangles(java.util.Collections.nCopies(4097,good)));
            assertThrows(IllegalArgumentException.class,()->runtime.buildSurvivalRectangles(List.of(
                    face(Direction.DOWN,0,0,0,0,4096,1,2),good)));
            assertThrows(NullPointerException.class,()->runtime.buildSurvivalRectangles(Arrays.asList(good,null)));
            assertThrows(IllegalArgumentException.class,()->runtime.buildSurvivalRectangles(List.of(good,face(Direction.UP,-1,0,1,0,1,1,2))));
            assertEquals(available,availableCheckouts(),"No partial helper checkout before complete admission");
            assertEquals(64,builder.makeOpaqueVertexBuffers(checkout).stream().mapToInt(ByteBuffer::remaining).sum(),
                    "Refused compile did not reset or consume the independent already-borrowed builder");
        }
        assertEquals(64,runtime.buildSurvivalRectangles(List.of(good)).length);
    }
    @Test void unsupportedCategoriesAirTransparencyAndLegacyMaterialSubstitutionRemainImpossible()throws Exception {
        var g=new DhTerrainGeometry.PageFace(Direction.UP,0,0,1,0,1,1,-1);
        for(int category:new int[]{-1,0,4,6,7,8,9,10,11,12,14,15,256})
            assertThrows(IllegalArgumentException.class,()->new PageFace(g,category));
        assertThrows(IllegalArgumentException.class,()->new PageFace(new DhTerrainGeometry.PageFace(Direction.UP,0,0,1,0,1,1,0),14));
        assertThrows(IllegalArgumentException.class,()->new PageFace(new DhTerrainGeometry.PageFace(Direction.UP,0,0,1,0,1,1,0x7fffffff),2));
        assertThrows(IllegalArgumentException.class,()->new PageFace(new DhTerrainGeometry.PageFace(Direction.UP,0,0,1,0,1,1,-1,2),2));
        for(int category:new int[]{1,3,5,13})assertThrows(IllegalArgumentException.class,()->
                new DhTerrainGeometry.PageFace(Direction.UP,0,0,1,0,1,1,-1,category));
        var runtime=runtime();assertThrows(IllegalArgumentException.class,()->runtime.buildRectangles(List.of(g),(byte)13,()->{}));
    }
    @Test void everyCancellationCheckpointReturnsThePoolOnTheAcquiringWorkerAndKeepsCleanOutput()throws Exception {
        var runtime=runtime();var faces=fixture();var calls=new AtomicInteger();
        byte[] expected=runtime.buildSurvivalRectangles(faces,calls::incrementAndGet);int checkpoints=calls.get();assertTrue(checkpoints>80);
        byte[] legacy=runtime.buildRectangles(List.of(InstalledDhIndependentHelperTest.top(0,42)));int available=availableCheckouts();
        try(var worker=new Worker()) {
            for(int target=1;target<=checkpoints;target++) {
                int stop=target;var signal=new CancellationException("survival checkpoint "+stop);
                var failure=assertThrows(ExecutionException.class,()->worker.executor.submit(()->{
                    long owner=Thread.currentThread().threadId();var seen=new AtomicInteger();
                    return runtime.buildSurvivalRectangles(faces,()->{assertEquals(owner,Thread.currentThread().threadId());if(seen.incrementAndGet()==stop)throw signal;});
                }).get(5,TimeUnit.SECONDS));
                assertSame(signal,failure.getCause());assertEquals(available,availableCheckouts(),"Checkout returned at "+stop);
                assertArrayEquals(expected,worker.executor.submit(()->runtime.buildSurvivalRectangles(faces)).get(5,TimeUnit.SECONDS));
                assertArrayEquals(legacy,runtime.buildRectangles(List.of(InstalledDhIndependentHelperTest.top(0,42))));
            }
        }
    }
    @Test void originalUncheckedFailureAfterPartialEmissionIsPreservedAndBuilderIsReusable()throws Exception {
        var runtime=runtime();var faces=fixture();byte[] expected=runtime.buildSurvivalRectangles(faces);
        var signal=new AssertionError("Original failure after native emission");var calls=new AtomicInteger();
        assertSame(signal,assertThrows(AssertionError.class,()->runtime.buildSurvivalRectangles(faces,()->{
            if(calls.incrementAndGet()==faces.size()+8)throw signal;
        })));
        assertArrayEquals(expected,runtime.buildSurvivalRectangles(faces));
    }
    @Test void nestedSurvivalAndLegacyBuildsCannotCorruptOuterBuilderOwnership()throws Exception {
        var runtime=runtime();var faces=fixture();byte[] expected=runtime.buildSurvivalRectangles(faces);
        var legacy=List.of(InstalledDhIndependentHelperTest.top(0,55));var calls=new AtomicInteger();
        assertArrayEquals(expected,runtime.buildSurvivalRectangles(faces,()->{
            if(calls.incrementAndGet()==faces.size()+5) {
                assertTrue(assertThrows(IllegalStateException.class,()->runtime.buildSurvivalRectangles(faces)).getMessage().contains("reentrant"));
                assertTrue(assertThrows(IllegalStateException.class,()->runtime.buildRectangles(legacy)).getMessage().contains("reentrant"));
            }
        }));
        calls.set(0);
        assertThrows(IllegalStateException.class,()->runtime.buildRectangles(legacy,()->{if(calls.incrementAndGet()==1)runtime.buildSurvivalRectangles(faces);}));
        assertArrayEquals(expected,runtime.buildSurvivalRectangles(faces));
    }
    @Test void admittedImmutableFacesSurviveCallerListChangesAfterSnapshotAdmission()throws Exception {
        var runtime=runtime();var original=fixture();byte[] expected=runtime.buildSurvivalRectangles(original);
        var mutable=new ArrayList<>(original);var calls=new AtomicInteger();
        assertArrayEquals(expected,runtime.buildSurvivalRectangles(mutable,()->{
            if(calls.incrementAndGet()==original.size()+2){mutable.clear();mutable.add(face(Direction.UP,0,0,1,0,1,1,2));}
        }));
        assertEquals(1,mutable.size());
    }
    @Test void waitingCancellationIsCheckedAfterMonitorAcquisitionAndReleasesNoForeignCheckout()throws Exception {
        var runtime=runtime();var faces=fixture();byte[] expected=runtime.buildSurvivalRectangles(faces);
        var attempted=new CountDownLatch(1);var cancel=new AtomicBoolean();var launched=new AtomicBoolean();
        var waiting=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<byte[]>>();var signal=new CancellationException("cancel while waiting");
        try(var worker=new Worker()) {
            assertArrayEquals(expected,runtime.buildSurvivalRectangles(faces,()->{
                if(launched.compareAndSet(false,true)) {
                    waiting.set(worker.executor.submit(()->{attempted.countDown();return runtime.buildSurvivalRectangles(faces,()->{if(cancel.get())throw signal;});}));
                    try{assertTrue(attempted.await(5,TimeUnit.SECONDS));}catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}
                    cancel.set(true);
                }
            }));
            var failure=assertThrows(ExecutionException.class,()->waiting.get().get(5,TimeUnit.SECONDS));assertSame(signal,failure.getCause());
            assertArrayEquals(expected,worker.executor.submit(()->runtime.buildSurvivalRectangles(faces)).get(5,TimeUnit.SECONDS));
        }
    }
    @Test void returnedArraysNeverAliasLaterUpstreamBuffersOrLegacyOutput()throws Exception {
        var runtime=runtime();var faces=fixture();byte[] first=runtime.buildSurvivalRectangles(faces),saved=first.clone();
        for(int i=0;i<64;i++)runtime.buildSurvivalRectangles(List.of(face(Direction.DOWN,0,i,1,i,7,3,CATEGORIES[i%5])));
        assertArrayEquals(saved,first);first[0]^=1;assertArrayEquals(saved,runtime.buildSurvivalRectangles(faces));
        byte[] legacy=runtime.buildRectangles(List.of(InstalledDhIndependentHelperTest.top(0,31)));legacy[0]^=1;
        assertArrayEquals(saved,runtime.buildSurvivalRectangles(faces));
    }
    @Test void colorProjectionHasOnlyExactColorCallbacksAndNoWorldAccess()throws Exception {
        var runtime=runtime();runtime.buildSurvivalRectangles(List.of(face(Direction.DOWN,0,0,1,0,7,3,13)));
        var field=InstalledDhTerrainRuntime.class.getDeclaredField("survivalColorSource");field.setAccessible(true);
        Object colors=field.get(runtime);assertNotNull(colors);assertSame(IClientLevelWrapper.class.getClassLoader(),colors.getClass().getClassLoader());
        assertEquals(SurvivalMaterials.argb(SurvivalMaterials.DIRT),IClientLevelWrapper.class.getMethod("getDirtBlockColor").invoke(colors));
        for(Direction direction:Direction.values())assertEquals(shade(direction),
                IClientLevelWrapper.class.getMethod("getShade",EDhDirection.class).invoke(colors,EDhDirection.valueOf(direction.name())));
        var world=Arrays.stream(IClientLevelWrapper.class.getMethods()).filter(method->method.getParameterCount()==0
                &&!method.getName().equals("getDirtBlockColor")&&method.getDeclaringClass()!=Object.class).findFirst().orElseThrow();
        assertInstanceOf(UnsupportedOperationException.class,assertThrows(InvocationTargetException.class,()->world.invoke(colors)).getCause());
        assertTrue(colors.equals(colors));assertFalse(colors.equals(new Object()));assertEquals(System.identityHashCode(colors),colors.hashCode());
        assertEquals("Immutable survival DH color source",colors.toString());
    }
    @Test void observedModeChangesRefuseAtEveryPostAdmissionPhaseAndDoNotLeakCheckouts()throws Exception {
        var runtime=runtime();var faces=fixture();
        try(var ignored=new Modes(EDhApiGrassSideRendering.FADE_TO_DIRT)) {
            var expected=runtime.observeSurvivalSettings();var calls=new AtomicInteger();
            byte[] clean=runtime.buildSurvivalRectangles(faces,expected,calls::incrementAndGet);int checkpoints=calls.get(),available=availableCheckouts();
            assertTrue(expected.diagnosticIdentity().contains("observed-config-only"));
            for(int target=faces.size()+2;target<=checkpoints;target++) {
                int stop=target;var visits=new AtomicInteger();
                try {
                    assertThrows(IllegalStateException.class,()->runtime.buildSurvivalRectangles(faces,expected,()->{
                        if(visits.incrementAndGet()==stop)Config.Client.Advanced.Graphics.Quality.grassSideRendering.setWithoutFiringEvents(EDhApiGrassSideRendering.AS_GRASS);
                    }));
                }finally{Config.Client.Advanced.Graphics.Quality.grassSideRendering.setWithoutFiringEvents(EDhApiGrassSideRendering.FADE_TO_DIRT);}
                assertEquals(available,availableCheckouts());assertArrayEquals(clean,runtime.buildSurvivalRectangles(faces,expected,()->{}));
            }
        }
    }
    @Test void unsupportedDebugOrStaleObservationRefusesWithoutMutatingOfficialSettings()throws Exception {
        var runtime=runtime();var faces=fixture();
        try(var ignored=new Modes(EDhApiGrassSideRendering.FADE_TO_DIRT)) {
            var observation=runtime.observeSurvivalSettings();
            Config.Client.Advanced.Graphics.Quality.grassSideRendering.setWithoutFiringEvents(EDhApiGrassSideRendering.AS_DIRT);
            assertThrows(IllegalStateException.class,()->runtime.buildSurvivalRectangles(faces,observation,()->{}));
            assertEquals(EDhApiGrassSideRendering.AS_DIRT,Config.Client.Advanced.Graphics.Quality.grassSideRendering.get());
            var unsupported=Arrays.stream(EDhApiDebugRendering.values()).filter(value->value!=EDhApiDebugRendering.OFF).findFirst().orElseThrow();
            Config.Client.Advanced.Debugging.debugRenderingColors.setWithoutFiringEvents(unsupported);
            assertThrows(IllegalArgumentException.class,()->runtime.buildSurvivalRectangles(faces));
            assertEquals(unsupported,Config.Client.Advanced.Debugging.debugRenderingColors.get());
        }
        assertTrue(runtime.buildSurvivalRectangles(faces).length>0);
    }
    @Test void legacyUnknownStoneSnowDefaultsAndDownRejectionRemainByteForByteUnchanged()throws Exception {
        var runtime=runtime();var old=new ArrayList<DhTerrainGeometry.PageFace>();
        for(int material:new int[]{0,2,8})for(Direction direction:Direction.values())old.add(new DhTerrainGeometry.PageFace(
                direction,0,material*32+direction.ordinal()*3,101,15,2,2,0xffabcdef,material));
        byte[] unknown=runtime.buildRectangles(old,(byte)0,()->{}),stone=runtime.buildRectangles(old,(byte)2,()->{});
        runtime.buildSurvivalRectangles(fixture());
        assertArrayEquals(unknown,runtime.buildRectangles(old,(byte)0,()->{}));
        assertArrayEquals(stone,runtime.buildRectangles(old,(byte)2,()->{}));
        assertThrows(IllegalArgumentException.class,()->runtime.buildRectangles(List.of(new DhTerrainGeometry.PageFace(Direction.DOWN,0,0,1,0,7,3,-1))));
        assertArrayEquals(unknown,runtime.buildRectangles(old,(byte)0,()->{}));
    }
}
