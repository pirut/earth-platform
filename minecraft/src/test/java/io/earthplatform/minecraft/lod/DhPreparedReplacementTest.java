package io.earthplatform.minecraft.lod;

import io.earthplatform.core.*;
import io.earthplatform.minecraft.scene.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.joml.Matrix4f;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure/fake-GL authority, pooled ownership and real asynchronous replacement geometry contracts. */
@Timeout(30)
class DhPreparedReplacementTest {
    static final String SOURCE="replacement-test-flat";
    static final WorldIdentity WORLD=new WorldIdentity(1,"test","1",SOURCE,"palette");
    static final CubePos EDIT=new CubePos(0,62,0);
    static final long EPOCH=4,RESOURCE=2;
    static final class Guard implements DhReplacementInput.AuthorityGuard {
        final Thread owner=Thread.currentThread();boolean current=true,historical=true;int calls;Runnable callback=()->{};
        public boolean isCurrent(DhReplacementInput.Certificate c){assertSame(owner,Thread.currentThread());calls++;callback.run();return current;}
        public boolean permitsRetainedPresentation(DhReplacementInput.Certificate c){assertSame(owner,Thread.currentThread());calls++;callback.run();return historical;}
    }
    static DhReplacementInput input(Guard guard,long revision,String policy){
        var capture=DhVoxelReplacementGeometry.capture(EDIT,(x,y,z)->y<=1000?0xff888888:(x==4&&y==1001&&z==4?0xffdddddd:0),()->{});
        var c=new DhReplacementInput.Certificate(WORLD,EPOCH,RESOURCE,revision+1,EDIT,revision,String.format("%064x",revision+1),"b".repeat(64),policy);
        return new DhReplacementInput(c,capture,guard);
    }
    static DhReplacementInput input(Guard g,long rev){return input(g,rev,DhReplacementInput.ANALYTIC_MATERIAL_POLICY);}
    static List<SceneReadyCube> mask(int centerX){var out=new ArrayList<SceneReadyCube>();for(int x=centerX-1;x<=centerX+1;x++)for(int y=61;y<=63;y++)for(int z=-1;z<=1;z++)out.add(new SceneReadyCube(new CubePos(x,y,z),WORLD,EPOCH,1,0,RESOURCE));return List.copyOf(out);}
    static final class Mesh implements DhGeographicLodView.Mesh {
        final int faces;boolean deleted,failClose;
        Mesh(int faces){this.faces=faces;}
        public int bytes(){return faces*64;}public int quads(){return faces;}
        public void close(){if(failClose)throw new IllegalStateException("close fault");deleted=true;}public boolean deleted(){return deleted;}
    }
    static final class Driver implements DhGeographicLodView.Driver {
        final Thread owner=Thread.currentThread();final List<Mesh> all=new ArrayList<>();int draws,uploads;boolean deleted,failUpload;
        final AtomicReference<CountDownLatch[]> block=new AtomicReference<>();
        public void checkRenderThread(){assertSame(owner,Thread.currentThread());}
        public String renderer(){return "fake replacement test";}public DhGeographicLodView.State captureState(){return ()->{};}
        public void initialize(){}
        public DhGeographicLodView.CpuCompiler cpuCompiler(){return (faces,cancel)->{assertNotSame(owner,Thread.currentThread());var gate=block.getAndSet(null);if(gate!=null){gate[0].countDown();try{assertTrue(gate[1].await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}}cancel.run();return new DhPreparedVertices(new byte[faces.size()*64]);};}
        public DhGeographicLodView.Mesh upload(DhGeographicLodData.TileKey key,List<DhTerrainGeometry.PageFace> faces){throw new AssertionError("owner CPU packing");}
        public DhGeographicLodView.Mesh uploadPrepared(DhGeographicLodData.TileKey key,List<DhTerrainGeometry.PageFace> faces,DhPreparedVertices bytes){if(failUpload)throw new IllegalStateException("upload fault");uploads++;var m=new Mesh(faces.size());all.add(m);return m;}
        public DhGeographicLodView.DrawStats draw(Map<DhGeographicLodData.TileKey,DhGeographicLodView.Mesh> meshes,Matrix4f matrix,double x,double y,double z){draws++;meshes.values().forEach(m->assertFalse(m.deleted()));return new DhGeographicLodView.DrawStats(meshes.size(),0,0);}
        public void close(){deleted=true;}public boolean deleted(){return deleted;}
    }
    static final class Harness implements AutoCloseable {
        final Driver driver=new Driver();final ExecutorService executor=Executors.newSingleThreadExecutor();final AtomicReference<String> source=new AtomicReference<>(SOURCE);final DhPreparedLodView view;
        Harness(){view=new DhPreparedLodView(new SceneSurfaceSampler(){public long surfaceY(long x,long z){return 1000;}public int argb(long x,long z,long y){return 0xff888888;}public String identity(){return source.get();}public boolean supportsConcurrentReads(){return true;}},WORLD,EPOCH,RESOURCE,0,0,driver,executor);view.initialize();}
        DhPreparedLodView.CoverageTicket prepare(long gen,List<SceneReadyCube> ready,DhReplacementInput input){var t=view.prepareCoverage(gen,ready,0,0,input);until(()->view.isPrepared(t)||view.isReplacementDeclined(t),view::pump);return t;}
        void commit(long gen,List<SceneReadyCube> ready,DhReplacementInput input){assertTrue(view.commitPrepared(prepare(gen,ready,input)));}
        long n(String key){return view.diagnostics().get(key).getAsLong();}
        boolean b(String key){return view.diagnostics().get(key).getAsBoolean();}
        void draw(){view.draw(new Matrix4f(),0,1001,0,RESOURCE);}
        public void close(){try{view.close();assertEquals(0,n("residentPageTileOwners"));}finally{executor.shutdownNow();}}
    }
    static void until(BooleanSupplier done,Runnable work){long stop=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(!done.getAsBoolean()){if(System.nanoTime()>stop)fail("timeout");work.run();try{Thread.sleep(1);}catch(InterruptedException e){throw new AssertionError(e);}}}

    @Test void exactCommittedCertificateHandshakeIncludesNoneAndRejectsRevoked(){try(var h=new Harness()){
        assertFalse(h.view.replacementReady(null));h.commit(1,mask(0),null);assertTrue(h.view.replacementReady(null));
        var g=new Guard();var r=input(g,1);h.commit(2,mask(0),r);assertTrue(h.view.replacementReady(r.certificate().identity()));
        assertFalse(h.view.replacementReady(null));assertFalse(h.view.replacementReady("f".repeat(64)));g.current=false;assertFalse(h.view.replacementReady(r.certificate().identity()));
    }}
    @Test void originalReplacementRegionCannotMoveAfterCommit(){try(var h=new Harness()){
        h.commit(1,mask(0),input(new Guard(),1));var other=new CubePos(1,62,0);var r=input(new Guard(),2);var c=r.certificate();
        var moved=new DhReplacementInput(new DhReplacementInput.Certificate(WORLD,EPOCH,RESOURCE,c.summaryGeneration(),other,c.durableRevision(),c.identity(),c.dependencyIdentity(),c.materialPolicy()),DhVoxelReplacementGeometry.capture(other,(x,y,z)->0,()->{}),new Guard());
        assertThrows(IllegalStateException.class,()->h.view.prepareCoverage(2,mask(0),0,0,moved));assertTrue(h.view.coverageReady(1));assertEquals(1,h.n("highestRequestedCoverageGeneration"));
    }}
    @Test void firstPublicationMaskedThenRetirementShowsExactReplacementAndNoExtraRenderer(){try(var h=new Harness()){var r=input(new Guard(),1);h.commit(1,mask(0),r);assertTrue(h.b("farSummarizesEdits"));assertEquals(0,h.n("replacementVisibleFaces"));assertEquals(0,h.n("replacementOwners"));h.commit(2,mask(10),r);assertTrue(h.n("replacementVisibleFaces")>0);assertTrue(h.n("replacementOwners")>0);assertTrue(h.n("pageTileOwners")<=64);assertTrue(h.n("residentPageTileOwners")<=128);h.draw();assertEquals(1,h.driver.draws);}}
    @Test void certificateRemainsCurrentWithoutAnyCaptureLease(){try(var h=new Harness()){var r=input(new Guard(),1);h.commit(1,mask(10),r);h.commit(2,mask(11),r);assertTrue(h.view.coverageReady(2));assertEquals(1,h.n("replacementDurableRevision"));}}
    @Test void pendingReplacementDoesNotChangePublishedAuthorityBeforeAllow(){try(var h=new Harness()){h.commit(1,mask(0),null);var r=input(new Guard(),2);var t=h.prepare(2,mask(0),r);assertFalse(h.b("farSummarizesEdits"));assertTrue(h.view.coverageReady(1));assertFalse(h.view.coverageReady(2));assertTrue(h.view.commitPrepared(t));assertEquals(r.identity(),h.view.diagnostics().get("replacementIdentity").getAsString());assertFalse(h.view.commitPrepared(t));}}
    @Test void revisedCertificateForcesFreshContentAuthorityEvenSameMask(){try(var h=new Harness()){h.commit(1,mask(0),input(new Guard(),1));long before=h.n("sameGeometryPreparations");h.commit(2,mask(0),input(new Guard(),2));assertEquals(before,h.n("sameGeometryPreparations"));assertEquals(2,h.n("replacementDurableRevision"));}}
    @Test void unchangedCertificateCanReuseGeometryButStillNeedsFreshTicket(){try(var h=new Harness()){var r=input(new Guard(),1);h.commit(1,mask(10),r);int before=h.driver.uploads;var t=h.prepare(2,mask(10),r);assertTrue(h.view.coverageReady(1));assertTrue(h.view.commitPrepared(t));assertEquals(before,h.driver.uploads);assertEquals(1,h.n("sameGeometryCommits"));}}
    @Test void editRevocationStopsCurrentButAllowsExplicitHistoricalDisplay(){try(var h=new Harness()){var guard=new Guard();h.commit(1,mask(10),input(guard,1));var old=h.view.retainPresentation();guard.current=false;assertFalse(h.view.coverageReady(1));h.draw();assertEquals(0,h.driver.draws);assertTrue(h.view.drawPresented(old,new Matrix4f(),0,1001,0,RESOURCE));assertEquals(1,h.driver.draws);assertFalse(h.b("invalidated"));}}
    @Test void observedCurrentRevocationCannotAbaBackToCurrent(){try(var h=new Harness()){var guard=new Guard();h.commit(1,mask(0),input(guard,1));guard.current=false;assertFalse(h.view.coverageReady(1));guard.current=true;assertFalse(h.view.coverageReady(1));}}
    @Test void observedHistoricalRevocationCannotAbaBack(){try(var h=new Harness()){var guard=new Guard();h.commit(1,mask(0),input(guard,1));var old=h.view.retainPresentation();guard.historical=false;assertFalse(h.view.isPresentationCurrent(old));guard.historical=true;assertFalse(h.view.isPresentationCurrent(old));}}
    @Test void replacementCommitRevokesOldHistoricalToken(){try(var h=new Harness()){var g=new Guard();h.commit(1,mask(0),input(g,1));var old=h.view.retainPresentation();g.current=false;h.commit(2,mask(0),input(new Guard(),2));assertFalse(h.view.isPresentationCurrent(old));}}
    @Test void revokedPendingAuthorityCannotCommitOrBecomeReady(){try(var h=new Harness()){h.commit(1,mask(0),null);var g=new Guard();var t=h.prepare(2,mask(0),input(g,1));g.current=false;assertFalse(h.view.isPrepared(t));assertFalse(h.view.commitPrepared(t));h.view.pump();assertTrue(h.view.coverageReady(1));assertFalse(h.b("invalidated"));}}
    @Test void unsupportedPolicyDeclinesOnlyWhileFullNativeCoverageAvailable(){try(var h=new Harness()){h.commit(1,mask(0),null);var t=h.prepare(2,mask(0),input(new Guard(),1,"unsupported"));assertTrue(h.view.isReplacementDeclined(t));assertFalse(h.view.isCurrent(t));assertFalse(h.view.commitPrepared(t));assertFalse(h.view.replacementDeclineReason(t).isBlank());assertTrue(h.view.coverageReady(1));assertFalse(h.b("invalidated"));assertThrows(IllegalArgumentException.class,()->h.view.prepareCoverage(2,mask(0),0,0,null));h.commit(3,mask(0),null);}}
    @Test void unsupportedPolicyWithoutNativeCoverageFencesInsteadOfExposingBase(){try(var h=new Harness()){h.commit(1,mask(0),null);assertThrows(IllegalArgumentException.class,()->h.prepare(2,mask(10),input(new Guard(),1,"unsupported")));assertTrue(h.b("invalidated"));assertFalse(h.view.coverageReady(1));}}
    @Test void cannotDropRetiredEditedSummaryWithoutCompleteNativeCoverage(){try(var h=new Harness()){var r=input(new Guard(),1);h.commit(1,mask(10),r);assertThrows(IllegalStateException.class,()->h.view.prepareCoverage(2,List.of(new SceneReadyCube(EDIT,WORLD,EPOCH,1,1,RESOURCE)),0,0,null));assertTrue(h.view.coverageReady(1));assertEquals(1,h.n("highestRequestedCoverageGeneration"));h.commit(2,mask(0),null);assertFalse(h.b("farSummarizesEdits"));}}
    @Test void existingCertificateCanDeclineOnlyUnderFullCandidateNativeCover(){try(var h=new Harness()){h.commit(1,mask(0),input(new Guard(),1));var old=h.view.retainPresentation();var t=h.prepare(2,mask(0),input(new Guard(),2,"unsupported"));assertTrue(h.view.isReplacementDeclined(t));assertTrue(h.view.isPresentationCurrent(old));h.commit(3,mask(0),null);assertFalse(h.view.isPresentationCurrent(old));}}
    @Test void resourceAndSourceFencesInvalidateHistoricalAuthority(){try(var h=new Harness()){h.commit(1,mask(0),input(new Guard(),1));var old=h.view.retainPresentation();assertFalse(h.view.drawPresented(old,new Matrix4f(),0,0,0,RESOURCE+1));h.source.set("changed");assertThrows(IllegalStateException.class,()->h.view.isPresentationCurrent(old));h.source.set(SOURCE);assertFalse(h.view.isPresentationCurrent(old));assertTrue(h.b("invalidated"));}}
    @Test void mismatchedSessionRejectedBeforeCancelOrSourceWork(){try(var h=new Harness()){var r=input(new Guard(),1);var c=r.certificate();var wrong=new DhReplacementInput(new DhReplacementInput.Certificate(WORLD,EPOCH+1,RESOURCE,c.summaryGeneration(),EDIT,1,c.identity(),c.dependencyIdentity(),c.materialPolicy()),r.data().capture(),new Guard());assertThrows(IllegalStateException.class,()->h.view.prepareCoverage(1,mask(0),0,0,wrong));assertEquals(-1,h.n("highestRequestedCoverageGeneration"));}}
    @Test void cancellationIgnoringCompilerCannotPublishStaleReplacement() throws Exception {try(var h=new Harness()){h.commit(1,mask(0),null);var gates=new CountDownLatch[]{new CountDownLatch(1),new CountDownLatch(1)};h.driver.block.set(gates);var t=h.view.prepareCoverage(2,mask(10),0,0,input(new Guard(),1));assertTrue(gates[0].await(10,TimeUnit.SECONDS));h.view.cancelPrepared(t);gates[1].countDown();var next=h.prepare(3,mask(0),null);assertTrue(h.view.commitPrepared(next));assertFalse(h.view.commitPrepared(t));assertFalse(h.b("farSummarizesEdits"));}}
    @Test void uploadFailureIsNeverRecoverableGeometryDecline(){try(var h=new Harness()){h.commit(1,mask(0),null);h.driver.failUpload=true;var t=h.view.prepareCoverage(2,mask(10),0,0,input(new Guard(),1));assertThrows(IllegalStateException.class,()->until(()->h.view.isPrepared(t),h.view::pump));assertFalse(h.view.isReplacementDeclined(t));assertTrue(h.b("invalidated"));}}
    @Test void failedRetirementRetainsPhysicalOwnersForCloseRetry(){var h=new Harness();try{h.commit(1,mask(10),input(new Guard(),1));h.driver.all.forEach(m->m.failClose=true);var t=h.prepare(2,mask(0),null);assertThrows(IllegalStateException.class,()->h.view.commitPrepared(t));assertTrue(h.b("invalidated"));assertTrue(h.n("residentPageTileOwners")>0);assertThrows(IllegalStateException.class,h.view::close);h.driver.all.forEach(m->m.failClose=false);h.view.close();assertEquals(0,h.n("residentPageTileOwners"));}finally{h.driver.all.forEach(m->m.failClose=false);h.close();}}
    @Test void reentrantGuardInvalidationCannotPermitDraw(){try(var h=new Harness()){var g=new Guard();h.commit(1,mask(0),input(g,1));g.callback=h.view::requestInvalidation;h.draw();assertEquals(0,h.driver.draws);assertTrue(h.b("invalidated"));}}
    @Test void dataHasNoOwnerCallbackAndCertificateContentChangesIdentity(){var g=new Guard();var a=input(g,1);assertTrue(Arrays.stream(DhReplacementInput.Data.class.getDeclaredFields()).noneMatch(f->DhReplacementInput.AuthorityGuard.class.isAssignableFrom(f.getType())||f.getType()==DhReplacementInput.class));assertNotEquals(a.identity(),input(g,2).identity());assertNotEquals(a.identity(),input(g,1,"other").identity());assertEquals(a.identity(),input(new Guard(),1).identity());assertEquals(0,g.calls);assertEquals(32000,a.data().declaredBytes());}
    @Test void captureAndHaloIdentityNeverAliasEvenUnderSameCertificate(){try(var h=new Harness()){
        var g=new Guard();var first=input(g,1);h.commit(1,mask(10),first);long reuse=h.n("sameGeometryPreparations");
        var changed=new DhReplacementInput(first.certificate(),DhVoxelReplacementGeometry.capture(EDIT,(x,y,z)->y<=1000?0xff888888:0,()->{}),g);
        assertNotEquals(first.identity(),changed.identity());h.commit(2,mask(10),changed);assertEquals(reuse,h.n("sameGeometryPreparations"));
    }}
    @Test void pooledUnionCountsOnlyExactAliasesAndFailedRetirementOwners(){var face=List.of(1,2);var current=Map.of("base",face);var alias=DhReplacementGpuBudget.assess(current,current,128,8L*1024*1024);assertTrue(DhReplacementGpuBudget.fits(alias));assertEquals(0,alias.changedOwners());var changed=DhReplacementGpuBudget.assess(current,Map.of("base",List.of(2,1)),128,0);assertFalse(DhReplacementGpuBudget.fits(changed));assertEquals(129,changed.owners());var byteOverflow=DhReplacementGpuBudget.assess(Map.of(),Map.of("piece",face),0,8L*1024*1024-175);assertFalse(DhReplacementGpuBudget.fits(byteOverflow));assertThrows(IllegalArgumentException.class,()->DhReplacementGpuBudget.assess(Map.of(),Map.of("piece",Collections.nCopies(4097,1)),0,0));}
}
