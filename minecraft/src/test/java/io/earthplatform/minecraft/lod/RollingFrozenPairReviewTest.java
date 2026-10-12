package io.earthplatform.minecraft.lod;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.coregate.RollingEditPresentation;
import io.earthplatform.minecraft.network.EngineCubeSection;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.renderprobe.RenderPublicationGuard;
import io.earthplatform.minecraft.scene.SceneReadyCube;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import io.earthplatform.streaming.CubeStreamAdmission;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import org.joml.Matrix4f;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual native admission, retained guard, DH view and paired owner. Only GL allocations are fake. */
@Timeout(30)
class RollingFrozenPairReviewTest {
    private static final String ID="frozen-pair-review";
    private static final WorldIdentity WORLD=new WorldIdentity(17,"pair","1",ID,"native");
    private static final CubePos POSITION=new CubePos(0,0,0);
    private static EnginePaletteContext palette;
    @BeforeAll static void registries(){
        var registry=new MappedRegistry<Biome>(Registries.BIOME,Lifecycle.stable());
        registry.register(Biomes.PLAINS,VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),RegistrationInfo.BUILT_IN);
        palette=EnginePaletteContext.create(registry.freeze());
    }
    @Test void immutableOldPairSurvivesAuthoritativeRevisionAndPrivatePreparationWithoutBecomingCurrent(){try(var h=new Harness()){
        h.capture();var oldSnapshot=h.nativeJob.snapshot();String oldCoverage=h.coverageIdentity();int allocations=h.driver.meshes.size();
        h.replaceRevision(1);assertFalse(h.guard.isCurrent(h.nativeJob));
        assertFalse(h.guard.drawIfCurrent(List.of(h.nativeJob),()->fail("Historical revision entered current drawing")));
        var candidate=h.prepare(2,List.of(h.ready(1)));assertTrue(h.view.isPrepared(candidate));assertFalse(h.view.coverageReady(2));
        assertTrue(h.draw());assertEquals(oldCoverage,h.coverageIdentity());assertSame(oldSnapshot,h.nativeTicket.snapshots().getFirst());
        assertEquals(0,h.nativeTicket.snapshots().getFirst().revision());assertEquals(0,h.pair.stamp().revision());
        assertEquals("a".repeat(64),h.pair.stamp().manifestDigest());assertEquals(2,h.pair.retainedTicketOwners());
        assertEquals(allocations,h.driver.meshes.size(),"A revision-only ready change must reuse identical far geometry");
    }}
    @Test void cancellingPrivateCoveragePreservesTheExactOldPair(){try(var h=new Harness()){
        h.capture();h.replaceRevision(1);var pending=h.prepare(2,List.of(h.ready(1)));
        h.view.cancelPrepared(pending);h.view.pump();assertFalse(h.view.isPrepared(pending));
        assertTrue(h.draw());assertTrue(h.view.coverageReady(1));assertFalse(h.guard.isCurrent(h.nativeJob));
        assertEquals(1,h.pair.draws());
    }}
    @Test void successfulFarReplacementRevokesTheOldPairBeforeAnyRetiredBufferCallback(){try(var h=new Harness()){
        h.capture();var pending=h.prepare(2,List.of());var retired=new AtomicInteger();
        h.driver.beforeDelete=()->{retired.incrementAndGet();assertFalse(h.view.isPresentationCurrent(h.farTicket));};
        h.pair.invalidate();assertTrue(h.view.commitPrepared(pending));assertTrue(retired.get()>0);
        assertFalse(h.pair.current());assertFalse(h.draw());assertTrue(h.guard.isPresented(h.nativeTicket.tokenFor(h.source),h.source));
        h.pair.retire();assertEquals(0,h.pair.retainedTicketOwners());assertFalse(h.nativeTicket.isCurrent());
        h.source.close();h.driver.beforeDelete=()->{};
    }}
    @Test void nativeAppearanceAndLeaseAbaPreventEitherHalfFromSubmitting(){
        for(boolean appearance:new boolean[]{true,false})try(var h=new Harness()){
            h.capture();int farDraws=h.driver.draws;
            if(appearance)h.appearance.set(false);else{h.admission.replaceInterest(List.of());var newer=h.admission.replaceInterest(List.of(POSITION)).requested().getFirst();h.commit(newer,0);}
            assertFalse(h.draw());assertEquals(farDraws,h.driver.draws);assertEquals(0,h.nativeDraws);
            if(appearance)h.appearance.set(true);assertFalse(h.pair.current());assertEquals(2,h.pair.retainedTicketOwners());
        }
    }
    @Test void farSourceAbaRevokesBothSubmissionPathsAndCannotRevive(){try(var h=new Harness()){
        h.capture();h.sourceIdentity.set("different");assertThrows(IllegalStateException.class,h::draw);
        h.sourceIdentity.set(ID);assertFalse(h.draw());assertEquals(0,h.nativeDraws);assertEquals(0,h.driver.draws);
        assertFalse(h.view.coverageReady(1));assertEquals(2,h.pair.retainedTicketOwners());
    }}
    @Test void pairedCloseRevokesCapabilitiesWhilePhysicalCleanupFailureRemainsRetryable(){try(var h=new Harness()){
        h.capture();h.pair.close();assertFalse(h.nativeTicket.isCurrent());assertFalse(h.view.isPresentationCurrent(h.farTicket));
        h.driver.failDelete=true;assertThrows(IllegalStateException.class,h.view::close);
        assertFalse(h.view.diagnostics().get("ownedGlObjectsDeleted").getAsBoolean());assertTrue(h.driver.meshes.stream().anyMatch(m->!m.deleted));
        h.driver.failDelete=false;h.view.close();assertTrue(h.driver.meshes.stream().allMatch(m->m.deleted));
        assertEquals(0,h.pair.retainedTicketOwners());
    }}
    private static final class Harness implements AutoCloseable{
        final AtomicBoolean appearance=new AtomicBoolean(true);final AtomicReference<String> sourceIdentity=new AtomicReference<>(ID);
        final CubeStreamAdmission admission=new CubeStreamAdmission(WORLD,9,1);final RenderPublicationGuard guard=new RenderPublicationGuard(admission,1);
        final CubeStreamAdmission.Lease lease;final RenderPublicationGuard.Job nativeJob;final RenderPublicationGuard.PresentationSource source;
        final ExecutorService worker=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("frozen-pair-source").factory());
        final Driver driver=new Driver();final DhPreparedLodView view;
        final RollingEditPresentation<RenderPublicationGuard.PresentationLease,DhPreparedLodView.PresentationTicket> pair;
        RenderPublicationGuard.PresentationLease nativeTicket;DhPreparedLodView.PresentationTicket farTicket;int nativeDraws;
        Harness(){
            lease=admission.replaceInterest(List.of(POSITION)).requested().getFirst();commit(lease,0);
            nativeJob=guard.prepare(EngineCubeSnapshot.capture(POSITION,WORLD,9,lease.generation(),0,new EngineCubeSection(palette)));
            assertTrue(guard.publish(nativeJob,()->{}));source=guard.presentationSource(nativeJob,List.of(nativeJob),appearance::get);
            view=new DhPreparedLodView(new SceneSurfaceSampler(){public long surfaceY(long x,long z){return 8;}public int argb(long x,long z,long y){return 0xff808080;}public String identity(){return sourceIdentity.get();}public boolean supportsConcurrentReads(){return true;}},WORLD,9,1,0,0,driver,worker);
            view.initialize();assertTrue(view.commitPrepared(prepare(1,List.of(ready(0)))));
            pair=new RollingEditPresentation<>(new RollingEditPresentation.Driver<>(){
                public RenderPublicationGuard.PresentationLease captureNative(){return nativeTicket=guard.retainPresentation(List.of(source));}
                public DhPreparedLodView.PresentationTicket captureFar(){return farTicket=view.retainPresentation();}
                public boolean nativeCurrent(RenderPublicationGuard.PresentationLease t){return t.isCurrent();}
                public boolean farCurrent(DhPreparedLodView.PresentationTicket t){return view.isPresentationCurrent(t);}
                public void closeNative(RenderPublicationGuard.PresentationLease t){t.close();}
                public void closeFar(DhPreparedLodView.PresentationTicket t){t.close();}
            });
        }
        void capture(){pair.capture(new RollingEditPresentation.Stamp(9,1,1,1,0,1,"a".repeat(64)));}
        boolean draw(){return pair.draw((n,f)->view.drawPresented(f,new Matrix4f(),0,8,0,guard.resourceGeneration())&&guard.drawIfPresented(n.tokenFor(source),source,()->{nativeDraws++;return true;}));}
        void commit(CubeStreamAdmission.Lease l,long revision){var header=new CubeStreamAdmission.Header(WORLD,9,l.position(),l.generation(),revision);var token=admission.admit(header);assertTrue(token.accepted());assertEquals(CubeStreamAdmission.Rejection.NONE,admission.commit(token.token(),()->{}));}
        void replaceRevision(long revision){commit(lease,revision);}
        SceneReadyCube ready(long revision){return new SceneReadyCube(POSITION,WORLD,9,lease.generation(),revision,1);}
        String coverageIdentity(){return view.diagnostics().get("coverageIdentity").getAsString();}
        DhPreparedLodView.CoverageTicket prepare(long generation,List<SceneReadyCube> ready){var t=view.prepareCoverage(generation,ready);long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!view.isPrepared(t)){assertTrue(System.nanoTime()<end,"Preparation timeout");view.pump();try{Thread.sleep(1);}catch(InterruptedException e){throw new AssertionError(e);}}return t;}
        public void close(){driver.beforeDelete=()->{};driver.failDelete=false;sourceIdentity.set(ID);try{pair.close();source.close();guard.close();view.close();admission.close();assertTrue(driver.meshes.stream().allMatch(m->m.deleted));}finally{worker.shutdownNow();try{assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}}}
    }
    private static final class Mesh implements DhGeographicLodView.Mesh{
        final Driver driver;final int quads;boolean deleted;Mesh(Driver driver,int quads){this.driver=driver;this.quads=quads;}
        public int quads(){return quads;}public int bytes(){return quads*64;}public boolean deleted(){return deleted;}
        public void close(){if(deleted)return;driver.beforeDelete.run();if(driver.failDelete)throw new IllegalStateException("retryable physical deletion");deleted=true;}
    }
    private static final class Driver implements DhGeographicLodView.Driver{
        final Thread owner=Thread.currentThread();final List<Mesh> meshes=new ArrayList<>();boolean deleted,failDelete;int draws;Runnable beforeDelete=()->{};
        public void checkRenderThread(){assertSame(owner,Thread.currentThread());}public String renderer(){return "independent fake paired lifecycle driver";}
        public DhGeographicLodView.State captureState(){return ()->{};}public void initialize(){}
        public DhGeographicLodView.CpuCompiler cpuCompiler(){return (faces,cancel)->{cancel.run();assertNotSame(owner,Thread.currentThread());return new DhPreparedVertices(new byte[faces.size()*64]);};}
        public DhGeographicLodView.Mesh upload(DhGeographicLodData.TileKey k,List<DhTerrainGeometry.PageFace> f){throw new AssertionError("Prepared upload required");}
        public DhGeographicLodView.Mesh uploadPrepared(DhGeographicLodData.TileKey k,List<DhTerrainGeometry.PageFace> f,DhPreparedVertices v){var m=new Mesh(this,f.size());meshes.add(m);return m;}
        public DhGeographicLodView.DrawStats draw(Map<DhGeographicLodData.TileKey,DhGeographicLodView.Mesh> meshes,Matrix4f matrix,double x,double y,double z){draws++;assertTrue(meshes.values().stream().noneMatch(DhGeographicLodView.Mesh::deleted));return new DhGeographicLodView.DrawStats(meshes.size(),0,0);}
        public void close(){deleted=true;}public boolean deleted(){return deleted;}
    }
}
