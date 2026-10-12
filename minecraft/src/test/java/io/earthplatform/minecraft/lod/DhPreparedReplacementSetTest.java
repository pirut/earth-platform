package io.earthplatform.minecraft.lod;

import io.earthplatform.core.*;
import io.earthplatform.minecraft.enginebridge.*;
import io.earthplatform.minecraft.scene.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.joml.Matrix4f;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class DhPreparedReplacementSetTest {
    static final String SOURCE=DhAnalyticFlatPolicy.expectedDataset(128);
    static final WorldIdentity WORLD=new WorldIdentity(0,"earthplatform:analytic-native-cube","analytic-native-cube-v1/rolling-interior-v1",SOURCE,"test-palette");
    static final DhAnalyticFlatPolicy POLICY=new DhAnalyticFlatPolicy(SOURCE,WORLD,128,0xff808080);
    static final ReplacementWorkspaces WORKSPACES=ReplacementWorkspaces.two(new CubePos(0,8,0));
    static final long EPOCH=4,RESOURCE=2;
    static final class Guard implements DhReplacementSetInput.AuthorityGuard {
        final Thread owner=Thread.currentThread();boolean current=true,historical=true;Runnable callback=()->{};
        public boolean isCurrent(ReplacementSetCertificate c){assertSame(owner,Thread.currentThread());callback.run();return current;}
        public boolean permitsRetainedPresentation(ReplacementSetCertificate c){assertSame(owner,Thread.currentThread());callback.run();return historical;}
    }
    static NativeReplacementCapture capture(CubePos p,boolean checker){
        byte[] values=new byte[8000];int index=0;
        for(long y=p.minBlockY()-2;y<p.minBlockY()+18;y++)for(long z=p.minBlockZ()-2;z<p.minBlockZ()+18;z++)for(long x=p.minBlockX()-2;x<p.minBlockX()+18;x++){
            boolean inside=CubePos.fromBlock(x,y,z).equals(p);
            values[index++]=(byte)(checker&&inside?((x+y+z)&1)==0?1:0:y<=128?1:(p.x()==0&&x==4&&y==132&&z==4?2:0));
        }
        return new NativeReplacementCapture(p,values);
    }
    static List<NativeReplacementCapture> captures(){return WORKSPACES.positions().stream().map(p->capture(p,false)).toList();}
    static ReplacementSetCertificate certificate(List<NativeReplacementCapture> captures,long generation,long revA,long revB,String indexIdentity){
        var regions=new ArrayList<ReplacementSetCertificate.Region>();long lease=1;
        for(int i=0;i<2;i++){
            var c=captures.get(i);var deps=new ArrayList<RollingCubeActor.CubeVersion>();
            for(var p:WORKSPACES.dependencies(c.position()))deps.add(new RollingCubeActor.CubeVersion(p,lease++,p.equals(c.position())?(i==0?revA:revB):0));
            regions.add(new ReplacementSetCertificate.Region(c.position(),deps,c.contentIdentity()));
        }
        return new ReplacementSetCertificate(WORLD,EPOCH,RESOURCE,"00000000-0000-0000-0000-000000000001",indexIdentity,"provider","a".repeat(64),DhReplacementInput.ANALYTIC_MATERIAL_POLICY,"exact-owned18-read20-unit-faces-v1",generation,regions);
    }
    static DhReplacementSetInput input(Guard guard,long generation,long a,long b){return input(guard,generation,a,b,captures(),"c".repeat(64));}
    static DhReplacementSetInput input(Guard guard,long generation,long a,long b,List<NativeReplacementCapture> captures,String index){return new DhReplacementSetInput(POLICY,certificate(captures,generation,a,b,index),captures,guard);}
    static List<SceneReadyCube> ready(int x){var out=new ArrayList<SceneReadyCube>();for(int a=-1;a<=1;a++)for(int y=7;y<=9;y++)for(int z=-1;z<=1;z++)out.add(new SceneReadyCube(new CubePos(x+a,y,z),WORLD,EPOCH,1,0,RESOURCE));return out;}
    static long center(int x){return 256L*Math.floorDiv(16*x+8+128,256);}
    static final class Harness implements AutoCloseable {
        final DhPreparedReplacementTest.Driver driver=new DhPreparedReplacementTest.Driver();
        final ExecutorService executor=Executors.newSingleThreadExecutor();final AtomicReference<String> source=new AtomicReference<>(SOURCE);final DhPreparedLodView view;
        Harness(){view=new DhPreparedLodView(new SceneSurfaceSampler(){public long surfaceY(long x,long z){return 128;}public int argb(long x,long z,long y){return 0xff808080;}public String identity(){return source.get();}public boolean supportsConcurrentReads(){return true;}public boolean supportsFootprintCoverage(){return true;}public boolean knownFootprint(long x,long z,long xx,long zz){return true;}},WORLD,EPOCH,RESOURCE,0,0,DhLodLayout.LONG_4096,driver,executor);view.initialize();}
        DhPreparedLodView.CoverageTicket prepare(long gen,int x,DhReplacementSetInput input){var t=view.prepareCoverageSet(gen,ready(x),center(x),0,input);DhPreparedReplacementTest.until(()->view.isPrepared(t)||view.isReplacementDeclined(t),view::pump);return t;}
        void commit(long gen,int x,DhReplacementSetInput input){assertTrue(view.commitPrepared(prepare(gen,x,input)));}
        long n(String k){return view.diagnostics().get(k).getAsLong();}
        public void close(){try{view.close();assertEquals(0,n("residentPageTileOwners"));}finally{executor.shutdownNow();}}
    }
    @Test void cleanGeneratedFixtureIsAlwaysIncludedBeforeAnyEdit(){var value=input(new Guard(),1,0,0);assertEquals(2,value.data().members().size());assertEquals(16000,value.data().declaredBytes());assertTrue(value.data().members().stream().allMatch(v->v.certificate().durableRevision()==0));try(var h=new Harness()){h.commit(1,10,value);assertTrue(h.view.replacementSetReady(value.certificate().identity()));assertFalse(h.view.replacementReady(null));assertEquals(2,h.n("replacementSetRegions"));assertEquals(0,h.n("replacementSetEditedRegions"));assertTrue(h.n("replacementVisibleFaces")>0);}}
    @Test void completeSetNeedsAllowAndNonfocusChangeForcesFreshGeometry(){try(var h=new Harness()){var a=input(new Guard(),1,1,0);h.commit(1,0,a);long reused=h.n("sameGeometryPreparations");var b=input(new Guard(),2,1,1);var t=h.prepare(2,0,b);assertTrue(h.view.replacementSetReady(a.certificate().identity()));assertFalse(h.view.replacementSetReady(b.certificate().identity()));assertTrue(h.view.commitPrepared(t));assertFalse(h.view.commitPrepared(t));assertTrue(h.view.replacementSetReady(b.certificate().identity()));assertEquals(reused,h.n("sameGeometryPreparations"));}}
    @Test void unchangedSetMayReuseButNeedsFreshTicket(){try(var h=new Harness()){var a=input(new Guard(),1,1,1);h.commit(1,10,a);int uploads=h.driver.uploads;var t=h.prepare(2,10,a);assertTrue(h.view.coverageReady(1));assertTrue(h.view.commitPrepared(t));assertEquals(uploads,h.driver.uploads);assertEquals(1,h.n("sameGeometryCommits"));}}
    @Test void completeSetCurrentAndHistoricalRevocationAreSeparateAndOneWay(){try(var h=new Harness()){var g=new Guard();var a=input(g,1,1,1);h.commit(1,10,a);var old=h.view.retainPresentation();g.current=false;assertFalse(h.view.replacementSetReady(a.certificate().identity()));g.current=true;assertFalse(h.view.replacementSetReady(a.certificate().identity()));assertTrue(h.view.drawPresented(old,new Matrix4f(),0,129,0,RESOURCE));g.historical=false;assertFalse(h.view.isPresentationCurrent(old));g.historical=true;assertFalse(h.view.isPresentationCurrent(old));}}
    @Test void fullSetCommitRevokesOldHistoricalToken(){try(var h=new Harness()){var g=new Guard();h.commit(1,0,input(g,1,1,1));var old=h.view.retainPresentation();g.current=false;var b=input(new Guard(),2,1,2);h.commit(2,0,b);assertFalse(h.view.isPresentationCurrent(old));assertTrue(h.view.replacementSetReady(b.certificate().identity()));}}
    @Test void cannotDowngradeOrRemoveUncoveredOtherRegion(){try(var h=new Harness()){h.commit(1,0,input(new Guard(),1,1,1));assertThrows(IllegalStateException.class,()->h.view.prepareCoverage(2,ready(0),0,0,null));assertTrue(h.view.coverageReady(1));}}
    @Test void cancellationIgnoringWorkCannotResurrectPriorSet() throws Exception {try(var h=new Harness()){var a=input(new Guard(),1,1,1);h.commit(1,0,a);var gates=new CountDownLatch[]{new CountDownLatch(1),new CountDownLatch(1)};h.driver.block.set(gates);var old=h.view.prepareCoverageSet(2,ready(10),center(10),0,input(new Guard(),2,1,2));assertTrue(gates[0].await(10,TimeUnit.SECONDS));h.view.cancelPrepared(old);gates[1].countDown();h.commit(3,0,a);assertFalse(h.view.commitPrepared(old));assertTrue(h.view.replacementSetReady(a.certificate().identity()));}}
    @Test void sourceAndResourceChangesFenceWholeSetAndHistoricalPresentation(){try(var h=new Harness()){h.commit(1,0,input(new Guard(),1,1,1));var old=h.view.retainPresentation();assertFalse(h.view.drawPresented(old,new Matrix4f(),0,0,0,RESOURCE+1));h.source.set("changed");assertThrows(IllegalStateException.class,()->h.view.isPresentationCurrent(old));h.source.set(SOURCE);assertFalse(h.view.isPresentationCurrent(old));}}
    @Test void pendingSetGuardRevocationPreventsCommit(){try(var h=new Harness()){h.commit(1,0,input(new Guard(),1,1,1));var g=new Guard();var t=h.prepare(2,10,input(g,2,1,2));g.current=false;assertFalse(h.view.isPrepared(t));assertFalse(h.view.commitPrepared(t));h.view.pump();assertTrue(h.view.coverageReady(1));}}
    @Test void uploadFailureNeverBecomesRecoverableCapacityDecline(){try(var h=new Harness()){h.commit(1,0,input(new Guard(),1,1,1));h.driver.failUpload=true;var t=h.view.prepareCoverageSet(2,ready(10),center(10),0,input(new Guard(),2,1,2));assertThrows(IllegalStateException.class,()->DhPreparedReplacementTest.until(()->h.view.isPrepared(t),h.view::pump));assertFalse(h.view.isReplacementDeclined(t));}}
    @Test void failedCleanupRetainsOwnersForRetry(){var h=new Harness();try{h.commit(1,10,input(new Guard(),1,1,1));h.driver.all.forEach(m->m.failClose=true);assertThrows(IllegalStateException.class,h.view::close);assertTrue(h.n("residentPageTileOwners")>0);h.driver.all.forEach(m->m.failClose=false);h.view.close();assertEquals(0,h.n("residentPageTileOwners"));}finally{h.driver.all.forEach(m->m.failClose=false);h.close();}}
    @Test void dataCannotRetainGuardOrOwnerAndOrderMissingCaptureReject(){var g=new Guard();var caps=captures();var cert=certificate(caps,1,0,0,"c".repeat(64));assertThrows(IllegalArgumentException.class,()->new DhReplacementSetInput(POLICY,cert,List.of(caps.getLast(),caps.getFirst()),g));assertThrows(IllegalArgumentException.class,()->new DhReplacementSetInput(POLICY,cert,List.of(caps.getFirst()),g));for(var f:DhReplacementSetInput.Data.class.getDeclaredFields()){assertFalse(DhReplacementSetInput.AuthorityGuard.class.isAssignableFrom(f.getType()));assertNotEquals(DhReplacementSetInput.class,f.getType());}} 
    @Test void indexChangeCannotAliasSameIndividualCaptureAndRevisions(){var caps=captures();var a=input(new Guard(),1,1,1,caps,"c".repeat(64));var b=input(new Guard(),1,1,1,caps,"d".repeat(64));assertNotEquals(a.identity(),b.identity());assertNotEquals(a.data().members().getFirst().identity(),b.data().members().getFirst().identity());}
    @Test void staleSetEpochOrResourcesRejectBeforeReplacingCurrentPair(){try(var h=new Harness()){
        var a=input(new Guard(),1,1,1);h.commit(1,0,a);var c=a.certificate();
        for(boolean epochChange:new boolean[]{true,false}){
            var stale=new ReplacementSetCertificate(c.world(),c.epoch()+(epochChange?1:0),c.resourceGeneration()+(epochChange?0:1),c.storeIdentity(),c.indexIdentity(),c.providerIdentity(),c.paletteIdentity(),c.materialPolicy(),c.detailPolicy(),2,c.regions());
            var bad=new DhReplacementSetInput(POLICY,stale,captures(),new Guard());
            assertThrows(IllegalStateException.class,()->h.view.prepareCoverageSet(2,ready(0),0,0,bad));
            assertTrue(h.view.replacementSetReady(c.identity()));assertEquals(1,h.n("highestRequestedCoverageGeneration"));
        }
    }}
    @Test void changedCaptureLeaseBindsCompleteSetEvenSameDurableData(){
        var caps=captures();var c=certificate(caps,1,1,1,"c".repeat(64));var regions=new ArrayList<ReplacementSetCertificate.Region>();
        for(var r:c.regions())regions.add(new ReplacementSetCertificate.Region(r.position(),r.dependencies().stream().map(d->new RollingCubeActor.CubeVersion(d.position(),d.leaseGeneration()+100,d.revision())).toList(),r.captureIdentity()));
        var next=new ReplacementSetCertificate(c.world(),c.epoch(),c.resourceGeneration(),c.storeIdentity(),c.indexIdentity(),c.providerIdentity(),c.paletteIdentity(),c.materialPolicy(),c.detailPolicy(),c.generation(),regions);
        assertEquals(c.durableIdentity(),next.durableIdentity());assertNotEquals(c.identity(),next.identity());
        assertNotEquals(new DhReplacementSetInput(POLICY,c,caps,new Guard()).identity(),new DhReplacementSetInput(POLICY,next,caps,new Guard()).identity());
    }
    @Test void reentrantSetGuardCannotDrawOrPublishAfterInvalidation(){try(var h=new Harness()){
        var g=new Guard();var a=input(g,1,1,1);h.commit(1,0,a);g.callback=h.view::requestInvalidation;
        assertFalse(h.view.replacementSetReady(a.certificate().identity()));h.view.draw(new Matrix4f(),0,129,0,RESOURCE);assertEquals(0,h.driver.draws);
    }}
    @Test void changedNonfocusCaptureCannotReuseAnOldCompleteCertificate(){
        var caps=captures();var c=certificate(caps,1,1,1,"c".repeat(64));
        var b=caps.getLast().withMaterial(68,130,4,2);
        assertThrows(IllegalArgumentException.class,()->new DhReplacementSetInput(POLICY,c,List.of(caps.getFirst(),b),new Guard()));
    }

    @Test void cachedCompleteIdentityIsStableWithoutPerReadCertificateHashing(){
        var value=input(new Guard(),1,0,0);String id=value.certificateIdentity();
        assertEquals(value.certificate().identity(),id);assertSame(id,value.certificateIdentity());assertSame(id,value.data().certificateIdentity());
        var invokes=new ArrayList<String>();
        try(var stream=DhPreparedLodView.class.getResourceAsStream("DhPreparedLodView.class")){
            new org.objectweb.asm.ClassReader(stream).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9){
                @Override public org.objectweb.asm.MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions){
                    if(!name.equals("replacementSetReady"))return null;
                    return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9){
                        @Override public void visitMethodInsn(int opcode,String owner,String method,String desc,boolean isInterface){invokes.add(owner+"."+method);}
                    };
                }
            },0);
        }catch(java.io.IOException e){throw new AssertionError(e);}
        assertTrue(invokes.contains("io/earthplatform/minecraft/lod/DhReplacementSetInput.certificateIdentity"));
        assertFalse(invokes.contains("io/earthplatform/minecraft/enginebridge/ReplacementSetCertificate.identity"));
    }

}
