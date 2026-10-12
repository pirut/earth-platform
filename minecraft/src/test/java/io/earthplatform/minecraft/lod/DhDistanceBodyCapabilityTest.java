package io.earthplatform.minecraft.lod;

import io.earthplatform.core.CubePos;
import io.earthplatform.geography.earth.EarthAlpineDefinition;
import io.earthplatform.minecraft.coregate.DistanceBodyTestFixture;
import io.earthplatform.minecraft.coregate.RollingDistanceBodyBinding;
import io.earthplatform.minecraft.scene.BodyNativeCompletionFixture;
import io.earthplatform.minecraft.scene.SceneReadyCube;
import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.*;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Actual View, real Alpine source/full125 proof and real replica protocol. Native GPU completion
 * receipts are explicitly modeled by BodyNativeCompletionFixture; far GL is a fake driver.
 * This suite proves authority/lifetime protocol, never installed graphics acceptance. */
@Timeout(90)
class DhDistanceBodyCapabilityTest {
    static final class Mesh implements DhGeographicLodView.Mesh {
        final int faces; boolean deleted;
        Mesh(int faces){this.faces=faces;}
        public int quads(){return faces;}
        public int bytes(){return DhNativeDistanceBudget.storage(faces).vertexBytes();}
        public long totalBytes(){return DhNativeDistanceBudget.storage(faces).chargedBytes();}
        public void close(){deleted=true;}
        public boolean deleted(){return deleted;}
    }
    static final class Driver implements DhGeographicLodView.Driver {
        final Thread owner=Thread.currentThread();
        boolean closed,failClose;
        int uploads,draws,compiles;
        Runnable onCapture=()->{},onRestore=()->{},onDraw=()->{};
        volatile CountDownLatch compilerEntered,compilerRelease;
        public void checkRenderThread(){assertSame(owner,Thread.currentThread());}
        public String renderer(){return "modeled far GL; real body/View protocol only";}
        public DhGeographicLodView.State captureState(){onCapture.run();return ()->onRestore.run();}
        public void initialize(){}
        public DhGeographicLodView.CpuCompiler cpuCompiler(){return (faces,cancel)->{
            assertNotSame(owner,Thread.currentThread());compiles++;
            CountDownLatch entered=compilerEntered,release=compilerRelease;
            if(entered!=null){compilerEntered=null;entered.countDown();await(release);}
            cancel.run();return new DhPreparedVertices(new byte[faces.size()*64]);
        };}
        public DhGeographicLodView.Mesh upload(DhGeographicLodData.TileKey key,List<DhTerrainGeometry.PageFace> faces){throw new AssertionError("prepared bytes required");}
        public DhGeographicLodView.Mesh uploadPrepared(DhGeographicLodData.TileKey key,List<DhTerrainGeometry.PageFace> faces,DhPreparedVertices bytes){
            assertEquals(faces.size(),bytes.quads());uploads++;return new Mesh(faces.size());
        }
        public DhGeographicLodView.DrawStats draw(Map<DhGeographicLodData.TileKey,DhGeographicLodView.Mesh> meshes,Matrix4f matrix,double x,double y,double z){
            assertFalse(meshes.isEmpty());assertTrue(meshes.values().stream().noneMatch(DhGeographicLodView.Mesh::deleted));draws++;onDraw.run();
            return new DhGeographicLodView.DrawStats(meshes.size(),meshes.values().stream().mapToLong(m->m.quads()*4L).sum(),1);
        }
        public void close(){if(failClose)throw new IllegalStateException("retained physical driver");closed=true;}
        public boolean deleted(){return closed;}
    }
    static final class Harness implements AutoCloseable {
        final DistanceBodyTestFixture body=DistanceBodyTestFixture.alpine();
        final Driver driver=new Driver();
        final ExecutorService executor=Executors.newSingleThreadExecutor();
        final DhPreparedLodView view;
        final long before=NativeDistanceCpuBudget.global().evidence().bytes();
        Harness(){
            body.stage(body.pin());body.join();
            view=new DhPreparedLodView(body.sampler(),body.world,1,body.guard.resourceGeneration(),x(),z(),DhLodLayout.EARTH_ALPINE_2048,driver,executor,DhNativeDistanceBudget.configuration());view.initialize();
        }
        long x(){return EarthAlpineDefinition.snapCubeCenter(body.plan.center().x());}
        long z(){return EarthAlpineDefinition.snapCubeCenter(body.plan.center().z());}
        DhPreparedLodView.CoverageTicket prepare(long generation){return view.prepareCoverageDistance(generation,body.joined.exactReady(),x(),z(),body.replacement,body.visual,body.joined);}
        void ready(DhPreparedLodView.CoverageTicket ticket){
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
            while(!view.isPrepared(ticket)&&System.nanoTime()<deadline){view.pump();Thread.yield();}
            assertTrue(view.isPrepared(ticket),()->view.diagnostics().toString());
        }
        void publish(){var ticket=prepare(1);ready(ticket);assertTrue(view.commitPrepared(ticket));body.install();}
        void draw(){view.draw(new Matrix4f(),body.pin().minBlockX()+8,body.pin().minBlockY()+8,body.pin().minBlockZ()+8,body.guard.resourceGeneration());}
        boolean presented(DhPreparedLodView.PresentationTicket ticket){return view.drawPresented(ticket,new Matrix4f(),body.pin().minBlockX()+8,body.pin().minBlockY()+8,body.pin().minBlockZ()+8,body.guard.resourceGeneration());}
        long metric(String key){return view.diagnostics().get(key).getAsLong();}
        public void close(){
            driver.onCapture=()->{};driver.onRestore=()->{};driver.onDraw=()->{};driver.failClose=false;
            if(driver.compilerRelease!=null)driver.compilerRelease.countDown();
            executor.shutdown();try{assertTrue(executor.awaitTermination(60,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}
            try{view.close();assertTrue(view.physicalDrainComplete());assertEquals(0,metric("distanceBodyAliases"));assertEquals(before,NativeDistanceCpuBudget.global().evidence().bytes());}finally{body.close();}
        }
    }
    static void await(CountDownLatch latch){try{assertTrue(latch.await(60,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}}

    @Test void privateCommitDoesNotRequirePublishedHostButActualDrawDoes(){
        try(var h=new Harness()){
            var ticket=h.prepare(1);h.ready(ticket);assertTrue(h.body.joined.isPrepared());assertFalse(h.body.joined.isPublishedCurrent());
            assertTrue(h.view.commitPrepared(ticket));assertTrue(h.view.coverageReady(1,h.x(),h.z(),DhLodLayout.EARTH_ALPINE_2048));
            h.draw();assertEquals(0,h.driver.draws);
            assertThrows(IllegalStateException.class,h.view::retainPresentation);
            h.body.install();assertTrue(h.body.joined.isPublishedCurrent());h.draw();assertEquals(1,h.driver.draws);
        }
    }
    @Test void emptyPartialExtraDuplicateAndForgedMetadataNeverReplaceTheValidRequest(){
        try(var h=new Harness()){
            var ready=h.body.joined.exactReady();var ticket=h.prepare(1);var cases=new ArrayList<List<SceneReadyCube>>();
            cases.add(List.of());cases.add(ready.subList(0,26));
            var duplicate=new ArrayList<>(ready);duplicate.add(ready.getFirst());cases.add(duplicate);
            var extra=new ArrayList<>(ready);var r=ready.getFirst();extra.add(new SceneReadyCube(new CubePos(r.position().x()+5,r.position().y(),r.position().z()),r.world(),r.epoch(),r.leaseGeneration(),r.revision(),r.resourceGeneration()));cases.add(extra);
            var wrongRevision=new ArrayList<>(ready);wrongRevision.set(0,new SceneReadyCube(r.position(),r.world(),r.epoch(),r.leaseGeneration(),r.revision()+1,r.resourceGeneration()));cases.add(wrongRevision);
            var wrongLease=new ArrayList<>(ready);wrongLease.set(0,new SceneReadyCube(r.position(),r.world(),r.epoch(),r.leaseGeneration()+100,r.revision(),r.resourceGeneration()));cases.add(wrongLease);
            for(var proposed:cases){assertThrows(RuntimeException.class,()->h.view.prepareCoverageDistance(2,proposed,h.x(),h.z(),h.body.replacement,h.body.visual,h.body.joined));assertTrue(h.view.isCurrent(ticket));assertEquals(1,h.metric("highestRequestedCoverageGeneration"));}
        }
    }
    @Test void equalReplacementContentCannotSubstituteForExactJoinedWrapper(){
        try(var h=new Harness()){
            var ticket=h.prepare(1);var original=h.body.replacement;
            var other=new DhReplacementInput(original.certificate(),original.data().capture(),new DhReplacementInput.AuthorityGuard(){public boolean isCurrent(DhReplacementInput.Certificate c){return true;}public boolean permitsRetainedPresentation(DhReplacementInput.Certificate c){return true;}});
            assertEquals(original.identity(),other.identity());
            assertThrows(IllegalStateException.class,()->h.view.prepareCoverageDistance(2,h.body.joined.exactReady(),h.x(),h.z(),other,h.body.visual,h.body.joined));
            assertTrue(h.view.isCurrent(ticket));assertEquals(1,h.metric("highestRequestedCoverageGeneration"));
        }
    }
    @Test void equalVisualCoverageCannotSubstituteForExactJoinedNativeWrapper(){
        try(var h=new Harness();var other=BodyNativeCompletionFixture.publication(h.body.plan,h.body.stamp,h.body.live::get)){
            assertEquals(h.body.visual.coverage().identity(),other.coverage().identity());var ticket=h.prepare(1);
            assertThrows(IllegalStateException.class,()->h.view.prepareCoverageDistance(2,h.body.joined.exactReady(),h.x(),h.z(),h.body.replacement,other,h.body.joined));
            assertTrue(h.view.isCurrent(ticket));
        }
    }
    @Test void repeatedPreparationChecksCurrentBodyBeforeReturningTicket(){
        try(var h=new Harness()){
            var ready=h.body.joined.exactReady();var ticket=h.prepare(1);assertSame(ticket,h.prepare(1));h.body.cancel();
            assertFalse(h.view.isCurrent(ticket));
            assertThrows(IllegalStateException.class,()->h.view.prepareCoverageDistance(1,ready,h.x(),h.z(),h.body.replacement,h.body.visual,h.body.joined));
            assertEquals(1,h.metric("highestRequestedCoverageGeneration"));
        }
    }
    @Test void revokedPreparedBodyCannotCommitEvenWhenResourcesAreComplete(){
        try(var h=new Harness()){
            var ticket=h.prepare(1);h.ready(ticket);h.body.cancel();assertFalse(h.view.isPrepared(ticket));assertFalse(h.view.commitPrepared(ticket));assertFalse(h.view.coverageReady(1));assertEquals(0,h.driver.draws);
        }
    }
    @Test void commitCaptureReentrancyCannotPublishARevokedBody(){
        try(var h=new Harness()){
            var ticket=h.prepare(1);h.ready(ticket);h.driver.onCapture=h.body::cancel;
            assertThrows(IllegalStateException.class,()->h.view.commitPrepared(ticket));assertFalse(h.view.coverageReady(1));assertEquals(0,h.driver.draws);
        }
    }
    @Test void drawCaptureReentrancyIsCheckedBeforeAnySubmission(){
        try(var h=new Harness()){
            h.publish();h.driver.onCapture=h.body::invalidateResources;
            assertThrows(IllegalStateException.class,h::draw);assertEquals(0,h.driver.draws);
        }
    }
    @Test void drawAndRestoreRevocationCannotReportSuccessfulCurrentSubmission(){
        for(boolean duringDraw:new boolean[]{true,false})try(var h=new Harness()){
            h.publish();if(duringDraw)h.driver.onDraw=h.body::invalidateResources;else h.driver.onRestore=h.body::invalidateResources;
            assertThrows(IllegalStateException.class,h::draw);assertEquals(1,h.driver.draws);assertFalse(h.view.coverageReady(1));
        }
    }
    @Test void explicitHistoryAloneKeepsOldRevisionPresentableDuringEdit(){
        try(var h=new Harness()){
            h.publish();var old=h.body.joined;
            try(var history=h.body.retainPresentation();var farHistory=h.view.retainPresentationDistance(history)){
                assertSame(farHistory,h.view.retainPresentationDistance(history));h.body.editOne();
                assertFalse(old.isPrepared());assertFalse(old.isPublishedCurrent());assertTrue(history.isPresentable());assertTrue(h.view.isPresentationCurrent(farHistory));
                h.draw();assertEquals(0,h.driver.draws);assertTrue(h.presented(farHistory));assertEquals(1,h.driver.draws);
                history.close();assertFalse(h.view.isPresentationCurrent(farHistory));assertFalse(h.presented(farHistory));assertEquals(1,h.driver.draws);
            }
        }
    }
    @Test void foreignBodyHistoryCannotAuthorizeThisFarPublication(){
        try(var h=new Harness();var other=DistanceBodyTestFixture.alpine()){
            h.publish();other.stage(other.pin());other.join();other.install();
            try(var foreign=other.retainPresentation()){assertThrows(IllegalStateException.class,()->h.view.retainPresentationDistance(foreign));}
            assertFalse(h.view.diagnostics().get("distanceBodyHistoryAlias").getAsBoolean());
        }
    }
    @Test void resourceRevocationStopsBothCurrentAndHistoricalPaths(){
        try(var h=new Harness()){
            h.publish();try(var history=h.body.retainPresentation();var farHistory=h.view.retainPresentationDistance(history)){
                h.body.invalidateResources();assertFalse(h.view.coverageReady(1));assertFalse(h.view.isPresentationCurrent(farHistory));assertFalse(h.presented(farHistory));h.draw();assertEquals(0,h.driver.draws);
            }
        }
    }
    @Test void logicalCancellationKeepsBodyAliasUntilPhysicalWorkerDrain(){
        try(var h=new Harness()){
            h.driver.compilerEntered=new CountDownLatch(1);h.driver.compilerRelease=new CountDownLatch(1);var entered=h.driver.compilerEntered;
            h.prepare(1);await(entered);assertEquals(1,h.metric("distanceBodyAliases"));
            assertThrows(IllegalStateException.class,h.view::close);assertFalse(h.view.physicalDrainComplete());assertEquals(1,h.metric("distanceBodyAliases"));
            assertEquals(h.before+(40L<<20),NativeDistanceCpuBudget.global().evidence().bytes());
            h.driver.compilerRelease.countDown();
        }
    }
    @Test void failedPhysicalCleanupRetainsPublishedBodyAliasAndBudgetForRetry(){
        try(var h=new Harness()){
            h.publish();h.driver.failClose=true;assertThrows(IllegalStateException.class,h.view::close);
            assertFalse(h.view.physicalDrainComplete());assertEquals(1,h.metric("distanceBodyAliases"));assertEquals(h.before+(40L<<20),NativeDistanceCpuBudget.global().evidence().bytes());
            h.driver.failClose=false;h.view.close();assertTrue(h.view.physicalDrainComplete());assertEquals(0,h.metric("distanceBodyAliases"));assertEquals(h.before,NativeDistanceCpuBudget.global().evidence().bytes());
        }
    }
    @Test void sameGeometryReuseBindsFreshCapabilityAndRequiresNewHostInstallation(){
        try(var h=new Harness()){
            h.publish();var old=h.body.joined;var oldVisual=h.body.visual;int uploads=h.driver.uploads,compiles=h.driver.compiles;
            h.body.refreshBindingSameContent();assertNotSame(old,h.body.joined);assertNotSame(oldVisual,h.body.visual);
            assertEquals(oldVisual.coverage().identity(),h.body.visual.coverage().identity());
            var ticket=h.prepare(2);assertSame(ticket,h.prepare(2));h.ready(ticket);
            assertEquals(1,h.metric("sameGeometryPreparations"));assertEquals(uploads,h.driver.uploads);assertEquals(compiles,h.driver.compiles);
            assertTrue(h.view.commitPrepared(ticket));assertTrue(h.view.coverageReady(2));assertFalse(h.body.joined.isPublishedCurrent());
            h.draw();assertEquals(0,h.driver.draws);h.body.install();h.draw();assertEquals(1,h.driver.draws);
            assertFalse(old.isPrepared());assertEquals(1,h.metric("sameGeometryCommits"));
        }
    }
    @Test void sameGeometryAliasDoesNotBorrowOldPublishedBodyAfterCandidateCancellation(){
        try(var h=new Harness()){
            h.publish();var old=h.body.joined;h.body.refreshBindingSameContent();var ticket=h.prepare(2);h.ready(ticket);assertEquals(1,h.metric("sameGeometryPreparations"));
            h.body.cancel();assertFalse(h.view.isCurrent(ticket));assertFalse(h.view.commitPrepared(ticket));
            assertTrue(old.isPublishedCurrent());assertTrue(h.view.coverageReady(1));h.draw();assertEquals(1,h.driver.draws);
        }
    }
    @Test void invalidationWhileHistoricalStateIsCapturedPreventsSubmission(){
        try(var h=new Harness()){
            h.publish();try(var history=h.body.retainPresentation();var farHistory=h.view.retainPresentationDistance(history)){
                h.body.editOne();h.driver.onCapture=history::close;
                assertFalse(h.presented(farHistory));assertEquals(0,h.driver.draws);
            }
        }
    }
    @Test void pureWorkerInputAndResultCannotHoldBodyOrHistoryAuthority(){
        for(var nested:DhPreparedLodView.class.getDeclaredClasses())if(Set.of("Input","Geometry","GeometrySource","ReplacementPayload").contains(nested.getSimpleName())){
            assertTrue(Modifier.isStatic(nested.getModifiers()));
            for(var field:nested.getDeclaredFields()){
                assertFalse(field.getType().getName().contains("RollingDistanceBodyBinding"),field.toString());
                assertNotEquals(io.earthplatform.minecraft.visual.NativeVisualPublication.class,field.getType(),field.toString());
            }
        }
        assertTrue(Modifier.isFinal(RollingDistanceBodyBinding.NativeJoined.class.getModifiers()));
        assertTrue(Arrays.stream(RollingDistanceBodyBinding.NativeJoined.class.getDeclaredConstructors()).allMatch(c->Modifier.isPrivate(c.getModifiers())));
    }
}
