package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.geography.MissingTerrainException;
import io.earthplatform.minecraft.scene.SceneReadyCube;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/** Real asynchronous view/cache protocol and exact CPU geometry; fake resources do not prove GL behavior. */
@Timeout(30)
class DhPreparedLodViewTest {
    private static final String IDENTITY = "prepared-flat-source-y1000";
    private static final WorldIdentity WORLD = new WorldIdentity(17, "scene", "1", IDENTITY, "native-palette");
    private static final long EPOCH = 9, RESOURCES = 3;

    private static class Sampler implements SceneSurfaceSampler {
        final AtomicReference<String> identity = new AtomicReference<>(IDENTITY);
        final AtomicReference<Gate> blockNext = new AtomicReference<>();
        final AtomicLong heightCalls=new AtomicLong();
        final Set<Thread> readers = java.util.concurrent.ConcurrentHashMap.newKeySet();
        @Override public long surfaceY(long x, long z) {
            readers.add(Thread.currentThread());heightCalls.incrementAndGet();
            Gate gate = blockNext.getAndSet(null);
            if (gate != null) { gate.entered.countDown(); await(gate.release); }
            return 1000;
        }
        @Override public int argb(long x, long z, long y) { return 0xffddeeff; }
        @Override public String identity() { return identity.get(); }
        @Override public boolean supportsConcurrentReads() { return true; }
    }

    private static final class Gate {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    }

    private static ExecutorService workers() {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2),
                task -> { var thread = new Thread(task, "incremental-test-source"); thread.setDaemon(true); return thread; },
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static final class Harness implements AutoCloseable {
        final Sampler sampler; final FakeDriver driver=new FakeDriver(); final ExecutorService workers=workers(); final DhPreparedLodView view;
        Harness(){this(new Sampler(),null);}
        Harness(Sampler sampler,java.util.function.Function<Executor,Executor> wrapper){this.sampler=sampler;view=new DhPreparedLodView(sampler,WORLD,EPOCH,RESOURCES,0,0,driver,wrapper==null?workers:wrapper.apply(workers));}
        void initialize(){view.initialize();}
        DhPreparedLodView.CoverageTicket prepare(long generation,List<SceneReadyCube> ready){var ticket=view.prepareCoverage(generation,ready);until(()->view.isPrepared(ticket),this::pump);return ticket;}
        void commit(long generation,List<SceneReadyCube> ready){assertTrue(view.commitPrepared(prepare(generation,ready)));assertTrue(view.coverageReady(generation));}
        void pump(){int before=driver.uploadCalls;long bytes=driver.uploadedBytes;view.pump();assertTrue(driver.uploadCalls-before<=2);assertTrue(driver.uploadedBytes-bytes<=1_048_576);}
        Map<DhGeographicLodData.TileKey,FakeMesh> published(){view.draw(new Matrix4f(),0,1000,0,RESOURCES);return driver.lastDraw;}
        public void close(){try{view.close();}finally{workers.shutdownNow();try{assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}}}
    }
    private static SceneReadyCube ready(int x,int y,int z){return new SceneReadyCube(new CubePos(x,y,z),WORLD,EPOCH,11,7,RESOURCES);}
    private static long number(DhPreparedLodView view,String key){return view.diagnostics().get(key).getAsLong();}
    private static boolean flag(DhPreparedLodView view,String key){return view.diagnostics().get(key).getAsBoolean();}
    private static void until(BooleanSupplier done,Runnable step){long stop=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(!done.getAsBoolean()){if(System.nanoTime()>stop)fail("Timed out");step.run();try{Thread.sleep(1);}catch(InterruptedException e){throw new AssertionError(e);}}}
    private static void await(CountDownLatch latch){try{assertTrue(latch.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}}

    @Test void coverageCapabilityChangeLatchesFailureAndCannotResumeByReturningOldFlag(){
        var supported=new AtomicBoolean(true);
        var source=new Sampler(){
            public boolean supportsFootprintCoverage(){return supported.get();}
            public boolean knownFootprint(long x,long z,long xx,long zz){return true;}
        };
        try(var h=new Harness(source,null)){
            h.initialize();h.commit(0,List.of(ready(0,62,0)));var shown=h.view.retainPresentation();
            var ticket=h.view.prepareCoverage(1,List.of(ready(0,62,0)),64,0);
            until(()->h.view.isPrepared(ticket),h::pump);supported.set(false);
            assertThrows(IllegalStateException.class,()->h.view.commitPrepared(ticket));supported.set(true);
            assertFalse(h.view.commitPrepared(ticket));assertFalse(h.view.isPresentationCurrent(shown));
            assertTrue(flag(h.view,"invalidated"));assertFalse(h.view.coverageReady(0,0,0));
        }
    }

    @Test void shiftedCenterIsPrivateUntilCommitAndExactHandshakeIncludesBothAxes(){try(var h=new Harness()){
        h.initialize();var mask=List.of(ready(0,62,0));h.commit(0,mask);var old=h.published();
        var before=h.view.diagnostics().get("coverageIdentity").getAsString();
        long samples=h.sampler.heightCalls.get();var ticket=h.view.prepareCoverage(1,mask,64,-64);
        assertFalse(flag(h.view,"pendingSameGeometry"));assertEquals(64,number(h.view,"pendingCenterX"));assertEquals(-64,number(h.view,"pendingCenterZ"));
        until(()->h.view.isPrepared(ticket),h::pump);
        assertTrue(h.view.coverageReady(0,0,0));assertFalse(h.view.coverageReady(1,64,-64));assertEquals(old,h.published());
        assertEquals(0,number(h.view,"publishedCenterX"));assertEquals(before,h.view.diagnostics().get("coverageIdentity").getAsString());
        assertTrue(h.view.commitPrepared(ticket));assertTrue(h.view.coverageReady(1,64,-64));
        assertFalse(h.view.coverageReady(1,0,-64));assertFalse(h.view.coverageReady(1,64,0));assertFalse(h.view.coverageReady(0,64,-64));
        assertNotEquals(before,h.view.diagnostics().get("coverageIdentity").getAsString());assertEquals(1,number(h.view,"recenterCommits"));
        assertTrue(h.sampler.heightCalls.get()>samples);assertEquals(0,number(h.view,"sameGeometryPreparations"));
    }}
    @Test void canonicalCenterIdempotenceDoesNotAdmitSameGenerationDifferentFootprint(){try(var h=new Harness()){
        h.initialize();var mask=List.of(ready(0,62,0));var ticket=h.view.prepareCoverage(0,mask,79,-49);
        assertSame(ticket,h.view.prepareCoverage(0,mask,64,-64));assertSame(ticket,h.view.prepareCoverage(0,mask,65,-63));
        assertThrows(IllegalArgumentException.class,()->h.view.prepareCoverage(0,mask,80,-64));
        assertThrows(IllegalArgumentException.class,()->h.view.prepareCoverage(0,mask,64,-65));
        assertTrue(h.view.isCurrent(ticket));until(()->h.view.isPrepared(ticket),h::pump);assertTrue(h.view.commitPrepared(ticket));
        assertTrue(h.view.coverageReady(0,79,-49));assertEquals(0,number(h.view,"recenterCommits"));
        assertEquals(64,number(h.view,"publishedCenterX"));assertEquals(-64,number(h.view,"publishedCenterZ"));
    }}
    @Test void overflowRejectsBeforeCancellingCurrentTicketOrConsumingGeneration(){try(var h=new Harness()){
        h.initialize();var mask=List.of(ready(0,62,0));var ticket=h.view.prepareCoverage(0,mask);
        for(long x:new long[]{Long.MIN_VALUE,Long.MIN_VALUE+511,Long.MAX_VALUE,Long.MAX_VALUE-511}){
            assertThrows(ArithmeticException.class,()->h.view.prepareCoverage(1,mask,x,0));
            assertThrows(ArithmeticException.class,()->h.view.prepareCoverage(1,mask,0,x));
            assertTrue(h.view.isCurrent(ticket));assertEquals(0,number(h.view,"highestRequestedCoverageGeneration"));
        }
        until(()->h.view.isPrepared(ticket),h::pump);assertTrue(h.view.commitPrepared(ticket));
        var good=h.view.prepareCoverage(1,mask,64,0);until(()->h.view.isPrepared(good),h::pump);assertTrue(h.view.commitPrepared(good));
        assertTrue(h.view.coverageReady(1,64,0));
    }}
    @Test void legacyPreparationReturnsToConstructorCenterAfterExplicitMove(){try(var h=new Harness()){
        h.initialize();var mask=List.of(ready(0,62,0));var moved=h.view.prepareCoverage(0,mask,128,64);
        until(()->h.view.isPrepared(moved),h::pump);assertTrue(h.view.commitPrepared(moved));
        var legacy=h.view.prepareCoverage(1,mask);assertEquals(0,number(h.view,"pendingCenterX"));assertEquals(0,number(h.view,"pendingCenterZ"));
        assertFalse(flag(h.view,"pendingSameGeometry"));until(()->h.view.isPrepared(legacy),h::pump);assertTrue(h.view.commitPrepared(legacy));
        assertTrue(h.view.coverageReady(1,0,0));assertEquals(1,number(h.view,"recenterCommits"));
    }}
    @Test void overlapUsesAbsoluteTileFacesAndReturnsToExactOriginalGeometryWithinCaps(){try(var h=new Harness()){
        h.initialize();var mask=List.of(ready(0,62,0));h.commit(0,mask);var original=h.published();
        var expected=new LinkedHashMap<DhGeographicLodData.TileKey,List<DhTerrainGeometry.PageFace>>();original.forEach((key,mesh)->expected.put(key,mesh.faces));
        long generation=1,totalAliases=0;
        for(long[] center:List.of(new long[]{64,0},new long[]{128,-64},new long[]{-64,64},new long[]{0,0})){
            var old=h.published();var ticket=h.view.prepareCoverage(generation,mask,center[0],center[1]);
            until(()->h.view.isPrepared(ticket),h::pump);assertTrue(h.view.commitPrepared(ticket));assertTrue(h.view.coverageReady(generation++,center[0],center[1]));
            var now=h.published();for(var entry:now.entrySet())if(old.containsKey(entry.getKey())&&old.get(entry.getKey()).faces.equals(entry.getValue().faces)){
                assertSame(old.get(entry.getKey()),entry.getValue());totalAliases++;
            }
            assertTrue(number(h.view,"residentPageTileOwners")<=128);assertTrue(number(h.view,"pageTileOwners")<=64);
            assertTrue(number(h.view,"residentBufferBytes")<=8L*1024*1024);assertTrue(number(h.view,"summaryCachedBytes")<=20L*1024*1024);
        }
        assertTrue(totalAliases>0);var returned=h.published();assertEquals(expected.keySet(),returned.keySet());expected.forEach((key,faces)->assertEquals(faces,returned.get(key).faces));
        assertEquals(4,number(h.view,"recenterCommits"));assertEquals(0,number(h.view,"sameGeometryPreparations"));
        h.view.close();assertEquals(0,h.driver.live());assertTrue(flag(h.view,"ownedGlObjectsDeleted"));assertEquals(0,number(h.view,"residentBufferBytes"));
        assertEquals(0,number(h.view,"publishedCenterX"));assertEquals(0,number(h.view,"publishedCenterZ"));
    }}
    @Test void currentMovedCenterStillAllowsFreshAuthorityOnlyPreparation(){try(var h=new Harness()){
        h.initialize();var a=ready(0,62,0);var initial=h.view.prepareCoverage(0,List.of(a),-64,128);
        until(()->h.view.isPrepared(initial),h::pump);assertTrue(h.view.commitPrepared(initial));
        long samples=h.sampler.heightCalls.get();int compile=h.driver.compileCalls.get(),uploads=h.driver.uploadCalls;
        var next=new SceneReadyCube(a.position(),WORLD,EPOCH,72,81,RESOURCES);var ticket=h.view.prepareCoverage(1,List.of(next),-64,128);
        assertTrue(flag(h.view,"pendingSameGeometry"));h.pump();assertTrue(h.view.commitPrepared(ticket));assertTrue(h.view.coverageReady(1,-64,128));
        assertEquals(samples,h.sampler.heightCalls.get());assertEquals(compile,h.driver.compileCalls.get());assertEquals(uploads,h.driver.uploadCalls);
        assertEquals(1,number(h.view,"sameGeometryCommits"));assertEquals(0,number(h.view,"recenterCommits"));
    }}

    @Test void sameXyzNewAuthorityUsesCurrentGeometryWithoutSamplingCompilationOrNewAllocation(){try(var h=new Harness()){
        h.initialize();var original=ready(0,62,0);h.commit(0,List.of(original));
        var old=h.published();var shown=h.view.retainPresentation();String before=h.view.diagnostics().get("coverageIdentity").getAsString();
        long samples=h.sampler.heightCalls.get(),compiled=h.driver.compileCalls.get(),bytes=number(h.view,"summaryCachedBytes");
        int allocated=h.driver.allocated.size();long cached=number(h.view,"summaryCachedItems");
        var replacement=new SceneReadyCube(original.position(),WORLD,EPOCH,99,123,RESOURCES);
        var ticket=h.view.prepareCoverage(1,List.of(replacement));
        assertTrue(flag(h.view,"pendingSameGeometry"));assertFalse(h.view.isPrepared(ticket));
        h.pump();assertTrue(h.view.isPrepared(ticket));assertTrue(h.view.coverageReady(0));assertEquals(old,h.published());
        assertTrue(h.view.isPresentationCurrent(shown));assertEquals(before,h.view.diagnostics().get("coverageIdentity").getAsString());
        assertTrue(h.view.commitPrepared(ticket));assertFalse(h.view.isPresentationCurrent(shown));assertFalse(h.view.commitPrepared(ticket));
        assertTrue(h.view.coverageReady(1));assertNotEquals(before,h.view.diagnostics().get("coverageIdentity").getAsString());
        assertEquals(samples,h.sampler.heightCalls.get());assertEquals(compiled,h.driver.compileCalls.get());
        assertEquals(allocated,h.driver.allocated.size());assertEquals(old,h.published());
        assertEquals(bytes,number(h.view,"summaryCachedBytes"));assertEquals(cached,number(h.view,"summaryCachedItems"));
        assertEquals(1,number(h.view,"sameGeometryPreparations"));assertEquals(1,number(h.view,"sameGeometryCommits"));
        assertFalse(flag(h.view,"sameGeometryReusesAuthority"));assertFalse(flag(h.view,"sameGeometryAddsPayloadBytes"));
    }}
    @Test void canonicalOrderReusesGeometryButEveryChangedXyzVolumeUsesWorker(){try(var h=new Harness()){
        h.initialize();var a=ready(0,62,0);var b=ready(1,62,0);h.commit(0,List.of(a,b));
        var reorder=h.view.prepareCoverage(1,List.of(b,a));assertTrue(flag(h.view,"pendingSameGeometry"));h.pump();assertTrue(h.view.commitPrepared(reorder));
        long generation=2;long hits=number(h.view,"sameGeometryPreparations");
        for(var mask:List.of(List.of(ready(2,62,0),b),List.of(ready(2,63,0),b),List.of(ready(2,63,1),b),List.of(b),List.<SceneReadyCube>of())){
            int compiled=h.driver.compileCalls.get();var t=h.view.prepareCoverage(generation++,mask);
            assertFalse(flag(h.view,"pendingSameGeometry"));until(()->h.view.isPrepared(t),h::pump);assertTrue(h.view.commitPrepared(t));
            assertTrue(h.driver.compileCalls.get()>compiled);assertEquals(hits,number(h.view,"sameGeometryPreparations"));
        }
    }}
    @Test void sameGeometryStillRejectsForeignWorldEpochResourcesDuplicateAndInvalidLease(){try(var h=new Harness();var other=new Harness()){
        h.initialize();other.initialize();var a=ready(0,62,0);h.commit(0,List.of(a));other.commit(0,List.of(a));
        var changed=new SceneReadyCube(a.position(),WORLD,EPOCH,99,123,RESOURCES);var t=h.prepare(1,List.of(changed));
        assertFalse(other.view.isCurrent(t));assertFalse(other.view.isPrepared(t));assertFalse(other.view.commitPrepared(t));other.view.cancelPrepared(t);assertTrue(h.view.isPrepared(t));
        for(var bad:List.of(new SceneReadyCube(a.position(),WORLD,EPOCH+1,99,123,RESOURCES),new SceneReadyCube(a.position(),WORLD,EPOCH,99,123,RESOURCES+1),
                new SceneReadyCube(a.position(),new WorldIdentity(18,"scene","1",IDENTITY,"native-palette"),EPOCH,99,123,RESOURCES)))
            assertThrows(IllegalArgumentException.class,()->h.view.prepareCoverage(2,List.of(bad)));
        assertThrows(IllegalArgumentException.class,()->new SceneReadyCube(a.position(),WORLD,EPOCH,0,123,RESOURCES));
        assertThrows(IllegalArgumentException.class,()->new SceneReadyCube(a.position(),WORLD,EPOCH,99,-1,RESOURCES));
        assertThrows(IllegalArgumentException.class,()->h.view.prepareCoverage(2,List.of(changed,changed)));
        assertTrue(h.view.isPrepared(t));assertTrue(h.view.commitPrepared(t));assertEquals(1,number(h.view,"sameGeometryCommits"));
    }}
    @Test void sourceMismatchAfterReuseStagingLatchesAndCannotResumeByReturningOldIdentity(){try(var h=new Harness()){
        h.initialize();h.commit(0,List.of(ready(0,62,0)));var shown=h.view.retainPresentation();
        var t=h.prepare(1,List.of(new SceneReadyCube(new CubePos(0,62,0),WORLD,EPOCH,12,8,RESOURCES)));
        h.sampler.identity.set("different-policy");assertThrows(IllegalStateException.class,()->h.view.commitPrepared(t));
        h.sampler.identity.set(IDENTITY);assertFalse(h.view.commitPrepared(t));assertFalse(h.view.isPresentationCurrent(shown));
        assertEquals(0,number(h.view,"sameGeometryCommits"));assertTrue(flag(h.view,"invalidated"));
    }}
    @Test void hundredsOfFreshAuthorityTicketsAddNoPayloadCacheEntriesOrMeshOwners(){try(var h=new Harness()){
        h.initialize();h.commit(0,List.of(ready(0,62,0)));var old=h.published();
        long samples=h.sampler.heightCalls.get(),bytes=number(h.view,"summaryCachedBytes"),cached=number(h.view,"summaryCachedItems");int allocated=h.driver.allocated.size(),compiled=h.driver.compileCalls.get();
        DhPreparedLodView.CoverageTicket previous=null;
        for(int i=1;i<=200;i++){
            var t=h.view.prepareCoverage(i,List.of(new SceneReadyCube(new CubePos(0,62,0),WORLD,EPOCH,11+i,7+i,RESOURCES)));
            if(previous!=null){assertFalse(h.view.isCurrent(previous));assertFalse(h.view.commitPrepared(previous));}
            h.pump();assertTrue(h.view.isPrepared(t));
            if(i%3==0){h.view.cancelPrepared(t);h.pump();assertFalse(h.view.commitPrepared(t));}
            else assertTrue(h.view.commitPrepared(t));
            assertEquals(old,h.published());assertEquals(allocated,h.driver.allocated.size());previous=t;
        }
        assertEquals(samples,h.sampler.heightCalls.get());assertEquals(compiled,h.driver.compileCalls.get());assertEquals(bytes,number(h.view,"summaryCachedBytes"));assertEquals(cached,number(h.view,"summaryCachedItems"));
        assertEquals(200,number(h.view,"sameGeometryPreparations"));assertEquals(134,number(h.view,"sameGeometryCommits"));
        h.view.close();assertEquals(0,h.driver.live());assertEquals(0,number(h.view,"residentBufferBytes"));
    }}
    @Test void sameGeometryCancellationPreservesOldPresentationUntilSuccessfulReplacement(){try(var h=new Harness()){
        h.initialize();h.commit(0,List.of(ready(0,62,0)));var shown=h.view.retainPresentation();var old=h.published();
        var a=h.view.prepareCoverage(1,List.of(ready(0,62,0)));h.view.cancelPrepared(a);h.pump();assertFalse(h.view.isPrepared(a));
        var b=h.prepare(2,List.of(new SceneReadyCube(new CubePos(0,62,0),WORLD,EPOCH,12,8,RESOURCES)));h.view.cancelPrepared(b);h.pump();
        assertTrue(h.view.isPresentationCurrent(shown));assertEquals(old,h.published());assertFalse(h.view.commitPrepared(b));
        var c=h.prepare(3,List.of(ready(0,62,0)));assertTrue(h.view.commitPrepared(c));assertFalse(h.view.isPresentationCurrent(shown));
        assertEquals(1,number(h.view,"presentationReleases"));assertEquals(1,number(h.view,"sameGeometryCommits"));
    }}
    @Test void failedSameGeometryCommitCaptureOrRestoreFencesAndRetainsCleanupOwners(){
        for(boolean capture:new boolean[]{true,false})try(var h=new Harness()){
            h.initialize();h.commit(0,List.of(ready(0,62,0)));var t=h.prepare(1,List.of(ready(0,62,0)));h.view.retainPresentation();
            if(capture)h.driver.failCapture=true;else h.driver.failNextRestore=true;
            assertThrows(InjectedFailure.class,()->h.view.commitPrepared(t));h.driver.failCapture=false;
            assertTrue(flag(h.view,"invalidated"));assertEquals(0,number(h.view,"sameGeometryCommits"));assertFalse(flag(h.view,"presentationRetained"));
            h.view.close();assertEquals(0,h.driver.live());assertTrue(flag(h.view,"ownedGlObjectsDeleted"));
        }
    }
    @Test void sameGeometryCommitReentrantCancellationOrReloadCannotClaimNewAuthority(){
        for(boolean reload:new boolean[]{false,true})try(var h=new Harness()){
            h.initialize();h.commit(0,List.of(ready(0,62,0)));var t=h.prepare(1,List.of(ready(0,62,0)));
            h.driver.onRestore=()->{if(reload)h.view.requestInvalidation();else h.view.cancelPrepared(t);};
            assertThrows(IllegalStateException.class,()->h.view.commitPrepared(t));h.driver.onRestore=()->{};
            assertEquals(0,number(h.view,"sameGeometryCommits"));assertEquals(0,number(h.view,"coverageGeneration"));assertTrue(flag(h.view,"invalidated"));
        }
    }

    @Test void firstFenceDistinguishesPreparedAliasFromActualBackgroundOrUploadWork(){try(var h=new Harness()){
        h.initialize();h.commit(0,List.of(ready(0,62,0)));var t=h.prepare(1,List.of(ready(0,62,0)));
        assertTrue(h.view.isPrepared(t));h.view.requestInvalidation();
        assertTrue(flag(h.view,"firstFencePendingSameGeometry"));assertTrue(flag(h.view,"firstFencePendingGpuStage"));
        assertFalse(flag(h.view,"firstFencePendingCpu"));assertEquals(0,number(h.view,"firstFencePrivateOrRetiredOwners"));
        assertFalse(h.view.commitPrepared(t));h.view.close();assertTrue(flag(h.view,"preClosePendingSameGeometry"));
        assertEquals(0,number(h.view,"residentBufferBytes"));assertEquals(0,number(h.view,"summaryCachedBytes"));
    }}

    @Test void initializationDoesNotReadUploadDrawOrPublishAndPumpNeverCommits(){try(var h=new Harness()){
        h.initialize();for(int i=0;i<5;i++)h.pump();assertTrue(h.sampler.readers.isEmpty());assertEquals(0,h.driver.uploadCalls);assertFalse(h.view.hasPublishedGeometry());
        var ticket=h.prepare(0,List.of());assertFalse(h.view.coverageReady(0));assertEquals(-1,number(h.view,"coverageGeneration"));
        for(int i=0;i<3;i++)h.pump();h.published();assertEquals(0,h.driver.drawCalls);assertEquals(0,number(h.view,"commits"));
        assertTrue(h.view.commitPrepared(ticket));assertTrue(h.view.coverageReady(0));assertFalse(h.view.commitPrepared(ticket));
        assertTrue(h.sampler.readers.stream().allMatch(t->t!=Thread.currentThread()));assertTrue(h.driver.compilerThreads.stream().allMatch(t->t!=Thread.currentThread()));
        assertEquals(0,h.driver.legacyUploadCalls);assertEquals(0,number(h.view,"sourceEditRevision"));
    }}
    @Test void entireOldPairStaysVisibleUntilExplicitCommitAndExact18XyzMaskIsApplied(){try(var h=new Harness()){
        h.initialize();h.commit(0,List.of());var old=h.published();var mask=new ArrayList<SceneReadyCube>();
        for(int y=61;y<=62;y++)for(int z=-1;z<=1;z++)for(int x=-1;x<=1;x++)mask.add(ready(x,y,z));
        var ticket=h.view.prepareCoverage(1,mask);assertSame(ticket,h.view.prepareCoverage(1,List.copyOf(mask)));
        until(()->h.view.isPrepared(ticket),()->{h.pump();assertEquals(old,h.published());assertTrue(old.values().stream().noneMatch(FakeMesh::deleted));assertTrue(h.view.coverageReady(0));});
        assertEquals(old,h.published());assertFalse(h.view.coverageReady(1));assertTrue(h.view.commitPrepared(ticket));assertEquals(18,number(h.view,"nearReadyCubes"));
        var expected=new DhAdaptiveTerrainData(new Sampler(),0,0).clip(mask.stream().map(SceneReadyCube::position).toList());var actual=h.published();
        assertEquals(expected.tiles().keySet(),actual.keySet());expected.tiles().forEach((key,faces)->assertEquals(faces,actual.get(key).faces));
        assertEquals(0,number(h.view,"recenterCommits"));assertTrue(number(h.view,"reusedTileOwners")>0);
    }}
    @Test void allTicketIdentityGenerationsCancellationAndCrossViewAbaAreFenced(){try(var a=new Harness();var b=new Harness()){
        a.initialize();b.initialize();var t=a.prepare(0,List.of(ready(0,62,0)));
        assertTrue(a.view.isCurrent(t));assertFalse(b.view.isCurrent(t));assertFalse(b.view.isPrepared(t));assertFalse(b.view.commitPrepared(t));b.view.cancelPrepared(t);assertTrue(a.view.isPrepared(t));
        a.view.cancelPrepared(t);assertFalse(a.view.isCurrent(t));assertFalse(a.view.isPrepared(t));assertFalse(a.view.commitPrepared(t));a.pump();assertEquals(0,a.driver.live());
        assertThrows(IllegalArgumentException.class,()->a.view.prepareCoverage(0,List.of(ready(0,62,0))));
        var next=a.prepare(1,List.of(ready(0,62,0)));assertNotSame(t,next);assertFalse(a.view.commitPrepared(t));assertTrue(a.view.commitPrepared(next));assertFalse(a.view.isCurrent(next));
        assertThrows(IllegalArgumentException.class,()->a.view.prepareCoverage(1,List.of()));assertThrows(IllegalArgumentException.class,()->a.view.prepareCoverage(-1,List.of()));
        assertTrue(number(a.view,"summaryCacheHits")>0);
    }}
    @Test void nearRevisionsLeasesAndEpochAreExactButDoNotInventBaseSourceRevision(){try(var h=new Harness()){
        h.initialize();var original=ready(0,62,0);var t=h.prepare(0,List.of(original));
        var changed=new SceneReadyCube(original.position(),WORLD,EPOCH,12,99,RESOURCES);
        assertThrows(IllegalArgumentException.class,()->h.view.prepareCoverage(0,List.of(changed)));
        assertTrue(h.view.commitPrepared(t));String old=h.view.diagnostics().get("coverageIdentity").getAsString();
        h.commit(1,List.of(changed));assertNotEquals(old,h.view.diagnostics().get("coverageIdentity").getAsString());assertEquals(0,number(h.view,"sourceEditRevision"));
        for(var cube:List.of(new SceneReadyCube(original.position(),WORLD,EPOCH+1,11,7,RESOURCES),new SceneReadyCube(original.position(),WORLD,EPOCH,11,7,RESOURCES+1),new SceneReadyCube(original.position(),new WorldIdentity(18,"scene","1",IDENTITY,"native-palette"),EPOCH,11,7,RESOURCES)))
            assertThrows(IllegalArgumentException.class,()->h.view.prepareCoverage(2,List.of(cube)));
        assertThrows(IllegalArgumentException.class,()->h.view.prepareCoverage(2,List.of(original,original)));
        assertThrows(IllegalArgumentException.class,()->h.view.prepareCoverage(2,List.of(ready(0,-689,0))));assertThrows(IllegalArgumentException.class,()->h.view.prepareCoverage(2,List.of(ready(0,554,0))));
        assertThrows(IllegalArgumentException.class,()->h.view.prepareCoverage(2,java.util.Collections.nCopies(257,original)));
        h.commit(2,List.of(ready(-1,-688,-1),ready(1,553,1)));assertTrue(h.view.coverageReady(2));
    }}
    @Test void cancelledIgnoringCompilerRetainsPhysicalSlotAndLatePayloadCannotUpload(){try(var h=new Harness()){
        h.initialize();h.commit(0,List.of());int uploads=h.driver.uploadCalls;var gate=new Gate();h.driver.blockNextCompile.set(gate);h.driver.honorCompilerCancellation=false;
        var old=h.view.prepareCoverage(1,List.of(ready(0,62,0)));await(gate.entered);
        try{h.view.cancelPrepared(old);var next=h.view.prepareCoverage(2,List.of(ready(1,62,0)));assertEquals(1,number(h.view,"summaryRunning"));assertEquals(1,number(h.view,"summaryQueued"));
            assertFalse(h.view.commitPrepared(old));h.pump();assertEquals(uploads,h.driver.uploadCalls);gate.release.countDown();until(()->h.view.isPrepared(next),h::pump);assertTrue(h.view.commitPrepared(next));
        }finally{gate.release.countDown();}
    }}
    @Test void cancellationDuringPartialUploadRetainsCurrentAndCleansOnlyPrivateOwners(){try(var h=new Harness()){
        h.initialize();h.commit(0,List.of());var old=h.published();int allocated=h.driver.allocated.size();
        var ticket=h.view.prepareCoverage(1,List.of(ready(0,62,0)));until(()->h.driver.allocated.size()>allocated,h::pump);
        h.view.cancelPrepared(ticket);assertFalse(h.view.isPrepared(ticket));h.pump();assertEquals(old,h.published());
        assertTrue(h.driver.allocated.subList(allocated,h.driver.allocated.size()).stream().allMatch(FakeMesh::deleted));assertEquals(old.size(),h.driver.live());
    }}
    @Test void cancellationAndInvalidationInsideUploadOrCommitCallbacksNeverClaimPublication(){
        for(boolean commitPhase:new boolean[]{false,true})try(var h=new Harness()){
            h.initialize();h.commit(0,List.of());var t=h.view.prepareCoverage(1,List.of(ready(0,62,0)));
            if(commitPhase)until(()->h.view.isPrepared(t),h::pump);
            h.driver.onRestore=()->h.view.cancelPrepared(t);
            if(commitPhase){assertThrows(IllegalStateException.class,()->h.view.commitPrepared(t));assertTrue(flag(h.view,"invalidated"));}
            else{int before=h.driver.uploadCalls;until(()->h.driver.uploadCalls>before,h::pump);assertFalse(h.view.isPrepared(t));assertFalse(h.view.commitPrepared(t));assertTrue(h.view.coverageReady(0));}
            h.driver.onRestore=()->{};
        }
        try(var h=new Harness()){h.initialize();h.commit(0,List.of());var t=h.prepare(1,List.of(ready(0,62,0)));h.driver.onRestore=h.view::requestInvalidation;
            assertThrows(IllegalStateException.class,()->h.view.commitPrepared(t));assertFalse(h.view.coverageReady(1));assertEquals(0,number(h.view,"coverageGeneration"));h.driver.onRestore=()->{};
        }
    }
    @Test void sourceIdentityMismatchLatchesInvalidationEvenAfterReturnToOldIdentity(){try(var h=new Harness()){
        h.initialize();var t=h.prepare(0,List.of());h.sampler.identity.set("changed");assertThrows(IllegalStateException.class,()->h.view.isPrepared(t));h.sampler.identity.set(IDENTITY);
        assertFalse(h.view.commitPrepared(t));assertTrue(flag(h.view,"invalidated"));assertFalse(h.view.hasPublishedGeometry());
    }}
    @Test void sourceAndCompilerPermanentFailuresThrowAndFenceInsteadOfPendingForever(){
        for(boolean compiler:new boolean[]{false,true})try(var h=new Harness(compiler?new Sampler():new Sampler(){@Override public long surfaceY(long x,long z){throw new IllegalArgumentException("permanent source budget");}},null)){
            h.initialize();if(compiler)h.driver.failNextCompile.set(new InjectedFailure("compiler"));var t=h.view.prepareCoverage(0,List.of());
            assertThrows(RuntimeException.class,()->until(()->h.view.isPrepared(t),h::pump));assertTrue(flag(h.view,"invalidated"));assertFalse(h.view.commitPrepared(t));
        }
    }
    @Test void transientExecutorPressureRetriesAndNeverRunsSourceOnOwner(){AtomicBoolean first=new AtomicBoolean(true);try(var h=new Harness(new Sampler(),executor->r->{if(first.getAndSet(false))throw new java.util.concurrent.RejectedExecutionException("pressure");executor.execute(r);})){h.initialize();h.commit(0,List.of());assertTrue(h.sampler.readers.stream().allMatch(t->t!=Thread.currentThread()));}}
    @Test void uploadRestoreCaptureAndDeletionFailuresFenceAndRetainAllOwnersForCloseRetry(){
        for(int fault=0;fault<3;fault++)try(var h=new Harness()){
            h.initialize();h.commit(0,List.of());var old=h.published();var t=h.view.prepareCoverage(1,List.of(ready(0,62,0)));
            if(fault==0)h.driver.failUpload=h.driver.uploadCalls+2;else if(fault==1)h.driver.failNextRestore=true;else h.driver.failCapture=true;
            assertThrows(InjectedFailure.class,()->until(()->h.view.isPrepared(t),h::pump));assertTrue(flag(h.view,"invalidated"));assertEquals(0,number(h.view,"coverageGeneration"));
            assertTrue(old.values().stream().noneMatch(FakeMesh::deleted));h.driver.failCapture=false;
        }
        try(var h=new Harness()){h.initialize();h.commit(0,List.of());int id=h.driver.allocated.getFirst().id;h.driver.failMeshClose.add(id);assertThrows(InjectedFailure.class,h.view::close);assertEquals(1,h.driver.live());assertFalse(flag(h.view,"ownedGlObjectsDeleted"));h.driver.failMeshClose.clear();h.view.close();assertTrue(flag(h.view,"ownedGlObjectsDeleted"));}
    }
    @Test void retirementFailureAfterCachePublicationNeverReportsCoverageAndRetainsCleanupOwners(){try(var h=new Harness()){
        h.initialize();h.commit(0,List.of());var old=h.published();var t=h.prepare(1,List.of(ready(0,62,0)));old.values().forEach(m->h.driver.failMeshClose.add(m.id));
        assertThrows(InjectedFailure.class,()->h.view.commitPrepared(t));assertEquals(0,number(h.view,"coverageGeneration"));assertTrue(flag(h.view,"invalidated"));assertFalse(h.view.coverageReady(1));h.driver.failMeshClose.clear();h.view.close();assertEquals(0,h.driver.live());
    }}
    @Test void closeDuringIgnoringCompilerPreservesPreCloseSnapshotAndChargesSlotUntilSettlement(){try(var h=new Harness()){
        h.initialize();h.commit(0,List.of());var gate=new Gate();h.driver.blockNextCompile.set(gate);h.driver.honorCompilerCancellation=false;var t=h.view.prepareCoverage(1,List.of(ready(0,62,0)));await(gate.entered);int uploads=h.driver.uploadCalls;
        try{h.view.requestInvalidation();assertTrue(flag(h.view,"firstFencePendingCpu"));assertEquals(1,number(h.view,"firstFenceSummaryRunning"));assertEquals(1,number(h.view,"firstFencePendingCoverageGeneration"));h.view.close();assertEquals(1,number(h.view,"preClosePendingCoverageGeneration"));assertFalse(flag(h.view,"preClosePendingCpu"));assertTrue(flag(h.view,"firstFencePendingCpu"));assertEquals(1,number(h.view,"summaryRunning"));assertTrue(flag(h.view,"ownedGlObjectsDeleted"));assertFalse(h.view.commitPrepared(t));}
        finally{gate.release.countDown();}until(()->number(h.view,"summaryRunning")==0,h::pump);assertEquals(uploads,h.driver.uploadCalls);assertEquals(0,number(h.view,"summaryCachedBytes"));
    }}
    @Test void initializationFailureRetainsDriverOwnerUntilCloseCanRetry(){try(var h=new Harness()){
        h.driver.failInitialize=true;h.driver.failGlobalClose=true;assertThrows(InjectedFailure.class,h.view::initialize);assertTrue(flag(h.view,"closed"));assertFalse(flag(h.view,"ownedGlObjectsDeleted"));assertEquals(0,h.driver.uploadCalls);
        h.driver.failGlobalClose=false;h.view.close();assertTrue(flag(h.view,"ownedGlObjectsDeleted"));
    }}
    @Test void wrongThreadAndPreInitializationCallsNeverAllocate(){try(var h=new Harness()){
        assertThrows(IllegalStateException.class,()->h.view.prepareCoverage(0,List.of()));assertThrows(IllegalStateException.class,()->new DhPreparedLodView(new Sampler(){@Override public boolean supportsConcurrentReads(){return false;}},WORLD,EPOCH,RESOURCES,0,0,new FakeDriver(),h.workers));
        h.initialize();var failure=new AtomicReference<Throwable>();var thread=new Thread(()->{try{h.view.pump();}catch(Throwable e){failure.set(e);}});thread.start();try{thread.join();}catch(InterruptedException e){throw new AssertionError(e);}assertInstanceOf(IllegalStateException.class,failure.get());assertEquals(0,h.driver.uploadCalls);
    }}
    @Test void changedSourceBetweenPumpAndCommitFailsExplicitlyAndCannotResumeAfterAba(){try(var h=new Harness()){
        h.initialize();var ticket=h.prepare(0,List.of());h.sampler.identity.set("changed");assertThrows(IllegalStateException.class,()->h.view.commitPrepared(ticket));assertTrue(flag(h.view,"invalidated"));h.sampler.identity.set(IDENTITY);assertFalse(h.view.commitPrepared(ticket));
    }}
    @Test void restoreFailureDuringCloseRemainsRetryableAfterPhysicalGlDeletion(){try(var h=new Harness()){
        h.initialize();h.commit(0,List.of());h.driver.failNextRestore=true;assertThrows(InjectedFailure.class,h.view::close);assertEquals(0,h.driver.live());assertFalse(flag(h.view,"ownedGlObjectsDeleted"));assertTrue(flag(h.view,"stateRestorationPending"));int restores=h.driver.restores;
        h.view.close();assertTrue(h.driver.restores>restores);assertTrue(flag(h.view,"ownedGlObjectsDeleted"));assertFalse(flag(h.view,"stateRestorationPending"));
    }}
    @Test void reentrantIdentityInvalidationCannotReuseTicketOrClaimPrepared(){
        AtomicReference<Runnable> callback=new AtomicReference<>(()->{});Sampler sampler=new Sampler(){@Override public String identity(){callback.get().run();return super.identity();}};
        try(var h=new Harness(sampler,null)){h.initialize();var t=h.prepare(0,List.of());callback.set(h.view::requestInvalidation);assertThrows(IllegalStateException.class,()->h.view.isPrepared(t));callback.set(()->{});assertFalse(h.view.commitPrepared(t));}
    }
    @Test void presentationRequiresPublishedBatchAndRetainsOnlyOneVisualTokenWithoutNewOwners(){try(var h=new Harness()){
        assertThrows(IllegalStateException.class,h.view::retainPresentation);h.initialize();
        var pending=h.prepare(0,List.of(ready(0,62,0)));assertThrows(IllegalStateException.class,h.view::retainPresentation);
        assertTrue(h.view.commitPrepared(pending));int owners=h.driver.live(),uploads=h.driver.uploadCalls;
        var token=h.view.retainPresentation();for(int i=0;i<100;i++)assertSame(token,h.view.retainPresentation());
        assertEquals(owners,h.driver.live());assertEquals(uploads,h.driver.uploadCalls);assertEquals(1,number(h.view,"presentationRetains"));
        assertEquals(1,number(h.view,"maxPresentationTickets"));assertEquals(1,number(h.view,"presentationReadyCubes"));assertFalse(flag(h.view,"presentationHasGameplayAuthority"));
        assertTrue(h.view.drawPresented(token,new Matrix4f(),0,1000,0,RESOURCES));assertEquals(1,number(h.view,"presentationFrames"));
    }}
    @Test void presentationReleaseIsIdempotentAndLateOldCloseCannotReleaseNewToken(){try(var h=new Harness()){
        h.initialize();h.commit(0,List.of());var old=h.view.retainPresentation();old.close();old.close();h.view.releasePresentation(old);
        assertFalse(h.view.isPresentationCurrent(old));assertFalse(flag(h.view,"presentationRetained"));assertEquals(1,number(h.view,"presentationReleases"));
        var next=h.view.retainPresentation();assertNotSame(old,next);old.close();assertTrue(h.view.isPresentationCurrent(next));
        int draws=h.driver.drawCalls;assertFalse(h.view.drawPresented(old,new Matrix4f(),0,1000,0,RESOURCES));assertEquals(draws,h.driver.drawCalls);
    }}
    @Test void crossViewAndWrongResourcePresentationNeverDrawOrReleaseAnotherView(){try(var a=new Harness();var b=new Harness()){
        a.initialize();b.initialize();a.commit(0,List.of());b.commit(0,List.of());var token=a.view.retainPresentation();var other=b.view.retainPresentation();
        assertFalse(b.view.isPresentationCurrent(token));b.view.releasePresentation(token);assertTrue(a.view.isPresentationCurrent(token));
        assertFalse(b.view.drawPresented(token,new Matrix4f(),0,1000,0,RESOURCES));assertTrue(b.view.isPresentationCurrent(other));
        int before=a.driver.drawCalls;assertFalse(a.view.drawPresented(token,new Matrix4f(),0,1000,0,RESOURCES+1));assertEquals(before,a.driver.drawCalls);
        assertTrue(a.view.drawPresented(token,new Matrix4f(),0,1000,0,RESOURCES));
    }}
    @Test void presentationSurvivesPrivatePreparationAndCancellationButReplacementRevokesIt(){try(var h=new Harness()){
        h.initialize();h.commit(0,List.of(ready(0,62,0)));var token=h.view.retainPresentation();var old=h.published();
        var ticket=h.view.prepareCoverage(1,List.of(ready(1,62,0)));
        until(()->h.view.isPrepared(ticket),()->{h.pump();assertTrue(h.view.drawPresented(token,new Matrix4f(),0,1000,0,RESOURCES));assertEquals(old,h.driver.lastDraw);});
        h.view.cancelPrepared(ticket);h.pump();assertTrue(h.view.isPresentationCurrent(token));assertTrue(h.view.coverageReady(0));
        h.commit(2,List.of(ready(1,62,0)));assertFalse(h.view.isPresentationCurrent(token));assertFalse(flag(h.view,"presentationRetained"));
        int before=h.driver.drawCalls;assertFalse(h.view.drawPresented(token,new Matrix4f(),0,1000,0,RESOURCES));assertEquals(before,h.driver.drawCalls);
        var next=h.view.retainPresentation();assertNotSame(token,next);token.close();assertTrue(h.view.isPresentationCurrent(next));assertEquals(2,number(h.view,"presentationCoverageGeneration"));
    }}
    @Test void exactRevisionAndLeaseReplacementRevokesPresentationEvenIfGpuFacesAreReused(){try(var h=new Harness()){
        h.initialize();var cube=ready(0,62,0);h.commit(0,List.of(cube));var token=h.view.retainPresentation();int uploads=h.driver.uploadCalls;
        h.commit(1,List.of(new SceneReadyCube(cube.position(),WORLD,EPOCH,99,42,RESOURCES)));
        assertEquals(uploads,h.driver.uploadCalls);assertFalse(h.view.isPresentationCurrent(token));assertTrue(h.view.coverageReady(1));
        var next=h.view.retainPresentation();assertTrue(h.view.isPresentationCurrent(next));assertEquals(1,number(h.view,"presentationCoverageGeneration"));
    }}
    @Test void cancelledIgnoringWorkerCannotReplaceOrRevokeRetainedPresentation(){try(var h=new Harness()){
        h.initialize();h.commit(0,List.of());var presentation=h.view.retainPresentation();var old=h.published();
        var gate=new Gate();h.driver.blockNextCompile.set(gate);h.driver.honorCompilerCancellation=false;var work=h.view.prepareCoverage(1,List.of(ready(0,62,0)));await(gate.entered);
        try{h.view.cancelPrepared(work);h.pump();assertTrue(h.view.drawPresented(presentation,new Matrix4f(),0,1000,0,RESOURCES));assertEquals(old,h.driver.lastDraw);}
        finally{gate.release.countDown();}
        until(()->number(h.view,"summaryRunning")==0,h::pump);assertTrue(h.view.isPresentationCurrent(presentation));assertFalse(h.view.commitPrepared(work));
    }}
    @Test void sourceIdentityMismatchOrExceptionRevokesPresentationPermanently(){
        for(boolean exception:new boolean[]{false,true}){
            AtomicBoolean fault=new AtomicBoolean();Sampler sampler=new Sampler(){@Override public String identity(){if(fault.get())throw new InjectedFailure("source identity");return super.identity();}};
            try(var h=new Harness(sampler,null)){h.initialize();h.commit(0,List.of());var token=h.view.retainPresentation();
                if(exception)fault.set(true);else sampler.identity.set("different-source");
                assertThrows(RuntimeException.class,()->h.view.isPresentationCurrent(token));fault.set(false);sampler.identity.set(IDENTITY);
                assertFalse(h.view.isPresentationCurrent(token));assertFalse(h.view.drawPresented(token,new Matrix4f(),0,1000,0,RESOURCES));assertThrows(IllegalStateException.class,h.view::retainPresentation);
            }
        }
    }
    @Test void reloadInvalidationCapturesThenRevokesPresentationAndClosesEveryOwner(){try(var h=new Harness()){
        h.initialize();h.commit(0,List.of());var token=h.view.retainPresentation();h.view.requestInvalidation();
        assertTrue(flag(h.view,"firstFencePresentationRetained"));assertEquals(0,number(h.view,"firstFencePresentationGeneration"));
        assertFalse(h.view.isPresentationCurrent(token));assertFalse(h.view.drawPresented(token,new Matrix4f(),0,1000,0,RESOURCES));h.view.close();
        assertEquals(0,h.driver.live());assertTrue(flag(h.view,"ownedGlObjectsDeleted"));assertFalse(flag(h.view,"presentationRetained"));token.close();
    }}
    @Test void presentationDrawFaultsRevokeTokenAndRetainFailedCleanupForRetry(){
        for(int fault=0;fault<3;fault++)try(var h=new Harness()){
            h.initialize();h.commit(0,List.of());var token=h.view.retainPresentation();
            if(fault==0)h.driver.failCapture=true;else if(fault==1)h.driver.failDraw=true;else h.driver.failNextRestore=true;
            assertThrows(InjectedFailure.class,()->h.view.drawPresented(token,new Matrix4f(),0,1000,0,RESOURCES));assertFalse(h.view.isPresentationCurrent(token));
            h.driver.failCapture=false;h.driver.failDraw=false;h.view.close();assertTrue(flag(h.view,"ownedGlObjectsDeleted"));
        }
        try(var h=new Harness()){
            h.initialize();h.commit(0,List.of());var token=h.view.retainPresentation();int id=h.driver.allocated.getFirst().id;h.driver.failMeshClose.add(id);
            assertThrows(InjectedFailure.class,h.view::close);assertFalse(h.view.isPresentationCurrent(token));assertFalse(flag(h.view,"ownedGlObjectsDeleted"));
            token.close();h.driver.failMeshClose.clear();h.view.close();assertTrue(flag(h.view,"ownedGlObjectsDeleted"));
        }
    }
    @Test void failedReplacementCannotContinueDrawingOldPresentationAfterPartialCacheCommit(){try(var h=new Harness()){
        h.initialize();h.commit(0,List.of());var token=h.view.retainPresentation();var old=h.published();var work=h.prepare(1,List.of(ready(0,62,0)));
        old.values().forEach(m->h.driver.failMeshClose.add(m.id));assertThrows(InjectedFailure.class,()->h.view.commitPrepared(work));
        assertFalse(h.view.isPresentationCurrent(token));assertFalse(h.view.drawPresented(token,new Matrix4f(),0,1000,0,RESOURCES));h.driver.failMeshClose.clear();
    }}
    @Test void reentrantReleaseDuringSourceOrCaptureCannotDrawRevokedPresentation(){
        AtomicReference<Runnable> callback=new AtomicReference<>(()->{});Sampler sampler=new Sampler(){@Override public String identity(){callback.get().run();return super.identity();}};
        try(var h=new Harness(sampler,null)){h.initialize();h.commit(0,List.of());var token=h.view.retainPresentation();callback.set(token::close);
            assertFalse(h.view.isPresentationCurrent(token));callback.set(()->{});var next=h.view.retainPresentation();h.driver.onCapture=next::close;
            int draws=h.driver.drawCalls,restores=h.driver.restores;assertFalse(h.view.drawPresented(next,new Matrix4f(),0,1000,0,RESOURCES));assertEquals(draws,h.driver.drawCalls);assertEquals(restores+1,h.driver.restores);h.driver.onCapture=()->{};
        }
    }
    @Test void offThreadReleaseLinearizesAfterAlreadyStartedPresentationDraw(){try(var h=new Harness()){
        h.initialize();h.commit(0,List.of());var token=h.view.retainPresentation();var attempted=new CountDownLatch(1);var done=new CountDownLatch(1);
        Thread release=new Thread(()->{attempted.countDown();token.close();done.countDown();});
        h.driver.onDraw=()->{release.start();await(attempted);assertEquals(1,done.getCount());};
        assertTrue(h.view.drawPresented(token,new Matrix4f(),0,1000,0,RESOURCES));await(done);h.driver.onDraw=()->{};assertFalse(h.view.isPresentationCurrent(token));
    }}
    @Test void presentationReadAndDrawStayOwnerThreadButReleaseCanRunOffThread(){try(var h=new Harness()){
        h.initialize();h.commit(0,List.of());var token=h.view.retainPresentation();var failures=new AtomicInteger();
        Thread other=new Thread(()->{for(Runnable action:List.<Runnable>of(()->h.view.retainPresentation(),()->h.view.isPresentationCurrent(token),()->h.view.drawPresented(token,new Matrix4f(),0,1000,0,RESOURCES))){try{action.run();}catch(IllegalStateException expected){failures.incrementAndGet();}}token.close();});
        other.start();try{other.join(5000);}catch(InterruptedException e){throw new AssertionError(e);}assertFalse(other.isAlive());assertEquals(3,failures.get());assertFalse(h.view.isPresentationCurrent(token));
    }}
    @Test void replacementRevokesPresentationBeforeAnyOldResourceRetirementCallback(){try(var h=new Harness()){
        h.initialize();h.commit(0,List.of());var token=h.view.retainPresentation();var next=h.prepare(1,List.of(ready(0,62,0)));var callbacks=new AtomicInteger();
        h.driver.onMeshClose=()->{callbacks.incrementAndGet();assertFalse(h.view.isPresentationCurrent(token));};
        assertTrue(h.view.commitPrepared(next));assertTrue(callbacks.get()>0);h.driver.onMeshClose=()->{};
    }}
    @Test void allPresentationIdentityReadsLatchRuntimeAndErrorFailures(){
        for(boolean error:new boolean[]{false,true})for(int operation=0;operation<3;operation++){
            AtomicReference<Throwable> failure=new AtomicReference<>();Sampler sampler=new Sampler(){@Override public String identity(){Throwable t=failure.get();if(t instanceof Error e)throw e;if(t instanceof RuntimeException e)throw e;return super.identity();}};
            try(var h=new Harness(sampler,null)){h.initialize();h.commit(0,List.of());var token=h.view.retainPresentation();Throwable marker=error?new AssertionError("identity"):new InjectedFailure("identity");failure.set(marker);
                int op=operation;Throwable observed=assertThrows(Throwable.class,()->{if(op==0)h.view.retainPresentation();else if(op==1)h.view.isPresentationCurrent(token);else h.view.drawPresented(token,new Matrix4f(),0,1000,0,RESOURCES);});
                assertSame(marker,observed);failure.set(null);assertFalse(h.view.isPresentationCurrent(token));assertFalse(flag(h.view,"presentationRetained"));assertFalse(h.view.hasPublishedGeometry());
            }
        }
    }
    private static final class InjectedFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        InjectedFailure(String message) { super(message); }
    }
    private static final class FakeMesh implements DhGeographicLodView.Mesh {
        final FakeDriver driver;
        final int id;
        final DhGeographicLodData.TileKey key;
        final List<DhTerrainGeometry.PageFace> faces;
        boolean deleted;
        FakeMesh(FakeDriver driver, int id, DhGeographicLodData.TileKey key, List<DhTerrainGeometry.PageFace> faces) {
            this.driver = driver; this.id = id; this.key = key; this.faces = List.copyOf(faces);
        }
        @Override public int quads() { return faces.size(); }
        @Override public int bytes() { return faces.size() * 64; }
        @Override public void close() {
            driver.checkRenderThread();
            if (deleted) return;
            driver.onMeshClose.run();
            if (driver.failMeshClose.contains(id)) throw new InjectedFailure("mesh close " + id);
            deleted = true;
        }
        @Override public boolean deleted() { return deleted; }
    }
    private static final class FakeDriver implements DhGeographicLodView.Driver {
        final Thread owner = Thread.currentThread();
        final List<FakeMesh> allocated = new ArrayList<>(), partial = new ArrayList<>();
        final Set<Integer> failMeshClose = new HashSet<>();
        final Set<Thread> compilerThreads = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final Map<DhPreparedVertices, List<DhTerrainGeometry.PageFace>> preparedFaces = new java.util.concurrent.ConcurrentHashMap<>();
        final AtomicInteger compileCalls = new AtomicInteger();
        final AtomicLong compiledBytes = new AtomicLong();
        final AtomicReference<Gate> blockNextCompile = new AtomicReference<>();
        final AtomicReference<RuntimeException> failNextCompile = new AtomicReference<>();
        volatile boolean honorCompilerCancellation = true;
        int uploadCalls, drawCalls, restores, failUpload = -1;
        int compilerRequests, preparedUploadCalls, legacyUploadCalls;
        long uploadedBytes;
        boolean globals, globalsDeleted, failInitialize, failGlobalClose, failNextRestore, failDraw, failCapture;
        Runnable onRestore = () -> { }, onCapture=()->{}, onDraw=()->{}, onMeshClose=()->{};
        Map<DhGeographicLodData.TileKey, FakeMesh> lastDraw = Map.of();
        @Override public void checkRenderThread() { assertSame(owner, Thread.currentThread()); }
        @Override public String renderer() { return "fake CPU transaction driver; no actual GL validation"; }
        @Override public DhGeographicLodView.State captureState() {
            checkRenderThread();
            if (failCapture) throw new InjectedFailure("capture");
            onCapture.run();
            return () -> {
                checkRenderThread(); restores++;
                onRestore.run();
                if (failNextRestore) { failNextRestore = false; throw new InjectedFailure("restore"); }
            };
        }
        @Override public void initialize() {
            checkRenderThread(); globals = true;
            if (failInitialize) throw new InjectedFailure("initialize");
        }
        @Override public DhGeographicLodView.CpuCompiler cpuCompiler() {
            checkRenderThread();
            assertTrue(globals && !globalsDeleted, "Initialize the driver before obtaining its CPU-only compiler");
            compilerRequests++;
            return (faces, cancellationCheck) -> {
                assertNotSame(owner, Thread.currentThread(), "CPU vertex packing must run off the owner/render thread");
                compilerThreads.add(Thread.currentThread());
                compileCalls.incrementAndGet();
                if (honorCompilerCancellation) cancellationCheck.run();
                Gate gate = blockNextCompile.getAndSet(null);
                if (gate != null) { gate.entered.countDown(); await(gate.release); }
                if (honorCompilerCancellation) cancellationCheck.run();
                RuntimeException failure = failNextCompile.getAndSet(null);
                if (failure != null) throw failure;
                var snapshot = List.copyOf(faces);
                byte[] bytes = new byte[snapshot.size() * 64];
                java.util.Arrays.fill(bytes, (byte) snapshot.hashCode());
                var prepared = new DhPreparedVertices(bytes);
                java.util.Arrays.fill(bytes, (byte) 0x5a); // A compiler's scratch array must not back the result.
                preparedFaces.put(prepared, snapshot);
                compiledBytes.addAndGet(prepared.byteCount());
                return prepared;
            };
        }
        @Override public DhGeographicLodView.Mesh upload(DhGeographicLodData.TileKey key, List<DhTerrainGeometry.PageFace> faces) {
            legacyUploadCalls++;
            throw new AssertionError("Incremental rendering must use prepared uploads, never legacy CPU packing on the render thread");
        }
        @Override public DhGeographicLodView.Mesh uploadPrepared(DhGeographicLodData.TileKey key,
                List<DhTerrainGeometry.PageFace> faces, DhPreparedVertices prepared) {
            checkRenderThread(); preparedUploadCalls++;
            assertEquals(faces, preparedFaces.get(prepared), "Upload must use the immutable result for exactly these faces");
            assertEquals(faces.size() * 64, prepared.byteCount());
            assertEquals(faces.size(), prepared.quads());
            byte[] expectedBytes = new byte[prepared.byteCount()];
            java.util.Arrays.fill(expectedBytes, (byte) faces.hashCode());
            assertArrayEquals(expectedBytes, prepared.copyBytes());
            return allocate(key, faces);
        }
        private DhGeographicLodView.Mesh allocate(DhGeographicLodData.TileKey key, List<DhTerrainGeometry.PageFace> faces) {
            checkRenderThread(); uploadCalls++;
            var mesh = new FakeMesh(this, allocated.size() + 1, key, faces); allocated.add(mesh);
            assertTrue(live() <= 128);
            try {
                if (uploadCalls == failUpload) throw new InjectedFailure("upload");
                uploadedBytes += mesh.bytes() + mesh.quads() * 24L;
                return mesh;
            } catch (RuntimeException | Error failure) {
                try { mesh.close(); }
                catch (RuntimeException cleanup) { partial.add(mesh); failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        @Override public DhGeographicLodView.DrawStats draw(Map<DhGeographicLodData.TileKey, DhGeographicLodView.Mesh> meshes,
                                                           Matrix4f matrix, double x, double y, double z) {
            checkRenderThread(); drawCalls++;onDraw.run();
            var snapshot = new LinkedHashMap<DhGeographicLodData.TileKey, FakeMesh>();
            meshes.forEach((key, value) -> { assertFalse(value.deleted()); snapshot.put(key, (FakeMesh) value); });
            lastDraw = Map.copyOf(snapshot);
            if (failDraw) throw new InjectedFailure("draw");
            return new DhGeographicLodView.DrawStats(meshes.size(), meshes.values().stream().mapToLong(mesh -> mesh.quads() * 4L).sum(), 123);
        }
        @Override public void close() {
            checkRenderThread(); RuntimeException failure = null;
            for (var iterator = partial.iterator(); iterator.hasNext();) {
                var mesh = iterator.next();
                try { mesh.close(); iterator.remove(); }
                catch (RuntimeException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            }
            if (failGlobalClose) {
                var error = new InjectedFailure("global close");
                if (failure == null) failure = error; else failure.addSuppressed(error);
            } else globalsDeleted = true;
            if (failure != null) throw failure;
        }
        @Override public boolean deleted() { return (!globals || globalsDeleted) && partial.isEmpty(); }
        @Override public int pendingOwners() { return partial.size(); }
        int live() { return (int) allocated.stream().filter(mesh -> !mesh.deleted()).count(); }
    }
}
